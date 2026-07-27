package me.ramos.unigate.iam.application.tenant.port.outbound

import me.ramos.unigate.iam.domain.membership.model.Membership
import me.ramos.unigate.iam.domain.tenant.model.Tenant
import me.ramos.unigate.iam.domain.tenant.vo.TenantId

/**
 * 테넌트 저장소 포트 (Phase 9c-2).
 *
 * ## 멤버십을 **같은 포트**에 둔 이유
 * `Membership` 은 별도 애그리거트지만 저장소 포트를 나누지 않았다. 테넌트 생성·멤버 추가는
 * 언제나 **한 트랜잭션**에서 함께 일어나고(쿼터 검사가 둘을 동시에 본다), 포트를 나누면
 * 유스케이스가 두 포트의 트랜잭션 참여를 신경 써야 한다.
 *
 * 멤버십 유스케이스가 독립적으로 커지면(P9d) 그때 나눈다 — 지금 나누면 소비자가 하나인
 * 포트를 둘 만드는 셈이다.
 */
interface TenantRepositoryPort {
  fun save(tenant: Tenant): Tenant

  fun findById(id: TenantId): Tenant?

  fun existsById(id: TenantId): Boolean

  fun saveMembership(membership: Membership): Membership

  /**
   * 쿼터에 계상되는 멤버 수 — `ACTIVE` 만 센다.
   *
   * 초대(`INVITED`)는 아직 자리를 차지하지 않는다는 도메인 규칙
   * (`MembershipStatus.countsTowardQuota`)을 쿼리로 옮긴 것이다. 두 곳이 어긋나면
   * 쿼터가 조용히 틀리므로, 규칙을 바꿀 때 **양쪽을 함께** 봐야 한다.
   */
  fun countActiveMembers(tenantId: TenantId): Int

  fun findMemberships(tenantId: TenantId): List<Membership>
}
