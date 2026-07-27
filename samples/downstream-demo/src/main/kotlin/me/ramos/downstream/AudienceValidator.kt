package me.ramos.downstream

import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt

/**
 * access token 의 aud 에 이 서비스의 audience 가 포함되는지 검증한다.
 *
 * Keycloak 은 unigate-client 의 dedicated scope 에 붙인 Audience Mapper 로
 * aud 에 `unigate-downstream-demo` 를 주입한다(docs/KEYCLOAK_REALM_SETUP.md §4.4).
 * mapper 가 빠지거나 다른 대상용 토큰이 들어오면 여기서 invalid_token 으로 걸러진다.
 */
class AudienceValidator(
    private val expectedAudience: String,
) : OAuth2TokenValidator<Jwt> {
    private val error =
        OAuth2Error(
            "invalid_token",
            "필수 audience '$expectedAudience' 가 토큰 aud 에 없습니다",
            null,
        )

    override fun validate(token: Jwt): OAuth2TokenValidatorResult =
        if (token.audience.contains(expectedAudience)) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(error)
        }
}
