package me.ramos.unigate.iam.domain.membership.model

import me.ramos.unigate.iam.domain.membership.enums.MembershipStatus
import me.ramos.unigate.iam.domain.membership.exception.MembershipDomainException
import me.ramos.unigate.iam.domain.membership.vo.TenantRole
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.tenant.vo.TenantId
import java.time.Instant

/**
 * 사용자 ↔ 테넌트 **관계 애그리거트**.
 *
 * ## Keycloak이 못 하는 것이 바로 이것이다
 * Keycloak의 group 소속은 **단일·평면**이다. 한 사용자가 여러 테넌트에 **각각 다른 역할로** 속하고,
 * 각 소속마다 초대 상태·수락 시각·초대자가 다른 구조는 표현할 수 없다
 * (`IAM_PLATFORM_DECISION.md` §6.1). 그 다대다 관계를 IAM이 소유하고, Keycloak에는 그중
 * **인증에 필요한 부분집합만 투영**한다.
 *
 * ## 식별자를 왜 (userRef, tenantId) 조합으로 두는가
 * 별도 대리키 대신 자연키를 쓴다. "한 사용자는 한 테넌트에 하나의 활성 멤버십만 갖는다"는 규칙이
 * 곧 식별자가 되어, 중복 가입을 저장소 제약(unique)으로 막을 수 있기 때문이다.
 * 다만 REVOKED 이력을 남기려면 상태를 포함한 부분 unique 가 필요하다 — 스키마 설계 시점(P8b 이후)의 숙제다.
 */
class Membership private constructor(
  val userRef: UserRef,
  val tenantId: TenantId,
  val invitedBy: UserRef?,
  val invitedAt: Instant,
  role: TenantRole,
  status: MembershipStatus,
  joinedAt: Instant?,
) {
  var role: TenantRole = role
    private set

  var status: MembershipStatus = status
    private set

  /** 초대를 수락해 실제 멤버가 된 시각. `INVITED` 상태에서는 `null`이다. */
  var joinedAt: Instant? = joinedAt
    private set

  /**
   * 초대를 수락한다.
   *
   * @param at 수락 시각. **도메인이 `Instant.now()`를 직접 부르지 않는다** — 그러면 시간에 의존해
   *   테스트가 불안정해지고, "언제"를 호출부가 통제할 수 없다(배치 재처리 등).
   */
  fun accept(at: Instant) {
    transitionTo(MembershipStatus.ACTIVE)
    joinedAt = at
  }

  /** 철회·탈퇴. `INVITED`(초대 취소)와 `ACTIVE`(멤버 제거) 양쪽에서 가능하다. */
  fun revoke() = transitionTo(MembershipStatus.REVOKED)

  /**
   * 역할을 변경한다. **REVOKED 멤버십의 역할은 바꿀 수 없다** — 이미 관계가 끝났는데 권한을
   * 조작하는 것은 의미가 없고, 감사 관점에서도 혼란스럽다.
   */
  fun changeRole(newRole: TenantRole) {
    if (status == MembershipStatus.REVOKED) {
      throw MembershipDomainException.RoleChangeOnRevoked(tenantId.value)
    }
    role = newRole
  }

  /** 쿼터에 계상되는가. 초대 상태는 아직 자리를 차지하지 않는다. */
  fun countsTowardQuota(): Boolean = status.countsTowardQuota()

  private fun transitionTo(target: MembershipStatus) {
    if (!status.canTransitionTo(target)) {
      throw MembershipDomainException.InvalidStatusTransition(status, target)
    }
    status = target
  }

  companion object {
    /**
     * 초대 생성 — `INVITED`로 시작한다.
     *
     * 초대는 아직 멤버가 아니므로 **쿼터를 차지하지 않는다.** 쿼터 검사는 수락 시점에 해야 하며,
     * 그 판단은 [me.ramos.unigate.iam.domain.tenant.model.Tenant.ensureCanAcceptMember]가 한다.
     */
    fun invite(
      userRef: UserRef,
      tenantId: TenantId,
      role: TenantRole,
      invitedBy: UserRef?,
      invitedAt: Instant,
    ): Membership =
      Membership(
        userRef = userRef,
        tenantId = tenantId,
        invitedBy = invitedBy,
        invitedAt = invitedAt,
        role = role,
        status = MembershipStatus.INVITED,
        joinedAt = null,
      )

    /**
     * 초대 절차 없이 곧바로 활성 멤버로 만든다.
     *
     * 테넌트 생성자(첫 tenant-admin)처럼 **초대할 사람이 없는** 경우에 쓴다
     * (`IAM_PLATFORM_DECISION.md` §7.5).
     */
    fun joinDirectly(
      userRef: UserRef,
      tenantId: TenantId,
      role: TenantRole,
      at: Instant,
    ): Membership =
      Membership(
        userRef = userRef,
        tenantId = tenantId,
        invitedBy = null,
        invitedAt = at,
        role = role,
        status = MembershipStatus.ACTIVE,
        joinedAt = at,
      )

    fun restore(
      userRef: UserRef,
      tenantId: TenantId,
      role: TenantRole,
      status: MembershipStatus,
      invitedBy: UserRef?,
      invitedAt: Instant,
      joinedAt: Instant?,
    ): Membership =
      Membership(
        userRef = userRef,
        tenantId = tenantId,
        invitedBy = invitedBy,
        invitedAt = invitedAt,
        role = role,
        status = status,
        joinedAt = joinedAt,
      )
  }
}
