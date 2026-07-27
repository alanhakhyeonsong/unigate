package me.ramos.unigate.iam.adapter.schedulerIn

import me.ramos.unigate.iam.application.outbox.service.OutboxProcessor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * outbox 폴링 스케줄러 — driving 어댑터.
 *
 * ## 다중 인스턴스에서 **ShedLock 같은 분산 락이 필요 없다**
 * 보통 `@Scheduled` 를 여러 인스턴스에 띄우면 같은 일을 중복 실행할까 봐 분산 락을 건다.
 * 여기서는 **필요 없다.** 중복 실행을 막는 지점이 스케줄러가 아니라 **DB 행 잠금**
 * (`SELECT ... FOR UPDATE SKIP LOCKED`)이기 때문이다.
 *
 * 인스턴스 N 개가 동시에 폴링해도:
 * - 각자 **서로 다른 행**을 집는다(이미 잠긴 행은 SKIP).
 * - 같은 행을 두 번 처리하는 일이 없다.
 * - 인스턴스를 늘리면 **처리량이 늘어난다** — 분산 락을 걸었다면 한 번에 하나만 돌아 늘지 않는다.
 *
 * 즉 분산 락은 여기서 안전장치가 아니라 **병목**이 된다.
 *
 * ## 적응형 폴링 (Phase 9b) — 고정 5초를 버린 이유
 *
 * 고정 간격은 두 요구를 동시에 만족시키지 못한다.
 *
 * | | 짧은 고정 간격 | 긴 고정 간격 |
 * |---|---|---|
 * | 일감이 있을 때 | 지연이 작다 ✅ | 가입 후 반영이 늦다 ❌ |
 * | 일감이 없을 때 | **빈 쿼리를 계속 날린다** ❌ | 조용하다 ✅ |
 *
 * 그래서 결과에 따라 간격을 조절한다 — **처리했으면 최소 간격으로 리셋**하고(뒤에 밀린 일감이
 * 더 있을 가능성이 높다), **비었으면 두 배씩 늘린다**(상한까지).
 *
 * 대가는 `@Scheduled(fixedDelay)` 를 쓸 수 없다는 것이다. 그 값은 기동 시 고정되어 런타임에
 * 바뀌지 않으므로, [TaskScheduler] 로 **다음 실행을 매번 직접 예약**한다.
 *
 * ## 회로가 열렸을 때
 * [OutboxProcessor.processOne] 이 `false` 를 돌려주므로 "일감 없음" 과 같은 경로를 타 간격이
 * 늘어난다. 의도한 동작이다 — 외부 시스템이 죽었는데 촘촘히 깨어날 이유가 없다.
 */
@Component
@ConditionalOnProperty(name = ["unigate.iam.outbox.polling.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxPollingScheduler(
  private val outboxProcessor: OutboxProcessor,
  private val taskScheduler: TaskScheduler,
  private val clock: Clock,
  @Value("\${unigate.iam.outbox.polling.initial-delay-ms:10000}")
  private val initialDelayMs: Long,
) {
  private val log = LoggerFactory.getLogger(javaClass)
  private val currentInterval = AtomicReference(MIN_INTERVAL)

  /**
   * 기동이 끝나면 **자기 재예약 루프**를 시작한다. 이후 주기는 [scheduleNext] 가 정한다.
   *
   * `@Scheduled` 를 쓰지 않는 이유: 그 애노테이션은 고정 주기를 전제하므로 적응형 간격과 맞지 않는다.
   * `fixedDelay = Long.MAX_VALUE` 같은 편법으로 "한 번만 실행" 을 흉내 낼 수는 있지만, 읽는 사람에게
   * 의도가 전달되지 않는다.
   *
   * `ApplicationReadyEvent` 를 기다리는 것은 **DataSource·Flyway 가 준비된 뒤**에 첫 폴링이
   * 나가게 하기 위함이다. 빈 초기화 시점에 시작하면 마이그레이션 중인 테이블을 집을 수 있다.
   */
  @EventListener(ApplicationReadyEvent::class)
  fun start() {
    log.info("outbox 폴링 시작 — {}ms 후 첫 실행", initialDelayMs)
    scheduleAt(Duration.ofMillis(initialDelayMs))
  }

  private fun poll() {
    var processed = 0
    try {
      while (processed < MAX_BATCH && outboxProcessor.processOne()) {
        processed++
      }
    } catch (e: Exception) {
      // ⚠️ 여기서 예외가 새어나가면 **재예약이 일어나지 않아 워커가 영영 멈춘다.**
      // `@Scheduled` 시절에는 다음 주기가 알아서 왔지만, 자기 재예약 방식에서는 이 catch 가
      // 유일한 안전망이다. 반드시 잡고 [scheduleNext] 로 넘어가야 한다.
      log.error("outbox 폴링 중 예기치 못한 오류 — 다음 주기에 재시도한다", e)
    }

    if (processed > 0) {
      log.info("outbox 폴링 처리 건수={}", processed)
    }
    scheduleNext(processed)
  }

  /**
   * 다음 폴링을 예약한다.
   *
   * @param processed 이번에 처리한 건수. 0 이면 간격을 늘리고, 하나라도 있으면 최소로 되돌린다.
   */
  private fun scheduleNext(processed: Int) {
    val next =
      if (processed > 0) {
        MIN_INTERVAL
      } else {
        val doubled = currentInterval.get().multipliedBy(BACKOFF_MULTIPLIER)
        if (doubled > MAX_INTERVAL) MAX_INTERVAL else doubled
      }
    currentInterval.set(next)
    scheduleAt(next)
  }

  private fun scheduleAt(delay: Duration) {
    try {
      taskScheduler.schedule({ poll() }, Instant.now(clock).plus(delay))
    } catch (e: Exception) {
      // 종료 중이면 스케줄러가 거절한다. 그때 예외를 던지면 셧다운 로그가 시끄러워질 뿐이다.
      log.warn("outbox 폴링 재예약 실패 — 애플리케이션 종료 중일 수 있다: {}", e.message)
    }
  }

  companion object {
    /** 한 폴링에서 처리할 최대 건수. 스케줄러 스레드를 오래 붙잡지 않기 위한 상한이다. */
    private const val MAX_BATCH = 20

    private const val BACKOFF_MULTIPLIER = 2L

    /** 일감이 있을 때의 간격. 가입 직후 반영 지연을 좌우한다. */
    private val MIN_INTERVAL: Duration = Duration.ofMillis(500)

    /**
     * 유휴 시 상한. 이 값이 곧 **최악의 반영 지연**이다 — 마지막 폴링이 비어서 잠든 직후 가입이
     * 들어오면 최대 이만큼 기다린다. 지연과 빈 쿼리 비용의 절충점이다.
     */
    private val MAX_INTERVAL: Duration = Duration.ofSeconds(10)
  }
}
