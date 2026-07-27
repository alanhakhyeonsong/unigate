package me.ramos.unigate.iam.adapter.iamIn

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import me.ramos.unigate.iam.application.tenant.service.ChangeRoleCommand
import me.ramos.unigate.iam.application.tenant.service.CreateTenantCommand
import me.ramos.unigate.iam.application.tenant.service.CreateTenantResult
import me.ramos.unigate.iam.application.tenant.service.CreateTenantService
import me.ramos.unigate.iam.application.tenant.service.InviteMemberCommand
import me.ramos.unigate.iam.application.tenant.service.MembershipAlreadyExistsException
import me.ramos.unigate.iam.application.tenant.service.MembershipNotFoundException
import me.ramos.unigate.iam.application.tenant.service.MembershipResult
import me.ramos.unigate.iam.application.tenant.service.MembershipService
import me.ramos.unigate.iam.application.tenant.service.RevokeMembershipCommand
import me.ramos.unigate.iam.application.tenant.service.TenantAlreadyExistsException
import me.ramos.unigate.iam.application.tenant.service.TenantNotAcceptingMembersException
import me.ramos.unigate.iam.application.tenant.service.TenantNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * 테넌트 관리 API (Phase 9c-2).
 *
 * ## 인가는 경로 규칙이 한다
 * `IamSecurityConfig` 가 `/iam/admin` 하위 전체에 `unigate-admin` 을 요구한다(P9c-1).
 * 메서드마다 `@PreAuthorize` 를 붙이지 않는 이유는 [OutboxAdminController] 와 같다 —
 * **새 메서드를 추가하고 애노테이션을 잊으면 인증만으로 뚫린다.**
 *
 * ## 201 이지만 의미는 202 에 가깝다
 * 응답 시점의 테넌트는 `PENDING` 이다. Keycloak group 프로비저닝이 끝나야 쓸 수 있으므로
 * "만들어졌다" 와 "쓸 수 있다" 사이에 간격이 있다. 그 간격을 응답의 `status` 로 드러낸다 —
 * 클라이언트가 그것을 보고 대기 UI 를 띄울 수 있어야 한다.
 *
 * 201 을 유지한 것은 **자원이 실제로 생겼기 때문**이다(조회하면 있다). 202 는 "아직 아무것도
 * 안 생겼을 수 있다" 는 뜻이라 사실과 다르다.
 */
@RestController
@RequestMapping("/iam/admin/tenants")
class TenantAdminController(
  private val createTenantService: CreateTenantService,
  private val membershipService: MembershipService,
) {
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  fun create(
    @Valid @RequestBody request: CreateTenantRequest,
    authentication: JwtAuthenticationToken,
  ): CreateTenantResult =
    createTenantService.create(
      CreateTenantCommand(
        tenantId = request.tenantId,
        displayName = request.displayName,
        // ⚠️ 생성자를 **본문에서 받지 않는다.** 받으면 관리자가 남을 생성자로 지정할 수 있고,
        // 그건 감사 기록을 위조할 수 있다는 뜻이다. 행위자는 언제나 토큰이 정한다.
        creatorRef = authentication.token.subject,
        maxUsers = request.maxUsers,
      ),
    )

  /** 테넌트 멤버 목록. */
  @GetMapping("/{tenantId}/members")
  fun listMembers(
    @PathVariable tenantId: String,
  ): MemberListResponse = MemberListResponse(members = membershipService.list(tenantId))

  /**
   * 멤버 초대. `INVITED` 로 시작하며 **쿼터를 차지하지 않는다** — 자리 계산은 수락 시점이다.
   */
  @PostMapping("/{tenantId}/members")
  @ResponseStatus(HttpStatus.CREATED)
  fun invite(
    @PathVariable tenantId: String,
    @Valid @RequestBody request: InviteMemberRequest,
    authentication: JwtAuthenticationToken,
  ): MembershipResult =
    membershipService.invite(
      InviteMemberCommand(
        tenantId = tenantId,
        userRef = request.userRef,
        role = request.role,
        // 행위자는 언제나 토큰이 정한다. 본문에서 받으면 감사를 위조할 수 있다.
        actorRef = authentication.token.subject,
      ),
    )

  /** 멤버 역할 변경. */
  @PatchMapping("/{tenantId}/members/{userRef}")
  fun changeRole(
    @PathVariable tenantId: String,
    @PathVariable userRef: String,
    @Valid @RequestBody request: ChangeRoleRequest,
    authentication: JwtAuthenticationToken,
  ): MembershipResult =
    membershipService.changeRole(
      ChangeRoleCommand(
        tenantId = tenantId,
        userRef = userRef,
        role = request.role,
        actorRef = authentication.token.subject,
      ),
    )

  /**
   * 멤버십 해제(초대 취소 · 멤버 제거).
   *
   * 204 가 아니라 **200 + 본문**이다. 해제 후 상태(`REVOKED`)를 돌려주면 클라이언트가 다시
   * 조회하지 않아도 되고, 무엇보다 **무엇이 해제됐는지**가 응답에 남는다.
   */
  @DeleteMapping("/{tenantId}/members/{userRef}")
  fun revoke(
    @PathVariable tenantId: String,
    @PathVariable userRef: String,
    authentication: JwtAuthenticationToken,
  ): MembershipResult =
    membershipService.revoke(
      RevokeMembershipCommand(
        tenantId = tenantId,
        userRef = userRef,
        actorRef = authentication.token.subject,
      ),
    )

  @ExceptionHandler(TenantNotFoundException::class)
  fun handleTenantNotFound(e: TenantNotFoundException): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "테넌트를 찾을 수 없습니다").apply {
      type = URI.create("urn:unigate:iam:tenant-not-found")
      title = "Tenant Not Found"
      setProperty("reasonCode", "tenant_not_found")
      setProperty("tenantId", e.tenantId)
    }

  @ExceptionHandler(MembershipNotFoundException::class)
  fun handleMembershipNotFound(e: MembershipNotFoundException): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "멤버십을 찾을 수 없습니다").apply {
      type = URI.create("urn:unigate:iam:membership-not-found")
      title = "Membership Not Found"
      setProperty("reasonCode", "membership_not_found")
      setProperty("tenantId", e.tenantId)
    }

  @ExceptionHandler(MembershipAlreadyExistsException::class)
  fun handleMembershipDuplicate(e: MembershipAlreadyExistsException): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "이미 멤버십이 있습니다").apply {
      type = URI.create("urn:unigate:iam:membership-already-exists")
      title = "Membership Already Exists"
      setProperty("reasonCode", "membership_already_exists")
      setProperty("currentStatus", e.status)
    }

  @ExceptionHandler(TenantNotAcceptingMembersException::class)
  fun handleNotAccepting(e: TenantNotAcceptingMembersException): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "새 멤버를 받을 수 없는 상태입니다").apply {
      type = URI.create("urn:unigate:iam:tenant-not-accepting-members")
      title = "Tenant Not Accepting Members"
      setProperty("reasonCode", "tenant_not_accepting_members")
      setProperty("currentStatus", e.status)
    }

  @ExceptionHandler(TenantAlreadyExistsException::class)
  fun handleDuplicate(e: TenantAlreadyExistsException): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "이미 존재하는 테넌트 id 입니다").apply {
      type = URI.create("urn:unigate:iam:tenant-already-exists")
      title = "Tenant Already Exists"
      setProperty("reasonCode", "tenant_already_exists")
      setProperty("tenantId", e.tenantId)
    }

  /**
   * `TenantId` VO 의 형식 규칙 위반은 `IllegalArgumentException` 으로 올라온다.
   *
   * 그대로 두면 500 이다 — 잘못된 입력인데 서버 오류로 보이면 클라이언트가 재시도한다.
   * 400 으로 바꿔 "고쳐서 다시 보내라" 를 분명히 한다.
   */
  @ExceptionHandler(IllegalArgumentException::class)
  fun handleInvalidInput(e: IllegalArgumentException): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: "잘못된 요청입니다").apply {
      type = URI.create("urn:unigate:iam:invalid-tenant-request")
      title = "Invalid Tenant Request"
      setProperty("reasonCode", "invalid_tenant_request")
    }
}

data class InviteMemberRequest(
  /** 초대 대상의 Keycloak `sub`. 이메일이 아닌 이유는 email 이 Keycloak SoT 라 표류하기 때문이다. */
  @field:NotBlank(message = "userRef 는 필수입니다")
  val userRef: String,
  /** 형식 검증은 `TenantRole` VO 가 한다. */
  @field:NotBlank(message = "role 은 필수입니다")
  val role: String,
)

data class ChangeRoleRequest(
  @field:NotBlank(message = "role 은 필수입니다")
  val role: String,
)

data class MemberListResponse(
  val members: List<MembershipResult>,
)

data class CreateTenantRequest(
  /**
   * 테넌트 slug. Keycloak group 경로(`/tenants/{id}`)가 되므로 **만든 뒤 바꿀 수 없다.**
   * 형식 검증은 `TenantId` VO 가 한다 — 여기서는 비어 있는지만 본다.
   */
  @field:NotBlank(message = "tenantId 는 필수입니다")
  val tenantId: String,
  @field:NotBlank(message = "displayName 은 필수입니다")
  val displayName: String,
  /** `null` 이면 기본 쿼터. 0 이하는 "아무도 못 들어옴" 이라 실수일 가능성이 높아 막는다. */
  @field:Positive(message = "maxUsers 는 1 이상이어야 합니다")
  val maxUsers: Int? = null,
)
