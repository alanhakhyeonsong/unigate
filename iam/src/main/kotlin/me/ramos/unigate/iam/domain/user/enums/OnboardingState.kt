package me.ramos.unigate.iam.domain.user.enums

/**
 * 온보딩 상태 — **outbox 패턴의 직접적인 결과물**이다.
 *
 * 가입은 IAM DB 쓰기와 Keycloak 사용자 생성이라는 **두 시스템 쓰기**로 이루어진다. outbox 를 택했으므로
 * (`IAM_PLATFORM_DECISION.md` §16) IAM DB 를 먼저 커밋하고 Keycloak 반영은 워커가 나중에 한다.
 * 그래서 **"프로필은 있는데 아직 신원이 없는" 중간 상태가 실재한다.**
 *
 * ```
 * PENDING_IDENTITY ──워커 성공──> ACTIVE
 *        │
 *        └──워커 실패(이메일 중복 등)──> IDENTITY_FAILED ──재시도──> PENDING_IDENTITY
 * ```
 *
 * 동기 방식(Keycloak 먼저)이었다면 이 상태들이 없었을 것이다. 대신 Keycloak 에 고아 사용자가 남는
 * 문제를 안았을 것이다 — 어느 쪽이든 **분산 일관성의 대가**를 어딘가에서 치른다.
 */
enum class OnboardingState {
  /**
   * IAM DB 에는 저장됐으나 Keycloak 사용자가 **아직 없다.** `UserRef` 가 `null` 인 유일한 상태다.
   *
   * ⚠️ 이 상태의 사용자는 **로그인할 수 없다.** FE 는 "처리 중"을 표현해야 한다.
   */
  PENDING_IDENTITY,

  /** Keycloak 사용자 생성 완료, `UserRef` 보유. 정상 사용 가능. */
  ACTIVE,

  /**
   * Keycloak 생성이 실패했다(이메일 중복 등). 사용자에게 알리고 정정을 받아야 한다.
   *
   * 중복 판정의 SoT 는 Keycloak 이라 **IAM DB 를 먼저 쓴 시점에는 알 수 없다.** outbox 를 택한 대가다.
   */
  IDENTITY_FAILED,
  ;

  fun canTransitionTo(target: OnboardingState): Boolean =
    when (this) {
      PENDING_IDENTITY -> target == ACTIVE || target == IDENTITY_FAILED
      // 정정 후 재시도를 허용한다. 실패가 종착이면 사용자는 영영 가입할 수 없다.
      IDENTITY_FAILED -> target == PENDING_IDENTITY
      ACTIVE -> false
    }
}
