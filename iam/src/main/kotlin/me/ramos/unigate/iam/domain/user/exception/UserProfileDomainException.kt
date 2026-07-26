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
}
