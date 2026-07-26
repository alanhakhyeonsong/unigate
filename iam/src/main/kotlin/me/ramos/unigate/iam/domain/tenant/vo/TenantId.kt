package me.ramos.unigate.iam.domain.tenant.vo

/**
 * 테넌트 식별자.
 *
 * Keycloak group 경로(`/tenants/{id}`)와 토큰 `groups` claim 에 그대로 실리므로 형식을 제한한다
 * (`IAM_PLATFORM_DECISION.md` §7.1). 슬래시·공백이 들어가면 group 경로가 깨지고, 대문자가 섞이면
 * 경로 비교에서 대소문자 문제가 생긴다.
 *
 * 게이트웨이가 `groups` claim 에서 [GROUP_PREFIX] 로 시작하는 항목을 파싱해 `X-Tenant-Id` 로 재주입할
 * 예정이므로(§7.3), **여기서의 형식 제약이 곧 게이트웨이 파싱의 전제**가 된다.
 */
@JvmInline
value class TenantId(
  val value: String,
) {
  init {
    require(value.matches(PATTERN)) {
      "테넌트 ID는 소문자·숫자·하이픈만 허용하며 2~64자여야 합니다: $value"
    }
  }

  /** Keycloak group 경로 표현. claim 에 실리는 형태다. */
  fun toGroupPath(): String = "$GROUP_PREFIX$value"

  companion object {
    private val PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,62}[a-z0-9]$")

    /** 게이트웨이의 테넌트 추출 로직과 반드시 일치해야 하는 접두사. */
    const val GROUP_PREFIX = "/tenants/"
  }
}
