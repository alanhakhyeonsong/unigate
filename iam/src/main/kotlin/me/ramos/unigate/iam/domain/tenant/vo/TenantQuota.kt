package me.ramos.unigate.iam.domain.tenant.vo

/**
 * 테넌트 쿼터 — 복합 값 객체.
 *
 * **Keycloak이 절대 모르는 개념**이다. "이 테넌트에 사용자를 몇 명까지 둘 수 있는가", "어떤 기능이
 * 켜져 있는가"는 순수 비즈니스 규칙이며, 이것이 IAM 도메인의 무게를 이룬다.
 *
 * @param maxUsers 이 테넌트가 보유할 수 있는 최대 활성 멤버 수. `null`이면 무제한.
 * @param featureFlags 켜진 기능 이름 집합. 단순 문자열 집합으로 두는 이유는 기능 목록이 제품 사정에
 *   따라 자주 바뀌기 때문이다 — enum으로 고정하면 기능 추가마다 도메인 배포가 필요해진다.
 */
data class TenantQuota(
  val maxUsers: Int?,
  val featureFlags: Set<String> = emptySet(),
) {
  init {
    require(maxUsers == null || maxUsers > 0) { "maxUsers는 null(무제한)이거나 1 이상이어야 합니다: $maxUsers" }
    require(featureFlags.none { it.isBlank() }) { "featureFlags에 빈 값을 넣을 수 없습니다" }
  }

  /**
   * 현재 멤버가 [currentMemberCount]명일 때 한 명 더 받을 수 있는가.
   *
   * 경계값이 중요하다: `maxUsers=10`이고 현재 10명이면 **거부**다(초과 불가). 9명이면 허용.
   */
  fun allowsAdditionalMember(currentMemberCount: Int): Boolean {
    require(currentMemberCount >= 0) { "현재 멤버 수는 음수일 수 없습니다: $currentMemberCount" }
    return maxUsers == null || currentMemberCount < maxUsers
  }

  fun hasFeature(feature: String): Boolean = feature in featureFlags

  companion object {
    /** 신규 테넌트 기본 쿼터. 무제한이 아니라 **보수적 기본값**을 준다 — 실수로 무한정 열리는 것을 막는다. */
    fun defaultQuota(): TenantQuota = TenantQuota(maxUsers = DEFAULT_MAX_USERS)

    fun unlimited(): TenantQuota = TenantQuota(maxUsers = null)

    private const val DEFAULT_MAX_USERS = 50
  }
}
