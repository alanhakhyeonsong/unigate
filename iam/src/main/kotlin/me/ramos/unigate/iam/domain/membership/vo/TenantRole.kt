package me.ramos.unigate.iam.domain.membership.vo

/**
 * 테넌트 안에서의 역할.
 *
 * ## 왜 enum이 아니라 VO인가
 * 역할은 제품 사정에 따라 늘어난다(`billing-admin`, `auditor` …). enum으로 고정하면 역할 하나 추가에
 * 도메인 배포가 필요하고, 테넌트별 커스텀 역할은 아예 표현할 수 없다.
 *
 * 대신 **형식은 제한한다.** 이 값이 Keycloak role 이름(`tenant-{id}-{role}`)으로 투영되므로
 * (`IAM_PLATFORM_DECISION.md` §7.1), 공백이나 대문자가 섞이면 role 이름 규칙이 깨진다.
 */
@JvmInline
value class TenantRole(
  val value: String,
) {
  init {
    require(value.matches(PATTERN)) {
      "역할 이름은 소문자·숫자·하이픈만 허용하며 2~32자여야 합니다: $value"
    }
  }

  companion object {
    private val PATTERN = Regex("^[a-z][a-z0-9-]{0,30}[a-z0-9]$")

    /** 테넌트 관리자 — 멤버 초대·역할 부여 권한의 기준이 되는 역할. */
    fun tenantAdmin(): TenantRole = TenantRole("tenant-admin")

    /** 일반 멤버 기본 역할. */
    fun member(): TenantRole = TenantRole("member")
  }
}
