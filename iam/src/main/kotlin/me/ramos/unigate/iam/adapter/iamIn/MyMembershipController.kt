package me.ramos.unigate.iam.adapter.iamIn

import me.ramos.unigate.iam.application.tenant.service.MembershipNotFoundException
import me.ramos.unigate.iam.application.tenant.service.MembershipResult
import me.ramos.unigate.iam.application.tenant.service.MembershipService
import me.ramos.unigate.iam.application.tenant.service.MyMembershipResult
import me.ramos.unigate.iam.application.tenant.service.TenantNotFoundException
import me.ramos.unigate.iam.domain.tenant.exception.TenantDomainException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * 내 멤버십 — 초대 수락 (Phase 9d).
 *
 * ## ⚠️ 이 컨트롤러가 `/iam/admin` 아래에 **있으면 안 된다**
 * 초대·역할변경·해제는 관리자가 **남의 자원**을 다루지만, 수락은 초대받은 본인이 **자기 것**에
 * 하는 행위다. 관리 경로에 두면 두 가지가 어긋난다:
 *
 * 1. 일반 사용자가 `unigate-admin` 없이는 자기 초대를 수락할 수 없다 — 초대 기능이 무의미해진다
 * 2. 반대로 관리 경로를 열면 **관리자가 남의 초대를 대신 수락**할 수 있게 된다
 *
 * ## 인가 검사가 없는 이유
 * 대상을 **토큰 `sub` 로만** 정한다. 경로에 사용자 식별자가 없으므로 남의 초대를 수락할 방법이
 * 애초에 없다 — P8e 의 프로필 API 와 같은 IDOR-free 구조다(`docs/learning/20`).
 *
 * 경로에 `{tenantId}` 만 있고 `{userRef}` 가 없는 것이 그 설계의 표현이다.
 */
@RestController
@RequestMapping("/iam/memberships")
class MyMembershipController(
  private val membershipService: MembershipService,
) {
  /**
   * 내 멤버십 목록 — 수락 대기 중인 초대를 포함한다.
   *
   * ⚠️ **인가의 근거가 아니다.** 게이트는 여전히 토큰 claim 으로만 판단한다. 이 목록은
   * "초대가 와 있다"·"수락했으니 재로그인해야 반영된다" 를 화면이 말할 수 있게 하는 정보다.
   * 둘을 혼동해 "목록에 있으니 접근 가능" 으로 취급하면, 재로그인 전 사용자에게 되지 않는
   * 기능을 열어 보이게 된다.
   */
  @GetMapping
  fun listMine(authentication: JwtAuthenticationToken): List<MyMembershipResult> =
    membershipService.listMine(authentication.token.subject)

  /**
   * 내게 온 초대를 수락한다.
   *
   * **쿼터 검사가 여기서 일어난다.** 초대는 자리를 차지하지 않으므로, 정원이 찬 뒤 수락하면
   * 거부될 수 있다 — 결함이 아니라 "먼저 수락한 사람이 자리를 갖는다" 는 선택이다.
   */
  @PostMapping("/{tenantId}/accept")
  fun accept(
    @PathVariable tenantId: String,
    authentication: JwtAuthenticationToken,
  ): MembershipResult = membershipService.accept(tenantId, authentication.token.subject)

  @ExceptionHandler(MembershipNotFoundException::class)
  fun handleNotFound(e: MembershipNotFoundException): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "수락할 초대가 없습니다").apply {
      type = URI.create("urn:unigate:iam:membership-not-found")
      title = "Membership Not Found"
      setProperty("reasonCode", "membership_not_found")
      setProperty("tenantId", e.tenantId)
    }

  @ExceptionHandler(TenantNotFoundException::class)
  fun handleTenantNotFound(e: TenantNotFoundException): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "테넌트를 찾을 수 없습니다").apply {
      type = URI.create("urn:unigate:iam:tenant-not-found")
      title = "Tenant Not Found"
      setProperty("reasonCode", "tenant_not_found")
      setProperty("tenantId", e.tenantId)
    }

  /**
   * 정원 초과 — **409** 다.
   *
   * 403 이 아닌 이유: 권한 문제가 아니다. 400 도 아니다: 요청은 올바르고 **테넌트의 현재 상태**와
   * 충돌한 것이다. 자리가 나면 같은 요청이 성공하므로 "다시 시도해볼 수 있다" 는 뜻도 담긴다.
   */
  @ExceptionHandler(TenantDomainException.QuotaExceeded::class)
  fun handleQuotaExceeded(e: TenantDomainException.QuotaExceeded): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "테넌트 정원이 찼습니다").apply {
      type = URI.create("urn:unigate:iam:tenant-quota-exceeded")
      title = "Tenant Quota Exceeded"
      setProperty("reasonCode", "quota_exceeded")
    }

  @ExceptionHandler(TenantDomainException.NotAcceptingMembers::class)
  fun handleNotAccepting(e: TenantDomainException.NotAcceptingMembers): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "새 멤버를 받을 수 없는 상태입니다").apply {
      type = URI.create("urn:unigate:iam:tenant-not-accepting-members")
      title = "Tenant Not Accepting Members"
      setProperty("reasonCode", "tenant_not_accepting_members")
    }
}
