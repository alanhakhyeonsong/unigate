package me.ramos.unigate.iam.application.outbox.model

import java.time.Duration
import java.time.Instant

/**
 * 외부 시스템 반영 지시 — outbox 패턴의 레코드.
 *
 * ## 왜 `domain` 이 아니라 `application` 에 있나
 * outbox 는 **기술 패턴**이지 IAM 도메인 개념이 아니다. 테넌트·멤버십·프로필과 달리 "비즈니스가
 * 말하는 것" 이 아니라 "두 시스템에 걸친 쓰기를 어떻게 안전하게 할 것인가" 라는 조정 문제다.
 * 도메인에 넣으면 도메인이 인프라 사정을 알게 된다.
 *
 * 그래도 순수 Kotlin 이라 상태 전이·백오프 계산을 프레임워크 없이 테스트할 수 있다
 * (ArchUnit 이 `application` 의 기술 의존을 막는다).
 *
 * ## 상태
 * ```
 * PENDING ──성공──> COMPLETED
 *    │
 *    ├──재시도 가능 실패──> PENDING (attempts++, next_attempt_at 뒤로)
 *    └──재시도 불가/한도초과──> DEAD (수동 개입 필요)
 * ```
 */
data class OutboxRecord(
  val id: Long?,
  val eventType: OutboxEventType,
  val payload: String,
  val status: OutboxStatus,
  val attempts: Int,
  val nextAttemptAt: Instant,
  val lastError: String?,
  /**
   * `DEAD` 가 된 시각. 그 외 상태에서는 null.
   *
   * `updated_at` 으로 갈음하지 않는 이유: 죽은 뒤에도 레코드는 갱신될 수 있고(운영자 재처리 등),
   * 그러면 "언제 죽었나" 가 덮여 사라진다. 사후 조사에서 가장 먼저 묻는 값이라 따로 둔다.
   */
  val deadAt: Instant? = null,
  /**
   * 마지막 실패의 예외 **클래스명**(FQCN). 메시지가 아니다.
   *
   * 메시지에는 외부 응답 본문이 섞여 토큰·secret 이 들어올 수 있다(`CLAUDE.md` §8).
   * 클래스명은 그 위험 없이 "무엇이 터졌나" 를 알려준다.
   */
  val lastExceptionClass: String? = null,
) {
  /**
   * 재시도 가능한 실패. 시도 횟수를 늘리고 **지수 백오프**로 다음 시각을 미룬다.
   *
   * 한도([MAX_ATTEMPTS])를 넘으면 `DEAD` 로 떨어뜨린다 — 무한 재시도는 외부 시스템을 계속
   * 두드리면서 문제를 감추기만 한다.
   *
   * @param reason 실패 사유 **코드**. **외부 예외 메시지를 그대로 넣지 않는다**(토큰·secret 유입 방지).
   * @param exceptionClass 예외 FQCN. 사후 조사용이며 메시지와 달리 민감정보가 없다.
   */
  fun failedRetryable(
    now: Instant,
    reason: String,
    exceptionClass: String? = null,
  ): OutboxRecord {
    val nextAttempts = attempts + 1
    return if (nextAttempts >= MAX_ATTEMPTS) {
      copy(
        status = OutboxStatus.DEAD,
        attempts = nextAttempts,
        lastError = reason,
        deadAt = now,
        lastExceptionClass = exceptionClass,
      )
    } else {
      copy(
        status = OutboxStatus.PENDING,
        attempts = nextAttempts,
        nextAttemptAt = now.plus(backoffFor(nextAttempts)),
        lastError = reason,
        lastExceptionClass = exceptionClass,
      )
    }
  }

  /**
   * 재시도해도 소용없는 실패. 즉시 `DEAD` 로 보낸다.
   *
   * 두 종류가 여기로 온다:
   * - **정정이 필요한 실패**(이메일 중복 등) — 사람이 개입해야 한다
   * - **버그성 실패**(역직렬화 불가, 대응 프로필 없음 등) — 코드를 고쳐야 한다
   *
   * 둘 다 재시도로 해결되지 않는다는 점이 같다. 이 구분이 없으면 "고칠 수 없는 실패" 를
   * 상한까지 재시도하며 로그만 더럽힌다.
   */
  fun failedPermanently(
    now: Instant,
    reason: String,
    exceptionClass: String? = null,
  ): OutboxRecord =
    copy(
      status = OutboxStatus.DEAD,
      attempts = attempts + 1,
      lastError = reason,
      deadAt = now,
      lastExceptionClass = exceptionClass,
    )

  /**
   * `DEAD` 레코드를 다시 처리 대상으로 되돌린다 — 운영자의 수동 재처리 (Phase 9c).
   *
   * ## `attempts` 를 0 으로 초기화한다
   * 재처리는 **"원인을 고쳤으니 다시 해보라"** 는 사람의 판단이다. 이전 시도 횟수를 이어서 세면
   * 한 번만 더 실패해도 즉시 DEAD 로 돌아가, 고쳐진 문제를 확인할 기회가 없다.
   *
   * ## 실패 흔적은 지우지 않는다
   * [lastError]·[lastExceptionClass] 를 남겨둔다. 재처리가 또 실패했을 때 **직전 실패와 같은
   * 원인인지** 비교할 수 있어야 하고, 감사 기록만으로는 그 대조가 어렵다.
   * 다만 [deadAt] 은 지운다 — 지금은 죽어 있지 않으므로 남겨두면 조회에서 거짓 양성이 된다.
   *
   * ⚠️ 이 전이는 **멱등하지 않다.** 같은 레코드를 두 번 requeue 하면 두 번 처리된다.
   * 처리 자체가 멱등하므로(`IdentityProviderPort.createUser` 계약) 결과는 같지만,
   * 호출자는 이미 PENDING 인 레코드를 되돌리지 않도록 상태를 확인해야 한다.
   */
  fun requeued(now: Instant): OutboxRecord =
    copy(
      status = OutboxStatus.PENDING,
      attempts = 0,
      nextAttemptAt = now,
      deadAt = null,
    )

  fun completed(): OutboxRecord =
    copy(
      status = OutboxStatus.COMPLETED,
      attempts = attempts + 1,
      lastError = null,
      lastExceptionClass = null,
    )

  companion object {
    /**
     * 이 횟수에 도달하면 DEAD.
     *
     * ## 10 → 5 로 줄였다 (Phase 9b) — 차단기와 **짝을 이룰 때만** 성립하는 값이다
     *
     * 예전에는 10이었다. 외부 장애가 길어져도 정상 건이 죽지 않게 하려면 그만큼 필요했다.
     * 하지만 그건 "외부가 죽었다"와 "이 레코드가 가망 없다"를 **한 축으로 뭉뚱그린** 값이라,
     * 대가로 진짜 문제 있는 레코드도 25분 넘게 붙잡고 있었다.
     *
     * 두 축을 나눈 지금은 5로 충분하다:
     * - 외부 시스템 장애 → [OutboxCircuit] 이 **클레임 자체를 멈춰** attempts 가 오르지 않는다
     * - 이 레코드 고유의 실패 → attempts 가 오른다
     *
     * ⚠️ **차단기 없이 이 값만 줄이면 안 된다.** 2분 남짓한 외부 장애에도 정상 가입이 DEAD 로
     * 떨어져 지금보다 나빠진다. 둘은 함께 도입해야 하는 한 쌍이다.
     */
    const val MAX_ATTEMPTS = 5

    private val BASE_BACKOFF = Duration.ofSeconds(10)
    private val MAX_BACKOFF = Duration.ofMinutes(5)

    /** 새 지시 생성 — 즉시 처리 대상(`nextAttemptAt = now`). */
    fun pending(
      eventType: OutboxEventType,
      payload: String,
      now: Instant,
    ): OutboxRecord =
      OutboxRecord(
        id = null,
        eventType = eventType,
        payload = payload,
        status = OutboxStatus.PENDING,
        attempts = 0,
        nextAttemptAt = now,
        lastError = null,
      )

    /**
     * 지수 백오프 — 10s, 20s, 40s … 상한 5분.
     *
     * 상한을 두는 이유: 무한히 늘리면 일시 장애가 복구된 뒤에도 한참을 기다린다.
     */
    fun backoffFor(attempts: Int): Duration {
      val multiplier = 1L shl (attempts - 1).coerceIn(0, 30)
      val backoff = BASE_BACKOFF.multipliedBy(multiplier)
      return if (backoff > MAX_BACKOFF) MAX_BACKOFF else backoff
    }
  }
}

enum class OutboxEventType {
  /** Keycloak 에 사용자를 생성하라. payload 는 [me.ramos.unigate.iam.application.user.dto.CreateKeycloakUserPayload]. */
  CREATE_KEYCLOAK_USER,

  /**
   * Keycloak 에 테넌트 group(`/tenants/{id}`)을 만들라 (Phase 9c-2).
   * payload 는 [me.ramos.unigate.iam.application.tenant.dto.CreateTenantGroupPayload].
   *
   * ## outbox 의 **두 번째 사용처**다
   * 테넌트 생성도 가입과 같은 모양의 문제다 — IAM DB 쓰기와 Keycloak 쓰기가 한 트랜잭션에
   * 묶이지 않는다. 같은 해법(로컬 DB 에 지시를 남기고 워커가 반영)을 그대로 쓴다.
   *
   * P9b 에서 워커의 결함(미분류 예외 무한 재시도)을 먼저 고친 이유가 이것이다 —
   * 약한 기반 위에 사용처를 늘리면 같은 결함이 새 경로에도 그대로 복제된다.
   */
  CREATE_KEYCLOAK_GROUP,

  /**
   * 사용자를 테넌트 group 에 넣으라 (Phase 9d). payload 는
   * [me.ramos.unigate.iam.application.tenant.dto.GroupMembershipPayload].
   *
   * 초대가 **수락된 시점**에 발행된다. 초대만으로는 발행하지 않는다 —
   * `INVITED` 는 아직 멤버가 아니고, 토큰 claim 에 실리면 안 되기 때문이다.
   */
  ADD_GROUP_MEMBER,

  /**
   * 사용자를 테넌트 group 에서 빼라 (Phase 9d).
   *
   * ⚠️ **지연이 곧 권한 잔존**이다. 다른 지시들은 늦어도 "아직 못 쓴다" 로 끝나지만,
   * 이것은 늦으면 **떠난 사람이 계속 접근할 수 있다.** 게다가 이미 발급된 토큰은 만료(현재 5분)
   * 전까지 유효하므로, outbox 지연 + 토큰 수명이 더해진 시간이 실제 잔존 시간이다.
   */
  REMOVE_GROUP_MEMBER,

  /**
   * Keycloak 사용자의 이메일을 바꾸라. payload 는
   * [me.ramos.unigate.iam.application.user.dto.UpdateKeycloakEmailPayload].
   *
   * ## 이 지시만 **보상**을 갖는다
   * 다른 지시들은 실패해도 "아직 반영되지 않은 상태" 로 멈추면 그만이다(테넌트는 PENDING 에
   * 머물고, group 멤버는 추가되지 않는다). 이메일 변경은 **요청 자체가 로컬에 기록**되므로
   * (`user_profile.pending_email`), 영구 실패하면 그 기록을 지워야 한다. 안 지우면 도메인이
   * "진행 중" 으로 보고 **다음 변경 요청을 영원히 거절**한다.
   */
  UPDATE_KEYCLOAK_EMAIL,
}

enum class OutboxStatus {
  /** 처리 대기. 워커가 `next_attempt_at <= now` 인 것만 집는다. */
  PENDING,

  /** 처리 완료. */
  COMPLETED,

  /** 더 이상 자동 재시도하지 않는다. 운영자가 봐야 한다. */
  DEAD,
}
