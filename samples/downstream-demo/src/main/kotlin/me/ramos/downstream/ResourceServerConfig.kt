package me.ramos.downstream

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
 * Step 8: 다운스트림을 Resource Server 로 승격한다.
 *
 * 게이트웨이(BFF)가 세션·로그인·CSRF 를 전담하고, 다운스트림은 **Bearer JWT 만 신뢰하는
 * stateless 자원 서버**다. 여기서 하는 일은 단 하나 — 인입 토큰이 진짜인지 검증한다.
 *
 * ## 무엇을 검증하나
 * - 서명(realm JWKS 로컬 검증) · 만료(exp/nbf) · 발급자(iss)  ← Spring Security 기본
 * - audience(aud)  ← **기본에 없어서 직접 더한다** (jwtDecoder 참조)
 */
@Configuration
class ResourceServerConfig(
    @param:Value("\${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private val issuerUri: String,
    @param:Value("\${unigate.downstream.expected-audience}")
    private val expectedAudience: String,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            authorizeHttpRequests {
                // ── 예외 목록. 여기 없는 모든 경로는 테넌트 검증을 받는다 ──────────────
                //
                // 순서가 곧 정책이다. 예외는 **위에 명시적으로** 적고, 마지막 줄이 기본값이다.
                // 새 엔드포인트를 만들면 아무것도 안 해도 마지막 줄에 걸린다 — 그게 요점이다.

                // 검증용 origin 확보 전용 (PublicPingController KDoc 참조). 자원이 아니다.
                authorize("/public/**", permitAll)

                // 진단용. 테넌트와 무관하게 "무엇이 도착했는가" 를 봐야 하므로 예외로 둔다.
                // ⚠️ 이 한 줄이 곧 "여기는 테넌트 격리가 없다" 는 선언이다 — P9g 에서 위조
                //    헤더가 그대로 통과한 바로 그 경로다. 예외는 이렇게 눈에 보여야 한다.
                authorize("/echo", authenticated)

                // 기본값: 인증 + **X-Tenant-Id 가 토큰의 소속과 일치**해야 한다.
                authorize(anyRequest, TenantContextAuthorizationManager())
            }
            oauth2ResourceServer {
                jwt { }
            }
            // stateless: 세션을 만들지 않는다. 매 요청은 Bearer 토큰만으로 인증된다.
            // BFF 가 세션·CSRF 를 전담하므로 다운스트림엔 둘 다 불필요하다.
            csrf { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
        }
        return http.build()
    }

    /**
     * JwtDecoder 를 직접 구성해 audience 검증을 명시적으로 끼운다.
     *
     * Spring Security 기본 검증(`createDefaultWithIssuer`)은 **서명·만료·iss** 만 본다.
     * **aud 는 보지 않는다.** 그래서 같은 realm 이 발급한 다른 대상(예: Keycloak `account`)용
     * 토큰도 서명·iss 만 맞으면 통과한다 — 우리를 향한 토큰이 아닌데도. 이건 인증 우회의 문이다.
     * AudienceValidator 를 더해 "이 토큰이 나(`$expectedAudience`)를 향한 것인가"를 확인한다.
     *
     * `fromIssuerLocation` 은 기동 시 issuer 의 discovery/JWKS 를 1회 조회하므로
     * 부팅 시점에 Keycloak 접근이 가능해야 한다.
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
