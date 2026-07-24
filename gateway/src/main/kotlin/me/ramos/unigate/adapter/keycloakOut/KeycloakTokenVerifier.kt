package me.ramos.unigate.adapter.keycloakOut

import kotlinx.coroutines.reactor.awaitSingle
import me.ramos.unigate.application.auth.exception.TokenVerificationException
import me.ramos.unigate.application.auth.port.outbound.TokenVerifierPort
import me.ramos.unigate.domain.auth.model.AuthenticatedPrincipal
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtValidationException
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.stereotype.Component

/**
 * [TokenVerifierPort] 의 Keycloak 구현 — raw JWT 를 로컬 JWKS 로 검증해 도메인 주체로 변환한다.
 *
 * 이 어댑터가 Keycloak 고유 지식(realm_access.roles 위치)을 봉인하고, 밖으로는 IdP 중립적인
 * [AuthenticatedPrincipal] 만 노출한다. IdP 를 Okta·자체 IdP 로 바꿔도 이 어댑터만 갈아끼우면 된다.
 *
 * 검증 자체(서명·JWKS 캐싱·kid 회전·클레임)는 주입된 [ReactiveJwtDecoder] 가 수행한다
 * (`KeycloakJwtDecoderConfig`). 이 클래스의 책임은 두 가지다:
 * 1. 예외를 [TokenVerificationException] + 구분된 원인 코드([TokenVerificationReason])로 번역
 * 2. 검증된 [Jwt] 를 [AuthenticatedPrincipal] 로 매핑
 */
@Component
class KeycloakTokenVerifier(
  private val jwtDecoder: ReactiveJwtDecoder,
) : TokenVerifierPort {
  override suspend fun verify(rawToken: String): AuthenticatedPrincipal {
    val jwt =
      try {
        jwtDecoder.decode(rawToken).awaitSingle()
      } catch (e: JwtValidationException) {
        // 서명은 통과했으나 클레임 검증(exp/iss/aud) 실패. 커스텀 검증기가 실은 코드를 그대로 쓴다.
        val error = e.errors.firstOrNull()
        throw TokenVerificationException(
          reasonCode = error?.errorCode ?: TokenVerificationReason.INVALID_TOKEN,
          message = error?.description ?: "토큰 검증에 실패했습니다",
          cause = e,
        )
      } catch (e: BadJwtException) {
        // 디코딩·서명 단계 실패. reactive 디코더는 서명 실패를 "Failed to validate the token" 으로,
        // 만료(JOSE 레벨 처리 시)를 "expired" 로 알린다 — servlet 스택과 문구가 다르다(실측 반영).
        throw TokenVerificationException(reasonCodeOf(e.message), e.message ?: "토큰 디코딩에 실패했습니다", e)
      } catch (e: JwtException) {
        // JWKS 조회 실패 등 그 밖의 검증 오류.
        throw TokenVerificationException(TokenVerificationReason.INVALID_TOKEN, e.message ?: "토큰 검증에 실패했습니다", e)
      }

    return jwt.toPrincipal()
  }

  private fun Jwt.toPrincipal(): AuthenticatedPrincipal {
    val subjectClaim =
      subject
        ?: throw TokenVerificationException(TokenVerificationReason.INVALID_TOKEN, "sub 클레임이 없습니다")
    return AuthenticatedPrincipal(
      subject = subjectClaim,
      email = getClaimAsString(CLAIM_EMAIL),
      groups = realmRoles(),
      audiences = audience ?: emptyList(),
    )
  }

  /**
   * Keycloak 고유: 역할은 `realm_access.roles` 에 담긴다. 도메인은 이 위치를 몰라야 하므로
   * 여기서 꺼내 IdP 중립적 `groups` 로 넘긴다. (다른 IdP 는 `groups` 클레임을 직접 줄 수도 있다.)
   */
  @Suppress("UNCHECKED_CAST")
  private fun Jwt.realmRoles(): List<String> {
    val realmAccess = getClaimAsMap(CLAIM_REALM_ACCESS) ?: return emptyList()
    return (realmAccess[CLAIM_ROLES] as? List<String>) ?: emptyList()
  }

  companion object {
    private const val CLAIM_EMAIL = "email"
    private const val CLAIM_REALM_ACCESS = "realm_access"
    private const val CLAIM_ROLES = "roles"

    /**
     * 디코더가 던진 BadJwtException 메시지를 원인 코드로 번역한다.
     *
     * reactive NimbusReactiveJwtDecoder 의 문구 기준(실측): 서명 실패 → "Failed to validate the token",
     * 만료 → "expired". 문구 매칭은 취약하므로 exp/iss/aud 는 가급적 커스텀 검증기(JwtValidationException)
     * 로 잡고, 여기 오는 건 서명/형식 오류가 대부분이다.
     */
    private fun reasonCodeOf(message: String?): String {
      val lower = message?.lowercase() ?: ""
      return when {
        lower.contains("expired") -> TokenVerificationReason.TOKEN_EXPIRED
        lower.contains("signature") || lower.contains("failed to validate the token") ->
          TokenVerificationReason.INVALID_SIGNATURE
        else -> TokenVerificationReason.MALFORMED_TOKEN
      }
    }
  }
}
