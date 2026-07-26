package me.ramos.unigate.iam.domain.tenant.exception

import me.ramos.unigate.iam.domain.common.exception.DomainException
import me.ramos.unigate.iam.domain.tenant.enums.TenantStatus

/**
 * 테넌트 도메인 예외. `sealed`로 서브타입을 한 파일에 봉인해 번역(application)에서 exhaustive 처리가 되게 한다.
 */
sealed class TenantDomainException(
  message: String,
  cause: Throwable? = null,
) : DomainException(message, cause) {
  /** 허용되지 않는 상태 전이. 어떤 전이가 거부됐는지 메시지에 남긴다. */
  class InvalidStatusTransition(
    from: TenantStatus,
    to: TenantStatus,
  ) : TenantDomainException("테넌트 상태를 $from 에서 $to 로 바꿀 수 없습니다")

  /** 쿼터 초과. */
  class QuotaExceeded(
    maxUsers: Int,
  ) : TenantDomainException("테넌트 멤버 수가 쿼터($maxUsers)에 도달했습니다")

  /** 멤버를 받을 수 없는 상태(ACTIVE 아님). */
  class NotAcceptingMembers(
    status: TenantStatus,
  ) : TenantDomainException("테넌트가 $status 상태라 새 멤버를 받을 수 없습니다")
}
