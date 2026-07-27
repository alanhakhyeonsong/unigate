package me.ramos.unigate.iam.domain.user.exception

import me.ramos.unigate.iam.domain.common.exception.DomainException
import me.ramos.unigate.iam.domain.user.enums.OnboardingState

/** 사용자 프로필 도메인 예외. */
sealed class UserProfileDomainException(
  message: String,
  cause: Throwable? = null,
) : DomainException(message, cause) {
  class InvalidStateTransition(
    from: OnboardingState,
    to: OnboardingState,
  ) : UserProfileDomainException("온보딩 상태를 $from 에서 $to 로 바꿀 수 없습니다")

  /** `ACTIVE` 인데 `UserRef` 가 없는 등, 상태와 데이터가 어긋난 경우. */
  class InconsistentIdentity(
    detail: String,
  ) : UserProfileDomainException("프로필의 신원 정보가 상태와 일치하지 않습니다: $detail")

  /**
   * 아직 Keycloak 신원이 없는 프로필에 신원 기반 변경을 시도했다 (이메일 변경).
   *
   * 이 상태의 이메일 정정은 **가입 재시도** 경로가 담당한다 — 바꿀 대상이 아직 없기 때문이다.
   */
  class IdentityNotReady(
    state: OnboardingState,
  ) : UserProfileDomainException("신원이 준비되지 않아 이 변경을 할 수 없습니다 (현재 상태: $state)")

  /**
   * 이미 반영 대기 중인 이메일 변경이 있다.
   *
   * 덮어쓰지 않고 거절한다 — 앞선 outbox 지시가 남은 채 뒤 요청과 경쟁하면 워커의 처리 순서에
   * 따라 최종 값이 요청 순서와 달라질 수 있다. 순서를 보장할 수 없으면 겹쳐 만들지 않는 편이 낫다.
   */
  class EmailChangeInProgress(
    pendingEmail: String,
  ) : UserProfileDomainException("이미 반영 대기 중인 이메일 변경이 있습니다: $pendingEmail")

  /** 현재와 같은 이메일로의 변경. 외부 시스템을 두드릴 이유가 없다. */
  class EmailUnchanged(
    email: String,
  ) : UserProfileDomainException("현재 이메일과 동일합니다: $email")
}
