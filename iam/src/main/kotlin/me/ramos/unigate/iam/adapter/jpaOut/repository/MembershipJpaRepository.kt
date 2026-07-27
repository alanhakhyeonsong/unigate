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

  /**
   * 현재 유효한 멤버십 하나. `REVOKED` 를 제외하므로 부분 unique 인덱스(`uq_membership_active`)가
   * **최대 하나**임을 보장한다 — 반환 타입이 단수인 근거가 그 제약이다.
   */
  fun findByTenantIdAndUserRefAndStatusNot(
    tenantId: String,
    userRef: String,
    status: MembershipStatus,
  ): MembershipEntity?
}
