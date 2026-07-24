package me.ramos.unigate.adapter.keycloakOut

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder

/**
 * 토큰 검증용 [ReactiveJwtDecoder] 를 구성한다 — **JWKS 로컬 캐싱 + 서명검증**.
 *
 * ## introspection 이 아니라 왜 JWKS 인가
 * introspection 은 매 요청마다 Keycloak `/introspect` 를 호출한다 → 요청 경로에 외부 왕복이 얹혀
 * 지연·결합·장애전파가 생긴다. JWKS 방식은 **공개키를 한 번 받아 캐시**하고, 이후엔 로컬에서
 * 서명만 검증한다. Keycloak 이 잠시 죽어도 캐시된 키로 검증이 계속된다(가용성 격리).
 *
 * ## `withJwkSetUri` 를 쓰는 이유(부팅 결합 회피)
 * `ReactiveJwtDecoders.fromIssuerLocation(issuer)` 은 **부팅 시** discovery 를 HTTP 로 조회한다 →
 * Keycloak 이 없으면 기동 실패. 반면 `withJwkSetUri` 는 **지연 로딩**이라 첫 검증 시점에야 JWKS 를
 * 가져온다. 소비자가 아직 없는(Phase 2) 이 빌딩블록엔 지연 로딩이 맞다 — 부팅을 Keycloak 에 묶지 않는다.
 *
 * JWKS 경로(`/protocol/openid-connect/certs`)는 Keycloak 규약이라 **어댑터(keycloakOut) 안에 봉인**한다.
 */
@Configuration
class KeycloakJwtDecoderConfig {
  @Bean
  fun tokenVerifierJwtDecoder(
    @Value("\${unigate.keycloak.issuer-uri}") issuerUri: String,
    @Value("\${unigate.keycloak.expected-audience}") expectedAudience: String,
  ): ReactiveJwtDecoder = buildJwkSetDecoder(jwkSetUri(issuerUri), issuerUri, expectedAudience)

  /** Keycloak 규약 JWKS 엔드포인트. 이 조립 규칙이 어댑터가 봉인하는 "Keycloak 고유" 지식이다. */
  private fun jwkSetUri(issuerUri: String): String = "$issuerUri/protocol/openid-connect/certs"
}

/**
 * 지정한 JWKS 엔드포인트를 바라보는 디코더를 만든다(운영 구성과 테스트가 공유).
 *
 * 디코더 자신은 **서명 + JWKS 캐싱 + kid 회전 재조회**를 담당하고(NimbusReactiveJwtDecoder/RemoteJWKSet),
 * 클레임 검증(exp/iss/aud)은 [JwtExpiryValidator]/[JwtIssuerValidator]/[JwtAudienceValidator] 로 얹는다.
 */
fun buildJwkSetDecoder(
  jwkSetUri: String,
  issuerUri: String,
  expectedAudience: String,
): NimbusReactiveJwtDecoder =
  NimbusReactiveJwtDecoder
    .withJwkSetUri(jwkSetUri)
    .build()
    .apply {
      setJwtValidator(
        DelegatingOAuth2TokenValidator(
          JwtExpiryValidator(),
          JwtIssuerValidator(issuerUri),
          JwtAudienceValidator(expectedAudience),
        ),
      )
    }
