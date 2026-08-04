package me.ramos.billing

import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt

/**
 * access token 의 `aud` 에 이 서비스의 audience 가 포함되는지 검증한다.
 *
 * `downstream-demo` 의 같은 이름 클래스와 **코드가 사실상 동일하다.** 그게 중요하다 —
 * 두 번째 소비자를 실제로 만들어 보고 나서야 "무엇이 진짜 공통인가" 가 갈리기 때문이다
 * (`paas-iam-scope-review.md` §11 — `downstream-starter` 착수 조건이 **소비자 2대**).
 *
 * ## 이 클래스가 **막지 못하는 것** (이 샘플의 존재 이유)
 * aud 검증은 "이 토큰이 나를 향한 것인가" 만 본다. 그런데 Keycloak 이 두 audience 를
 * **한 토큰에 함께** 실으면(`setup-realm.sh` 가 두 mapper 를 모두 GW client 의 dedicated scope 에
 * 붙인다) 그 토큰은 demo 에도 billing 에도 유효하다.
 *
 * → **A 가 받은 Bearer 를 그대로 B 에 재생하면 통과한다.** 각 서비스의 검증은 자기 몫을 다 했는데도.
 * 이것이 `paas-iam-scope-review.md` §6.2 (a) "하나가 뚫리면 전부" 의 실체다.
 * 막으려면 여기가 아니라 **토큰 발급 쪽**을 바꿔야 한다((b) token exchange / (c) GW re-mint).
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
