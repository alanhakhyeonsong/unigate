package me.ramos.unigate.iam.domain.membership.exception

import me.ramos.unigate.iam.domain.common.exception.DomainException
import me.ramos.unigate.iam.domain.membership.enums.MembershipStatus

/** 멤버십 도메인 예외. */
sealed class MembershipDomainException(
  message: String,
  cause: Throwable? = null,
) : DomainException(message, cause) {
  class InvalidStatusTransition(
    from: MembershipStatus,
    to: MembershipStatus,
  ) : MembershipDomainException("멤버십 상태를 $from 에서 $to 로 바꿀 수 없습니다")

  class RoleChangeOnRevoked(
    tenantId: String,
  ) : MembershipDomainException("철회된 멤버십($tenantId)의 역할은 변경할 수 없습니다")
}
