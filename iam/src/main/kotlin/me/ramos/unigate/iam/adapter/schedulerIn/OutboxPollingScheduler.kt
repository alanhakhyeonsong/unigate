package me.ramos.unigate.iam.adapter.schedulerIn

import me.ramos.unigate.iam.application.outbox.service.OutboxProcessor
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

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
 * ## 한 번의 폴링에서 여러 건을 처리한다
 * 건별 트랜잭션([OutboxProcessor.processOne])을 반복하되 [MAX_BATCH] 에서 끊는다.
 * 무한 루프로 두면 밀린 일감이 많을 때 한 인스턴스가 스케줄러 스레드를 계속 붙잡는다.
 *
 * ## 끌 수 있게 해둔 이유
 * 통합 테스트에서 스케줄러가 제멋대로 도는 것을 막아야 한다. 워커를 아예 띄우지 않는 인스턴스
 * (예: API 전용 노드)를 구성할 여지도 남긴다.
 */
@Component
@ConditionalOnProperty(name = ["unigate.iam.outbox.polling.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxPollingScheduler(
  private val outboxProcessor: OutboxProcessor,
) {
  private val log = LoggerFactory.getLogger(javaClass)

  /**
   * `fixedDelay` 를 쓴다 — 이전 실행이 **끝난 뒤** 간격을 센다.
   *
   * `fixedRate` 였다면 처리가 간격보다 오래 걸릴 때 실행이 겹쳐 쌓인다. 외부 API 호출이 포함된
   * 작업이라 지연이 들쭉날쭉하므로 `fixedDelay` 가 안전하다.
   */
  @Scheduled(
    fixedDelayString = "\${unigate.iam.outbox.polling.delay-ms:5000}",
    initialDelayString = "\${unigate.iam.outbox.polling.initial-delay-ms:10000}",
  )
  fun poll() {
    var processed = 0
    try {
      while (processed < MAX_BATCH && outboxProcessor.processOne()) {
        processed++
      }
    } catch (e: Exception) {
      // ⚠️ 스케줄러 메서드에서 예외가 새어나가면 **다음 실행이 취소될 수 있다.**
      // 여기서 반드시 잡아 로그만 남기고 다음 주기를 기약한다.
      log.error("outbox 폴링 중 예기치 못한 오류 — 다음 주기에 재시도한다", e)
    }
    if (processed > 0) {
      log.info("outbox 폴링 처리 건수={}", processed)
    }
  }

  companion object {
    /** 한 폴링에서 처리할 최대 건수. 스케줄러 스레드를 오래 붙잡지 않기 위한 상한이다. */
    private const val MAX_BATCH = 20
  }
}
