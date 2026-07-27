package me.ramos.unigate.iam.adapter.jpaOut

import com.fasterxml.jackson.databind.ObjectMapper
import me.ramos.unigate.iam.adapter.jpaOut.entity.MembershipEntity
import me.ramos.unigate.iam.adapter.jpaOut.entity.TenantEntity
import me.ramos.unigate.iam.adapter.jpaOut.repository.MembershipJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.TenantJpaRepository
import me.ramos.unigate.iam.application.tenant.port.outbound.TenantRepositoryPort
import me.ramos.unigate.iam.domain.membership.enums.MembershipStatus
import me.ramos.unigate.iam.domain.membership.model.Membership
import me.ramos.unigate.iam.domain.membership.vo.TenantRole
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.tenant.model.Tenant
import me.ramos.unigate.iam.domain.tenant.vo.TenantId
import me.ramos.unigate.iam.domain.tenant.vo.TenantQuota
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * [TenantRepositoryPort] 의 JPA 구현 (Phase 9c-2).
 *
 * ## 도메인 ↔ 엔티티 매핑이 여기서 끝난다
 * 도메인 [Tenant] 는 `private constructor` + 팩토리(`create`/`restore`)라 밖에서 임의 상태로
 * 만들 수 없다. 저장소에서 되살릴 때는 **`restore`** 를 쓴다 — 전이 규칙을 거치지 않고
 * 상태를 그대로 복원하는 전용 통로다. `create` 를 쓰면 저장된 ACTIVE 테넌트가 읽을 때마다
 * PENDING 으로 되돌아간다.
 */
@Component
class JpaTenantAdapter(
  private val tenantRepository: TenantJpaRepository,
  private val membershipRepository: MembershipJpaRepository,
  private val objectMapper: ObjectMapper,
) : TenantRepositoryPort {
  override fun save(tenant: Tenant): Tenant {
    val existing = tenantRepository.findById(tenant.id.value).orElse(null)
    val entity =
      if (existing == null) {
        TenantEntity.newTenant(
          id = tenant.id.value,
          displayName = tenant.displayName,
          status = tenant.status,
          maxUsers = tenant.quota.maxUsers,
          featureFlags = objectMapper.writeValueAsString(tenant.quota.featureFlags),
        )
      } else {
        existing.apply {
          displayName = tenant.displayName
          status = tenant.status
          maxUsers = tenant.quota.maxUsers
          featureFlags = objectMapper.writeValueAsString(tenant.quota.featureFlags)
          updatedAt = Instant.now()
        }
      }
    return tenantRepository.save(entity).also { it.markNotNew() }.toModel()
  }

  override fun findById(id: TenantId): Tenant? = tenantRepository.findById(id.value).orElse(null)?.toModel()

  override fun existsById(id: TenantId): Boolean = tenantRepository.existsById(id.value)

  override fun saveMembership(membership: Membership): Membership =
    membershipRepository.save(membership.toEntity()).toModel()

  override fun countActiveMembers(tenantId: TenantId): Int =
    membershipRepository.countByTenantIdAndStatus(tenantId.value, MembershipStatus.ACTIVE)

  override fun findMemberships(tenantId: TenantId): List<Membership> =
    membershipRepository.findByTenantId(tenantId.value).map { it.toModel() }

  override fun findActiveOrInvited(
    tenantId: TenantId,
    userRef: UserRef,
  ): Membership? =
    membershipRepository
      .findByTenantIdAndUserRefAndStatusNot(tenantId.value, userRef.value, MembershipStatus.REVOKED)
      ?.toModel()

  /**
   * 기존 행을 찾아 **변경분만 반영**한다.
   *
   * `saveMembership` 처럼 새 엔티티를 만들어 저장하면 id 가 없어 **INSERT 가 되고**,
   * 부분 unique 인덱스에 걸리거나(활성 중복) 이력이 두 벌 생긴다.
   */
  override fun updateMembership(membership: Membership): Membership {
    val entity =
      membershipRepository
        .findByTenantIdAndUserRefAndStatusNot(
          membership.tenantId.value,
          membership.userRef.value,
          MembershipStatus.REVOKED,
        ) ?: return saveMembership(membership)

    entity.role = membership.role.value
    entity.status = membership.status
    entity.joinedAt = membership.joinedAt
    entity.updatedAt = Instant.now()
    return membershipRepository.save(entity).toModel()
  }

  // ── 매핑 — 엔티티가 이 파일 밖으로 나가지 않는다 ────────────────────────

  private fun TenantEntity.toModel(): Tenant =
    Tenant.restore(
      id = TenantId(id),
      displayName = displayName,
      status = status,
      quota =
        TenantQuota(
          maxUsers = maxUsers,
          featureFlags = objectMapper.readValue(featureFlags, Array<String>::class.java).toSet(),
        ),
    )

  private fun Membership.toEntity(): MembershipEntity =
    MembershipEntity(
      tenantId = tenantId.value,
      userRef = userRef.value,
      role = role.value,
      status = status,
      invitedBy = invitedBy?.value,
      invitedAt = invitedAt,
      joinedAt = joinedAt,
    )

  private fun MembershipEntity.toModel(): Membership =
    Membership.restore(
      userRef = UserRef(userRef),
      tenantId = TenantId(tenantId),
      role = TenantRole(role),
      status = status,
      invitedBy = invitedBy?.let { UserRef(it) },
      invitedAt = invitedAt,
      joinedAt = joinedAt,
    )
}
