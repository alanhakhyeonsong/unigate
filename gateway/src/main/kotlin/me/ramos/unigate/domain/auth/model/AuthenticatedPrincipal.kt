package me.ramos.unigate.domain.auth.model

/**
 * 검증된 인증 주체. 다운스트림 표준 principal 계약의 도메인 표현.
 *
 * 순수 도메인 모델 — Spring/외부 의존성 없음.
 */
data class AuthenticatedPrincipal(
  val subject: String,
  val email: String?,
  val groups: List<String>,
  val audiences: List<String>,
)
