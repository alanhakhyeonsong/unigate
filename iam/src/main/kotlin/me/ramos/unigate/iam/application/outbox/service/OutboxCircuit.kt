package me.ramos.unigate.iam.application.outbox.service

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * outbox 워커 전용 회로 차단기 — **레코드를 집기 전에** 외부 시스템 상태를 묻는다.
 *
 * ## 왜 필요한가 — 진짜 이유는 성능이 아니라 `attempts` 보호다
 *
 * Keycloak 이 죽으면 워커는 5초마다 계속 레코드를 집어 실패시킨다. 그때 올라가는 것은
 * **레코드의 재시도 횟수**다. 즉 **잘못이 없는 정상 가입 건이 외부 장애 때문에 `DEAD` 로 떨어진다.**
 *
 * ```
 * (차단기 없음) Keycloak 다운 20분 → 백오프를 타고 계속 재시도 → attempts 소진 → DEAD
 * (차단기 있음) 연속 실패 감지 → 클레임 자체를 멈춤 → attempts 그대로 → 복구 후 정상 처리
 * ```
 *
 * 이것이 [OutboxRecord.MAX_ATTEMPTS] 를 10 에서 5 로 줄일 수 있는 전제다. 차단기 없이 횟수만
 * 줄이면 **지금보다 나빠진다** — 짧은 장애에도 정상 건이 죽는다.
 *
 * ## 왜 resilience4j 가 아닌가
 * 게이트웨이는 resilience4j 를 쓴다(Phase 3). 여기서 같은 선택을 하지 않은 이유:
 *
 * | | resilience4j | 여기서 필요한 것 |
 * |---|---|---|
 * | 모델 | **호출을 감싼다**(decorator) | 호출 **전에 상태를 묻는다**(gate) |
 * | 동시성 제어 | 슬라이딩 윈도우·half-open 동시 호출 제한 | 워커는 **한 번에 1건 순차** 처리라 불필요 |
 * | 레이어 | 기술 의존 → 포트·어댑터가 필요 | 순수 Kotlin 이라 `application` 에 그대로 둔다 |
 *
 * 필요한 것이 상태 3개와 카운터 하나뿐이라, 라이브러리를 들이면 포트를 하나 더 만들게 되고
 * 그것이 "소비자 없는 추상화" 를 늘린다(Phase 5 재정의).
 *
 * ## ⚠️ 이 차단기는 **인스턴스 로컬**이다
 * 다중 인스턴스에서 각 워커가 자기 차단기를 갖는다. 공유하지 않는 것은 의도적이다 —
 * 상태를 공유하려면 Valkey 같은 외부 저장소가 필요하고, 그러면 **장애 대응 장치가 또 다른
 * 인프라에 의존**하게 된다. 인스턴스마다 몇 번씩 실패를 겪는 대가는 작다.
 *
 * ## `synchronized` 를 쓰지 않는다
 * Virtual Thread 에서 `synchronized` 블록 안의 블로킹은 캐리어 스레드를 **pin** 시킨다
 * (`CLAUDE.md` §1.1, P8c 에서 `ReentrantLock` 을 택한 것과 같은 이유). 여기서는 상태를 불변
 * 스냅샷으로 묶어 [AtomicReference] 로 갈아끼우므로 락 자체가 필요 없다.
 */
@Component
class OutboxCircuit(
  private val clock: Clock,
) {
  private val state = AtomicReference(Snapshot.closed())

  /**
   * 지금 외부 반영을 시도해도 되는가.
   *
   * `false` 면 워커는 **레코드를 집지 않는다** — 집어서 실패시키면 그게 곧 `attempts` 소진이다.
   *
   * 열림 시간이 지나면 **한 건만** 통과시켜 복구를 확인한다(half-open). 그 한 건이 성공하면 닫히고,
   * 실패하면 다시 열림 시간을 처음부터 센다.
   */
  fun canAttempt(): Boolean {
    val now = Instant.now(clock)
    return state
      .updateAndGet { snapshot ->
        when {
          snapshot.openedAt == null -> snapshot
          // 열린 지 충분히 지났다 → 탐색 1건 허용(half-open 진입)
          !now.isBefore(snapshot.openedAt.plus(OPEN_DURATION)) -> snapshot.copy(probing = true)
          else -> snapshot
        }
      }.let { it.openedAt == null || it.probing }
  }

  /** 성공했다 → 회로를 닫고 실패 카운터를 초기화한다. */
  fun recordSuccess() {
    state.set(Snapshot.closed())
  }

  /**
   * 실패했다 → 연속 실패가 [FAILURE_THRESHOLD] 에 닿으면 회로를 연다.
   *
   * half-open 탐색 중의 실패는 **즉시** 다시 연다. 복구되지 않았다는 뜻이므로 카운터를 더 셀 이유가 없다.
   */
  fun recordFailure() {
    val now = Instant.now(clock)
    state.updateAndGet { snapshot ->
      val failures = snapshot.consecutiveFailures + 1
      when {
        snapshot.probing -> Snapshot(consecutiveFailures = failures, openedAt = now, probing = false)
        failures >= FAILURE_THRESHOLD -> Snapshot(consecutiveFailures = failures, openedAt = now, probing = false)
        else -> snapshot.copy(consecutiveFailures = failures)
      }
    }
  }

  /** 메트릭·로그용. 회로가 열려 있으면 `true`. */
  fun isOpen(): Boolean = state.get().openedAt != null

  /**
   * 회로 상태 스냅샷 — **불변**이라 원자적으로 통째로 교체할 수 있다.
   *
   * @property openedAt 회로가 열린 시각. `null` 이면 닫힘(정상).
   * @property probing half-open 탐색 1건이 허용된 상태인가.
   */
  private data class Snapshot(
    val consecutiveFailures: Int,
    val openedAt: Instant?,
    val probing: Boolean,
  ) {
    companion object {
      fun closed() = Snapshot(consecutiveFailures = 0, openedAt = null, probing = false)
    }
  }

  companion object {
    /**
     * 연속 이 횟수만큼 실패하면 회로를 연다.
     *
     * 레코드별 재시도 상한([OutboxRecord.MAX_ATTEMPTS])과 **다른 축**이다. 이쪽은 "외부 시스템이
     * 죽었는가"를 보고, 저쪽은 "이 레코드가 가망이 있는가"를 본다.
     */
    const val FAILURE_THRESHOLD = 5

    /**
     * 회로를 열어두는 시간. 이 시간이 지나면 탐색 1건을 흘려보낸다.
     *
     * 짧으면 죽은 외부 시스템을 계속 두드리고, 길면 복구를 늦게 알아챈다. 30초는 Keycloak
     * 롤링 재시작(수 초~수십 초) 동안 몇 번만 탐색하도록 잡은 값이다.
     */
    private val OPEN_DURATION: Duration = Duration.ofSeconds(30)
  }
}
