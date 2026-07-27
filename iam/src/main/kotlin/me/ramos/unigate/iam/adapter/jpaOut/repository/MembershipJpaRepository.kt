package me.ramos.unigate.iam.adapter.jpaOut.repository

import me.ramos.unigate.iam.adapter.jpaOut.entity.MembershipEntity
import me.ramos.unigate.iam.domain.membership.enums.MembershipStatus
import org.springframework.data.jpa.repository.JpaRepository

interface MembershipJpaRepository : JpaRepository<MembershipEntity, Long> {
  fun findByTenantId(tenantId: String): List<MembershipEntity>

  /**
   * 쿼터 계산용. `ACTIVE` 만 세는 것은 도메인 규칙(`MembershipStatus.countsTowardQuota`)을
   * 쿼리로 옮긴 것이다 — 두 곳이 어긋나면 쿼터가 조용히 틀린다.
   */
  fun countByTenantIdAndStatus(
    tenantId: String,
    status: MembershipStatus,
  ): Int
}
