package me.ramos.unigate.adapter.keycloakOut

import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

/*
 * JWT 클레임 검증기 — exp/nbf · iss · aud 를 각각 **구분된 원인 코드**로 검증한다.
 *
 * ## 왜 직접 만드나
 * Spring 의 기본 검증기(`JwtValidators.createDefaultWithIssuer`)도 exp·iss 를 보지만, 실패 시
 * `OAuth2Error.errorCode` 가 전부 `invalid_token` 이라 "무엇이 틀렸는지" 코드로 구분되지 않는다.
 * 여기서는 실패마다 [TokenVerificationReason] 의 코드를 실어, 검증기가 곧 원인 분류기가 되게 한다.
 *
 * ## 무엇을 여기서 안 하나
 * **서명 검증은 하지 않는다.** 서명은 디코더(NimbusReactiveJwtDecoder)가 JWKS 공개키로 이미
 * 검증하며, 실패 시 디코딩 단계에서 예외가 난다. 검증기는 서명이 통과한 뒤의 **클레임**만 본다.
 */

// 허용 클럭 스큐(초). 게이트웨이와 Keycloak 의 시계 오차를 흡수한다.
private const val CLOCK_SKEW_SECONDS = 30L

private fun fail(
  code: String,
  description: String,
): OAuth2TokenValidatorResult = OAuth2TokenValidatorResult.failure(OAuth2Error(code, description, null))

/** exp 만료·nbf 미도래 검증. 클럭 스큐 30초를 허용한다. */
class JwtExpiryValidator(
  private val now: () -> Instant = Instant::now,
) : OAuth2TokenValidator<Jwt> {
  override fun validate(token: Jwt): OAuth2TokenValidatorResult {
    val current = now()
    val expiresAt = token.expiresAt
    if (expiresAt != null && expiresAt.isBefore(current.minusSeconds(CLOCK_SKEW_SECONDS))) {
      return fail(TokenVerificationReason.TOKEN_EXPIRED, "토큰이 만료되었습니다 (exp=$expiresAt)")
    }
    val notBefore = token.notBefore
    if (notBefore != null && notBefore.isAfter(current.plusSeconds(CLOCK_SKEW_SECONDS))) {
      return fail(TokenVerificationReason.TOKEN_EXPIRED, "토큰이 아직 유효하지 않습니다 (nbf=$notBefore)")
    }
    return OAuth2TokenValidatorResult.success()
  }
}

/** iss 가 신뢰하는 발급자와 정확히 일치하는지 검증한다. */
class JwtIssuerValidator(
  private val expectedIssuer: String,
) : OAuth2TokenValidator<Jwt> {
  override fun validate(token: Jwt): OAuth2TokenValidatorResult =
    if (token.issuer?.toString() == expectedIssuer) {
      OAuth2TokenValidatorResult.success()
    } else {
      fail(TokenVerificationReason.INVALID_ISSUER, "iss 불일치: ${token.issuer}")
    }
}

/** aud 에 우리 audience 가 포함되는지 검증한다(Step 8 다운스트림과 같은 방어선). */
class JwtAudienceValidator(
  private val expectedAudience: String,
) : OAuth2TokenValidator<Jwt> {
  override fun validate(token: Jwt): OAuth2TokenValidatorResult =
    if (token.audience.contains(expectedAudience)) {
      OAuth2TokenValidatorResult.success()
    } else {
      fail(TokenVerificationReason.INVALID_AUDIENCE, "필수 audience '$expectedAudience' 가 aud 에 없습니다")
    }
}
