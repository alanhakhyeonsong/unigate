package me.ramos.billing

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoders
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain

/**
 * 제품 B 의 Resource Server 설정.
 *
 * ## ⚠️ downstream-demo 와 **일부러 다르다**
 * demo 는 `anyRequest` 에 `TenantContextAuthorizationManager` 를 걸어 **문 앞에서** 테넌트를
 * 대조한다(잊으면 닫히는 기본값 — `docs/learning/24`). 여기는 `authenticated` 까지만 건다.
 *
 * 왜 일부러 약하게 두는가: `paas-iam-scope-review.md` §5.5 는 **ABAC 판정을 제품 BE 가 관장**한다고
 * 결정했다. 그 결정 자체는 옳지만, 제품이 그 판정을 **토큰의 소속 목록**으로 하면 §5.5.4 의 구멍이
 * 열린다. 그 구멍은 문 앞 게이트가 아니라 **자원을 아는 자리**에서만 생기므로, 문 앞을 닫아 버리면
 * 재현 자체가 안 된다.
 *
 * 즉 이 앱은 **"제품이 자기 ABAC 을 틀리게 구현했을 때"** 를 재현하는 장치다.
 * 두 판정 방식을 나란히 두어(`SubscriptionController` ↔ `ScopedSubscriptionController`)
 * 같은 요청이 한쪽은 통과하고 한쪽은 403 이 되는 것을 눈으로 본다.
 *
 * **레퍼런스로 복사하지 말 것.** 제품 코드라면 demo 쪽 배치가 맞다.
 */
@Configuration
class ResourceServerConfig(
    @param:Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private val issuerUri: String,
    @param:Value("\${unigate.billing.expected-audience}")
    private val expectedAudience: String,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                // 브라우저에서 이 origin 을 얻기 위한 검증 도구. 자원이 아니다(demo 와 같은 이유).
                authorize("/public/**", permitAll)

                // probe 용. health 만 연다 — `/actuator/**` 를 통째로 열면 env·metrics 가 따라 열린다.
                authorize("/actuator/health", permitAll)
                authorize("/actuator/health/**", permitAll)

                // ⚠️ 여기가 demo 와 갈리는 한 줄이다. 테넌트 대조를 **문 앞에서 하지 않는다.**
                // 판정 책임이 각 컨트롤러로 내려가고, 그래서 컨트롤러마다 틀릴 수 있다.
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer {
                jwt { }
            }
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
        }
        return http.build()
    }

    /**
     * 기본 검증(서명·만료·iss)에 audience 검증을 더한다. [AudienceValidator] KDoc 참조 —
     * **이 검증을 통과한다고 해서 "나만을 향한 토큰" 이라는 뜻은 아니다.**
     *
     * `fromIssuerLocation` 은 기동 시 discovery/JWKS 를 1회 조회하므로 Keycloak 이 떠 있어야
     * 기동한다(demo 와 같은 특성 — `samples/README.md` §4 경고).
     */
    @Bean
    fun jwtDecoder(): JwtDecoder {
        val decoder = JwtDecoders.fromIssuerLocation(issuerUri) as NimbusJwtDecoder
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                AudienceValidator(expectedAudience),
            ),
        )
        return decoder
    }
}
