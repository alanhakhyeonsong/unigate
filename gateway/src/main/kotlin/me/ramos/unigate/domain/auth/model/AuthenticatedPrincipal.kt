package me.ramos.unigate.domain.auth.model

/**
 * 검증된 인증 주체. 다운스트림 표준 principal 계약의 도메인 표현.
 *
 * 순수 도메인 모델 — Spring/외부 의존성 없음.
 */
data class AuthenticatedPrincipal(
  val subject: String,
  val email: String?,
  /**
   * IdP 중립적 **역할** 목록.
   *
   * ⚠️ 예전 이름은 `groups` 였다(Phase 9f 에서 리네임). Keycloak 이 역할을 `realm_access.roles` 에
   * 담는 것을 감추려는 의도였는데, **진짜 `groups` claim**(테넌트 경로)이 생기면서
   * "groups 인데 groups 가 아닌" 상태가 됐다. 이름이 실제와 어긋나면 읽는 사람이 매번 확인해야 한다.
   */
  val roles: List<String>,
  /**
   * 소속 **테넌트 id** 목록 (Phase 9f).
   *
   * Keycloak 은 이를 group 경로(`/tenants/{id}`)로 표현하지만, 그 형식은 IdP 사정이다.
   * 여기까지 올라올 때는 접두사를 벗긴 **id 만** 담는다 — 도메인이 "테넌트를 group 으로 표현한다" 는
   * 구현 선택을 알 필요가 없다.
   */
  val tenants: List<String>,
  val audiences: List<String>,
)
