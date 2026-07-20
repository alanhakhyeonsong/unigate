package me.ramos.unigate.application.auth.port.outbound

import me.ramos.unigate.domain.auth.model.AuthenticatedPrincipal

/**
 * 토큰 검증 아웃바운드 포트.
 *
 * OIDC 표준(JWKS 로컬 캐싱 + 서명 검증)에만 의존하며,
 * Keycloak 등 구체 IdP 는 어댑터(adapter/keycloakOut)에 봉인한다.
 * 어댑터 교체(Okta, 자체 IdP)만으로 인증 공급원을 바꿀 수 있어야 한다.
 */
interface TokenVerifierPort {
  /**
   * 서명·만료·audience 를 검증하고 검증된 주체를 반환한다.
   *
   * @throws me.ramos.unigate.application.auth.exception.TokenVerificationException 검증 실패 시
   */
  suspend fun verify(rawToken: String): AuthenticatedPrincipal
}
