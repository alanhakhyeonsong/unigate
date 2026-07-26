package me.ramos.unigate.iam.domain.membership.enums

/**
 * 멤버십 상태.
 *
 * ```
 * INVITED ──수락──> ACTIVE
 *    │                │
 *    └───철회/탈퇴────> REVOKED
 * ```
 *
 * `REVOKED`는 종착이다. 다시 넣으려면 **새 멤버십을 만든다** — 재초대 이력이 남아야 감사가 성립하고,
 * 되살리기를 허용하면 "언제부터 멤버였는가"(joinedAt)가 모호해진다.
 */
enum class MembershipStatus {
  /** 초대됐으나 아직 수락 전. **쿼터를 차지하지 않는다**(활성 멤버가 아니므로). */
  INVITED,

  /** 정상 멤버. 쿼터에 계상된다. */
  ACTIVE,

  /** 철회·탈퇴(종착). */
  REVOKED,
  ;

  fun canTransitionTo(target: MembershipStatus): Boolean =
    when (this) {
      INVITED -> target == ACTIVE || target == REVOKED
      ACTIVE -> target == REVOKED
      REVOKED -> false
    }

  /** 쿼터에 계상되는 상태인가. 초대 상태는 아직 자리를 차지하지 않는다. */
  fun countsTowardQuota(): Boolean = this == ACTIVE
}
