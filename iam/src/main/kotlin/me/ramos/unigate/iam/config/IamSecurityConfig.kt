package me.ramos.unigate.iam.config

import me.ramos.unigate.iam.adapter.keycloakAdminOut.KeycloakAdminProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler

/**
 * IAM 인증 정책 — **Resource Server** (Phase 8f).
 *
 * ## 게이트웨이와 역할이 정반대다
 * 게이트웨이는 OAuth2 **Client**(BFF)라 로그인을 시키고 세션에 토큰을 보관한다. IAM 은 **Resource
 * Server** 라 로그인을 시키지 않고, GW 가 relay 한 `Authorization: Bearer <access token>` 을
 * **검증만** 한다. 그래서 리다이렉트도 세션도 쿠키도 없다.
 *
 * | | gateway | iam (여기) |
 * |---|---|---|
 * | 역할 | OAuth2 Client (BFF) | Resource Server |
 * | 미인증 응답 | 302 또는 401 problem+json | **401 + `WWW-Authenticate: Bearer`** |
 * | 세션 | Valkey(Spring Session) | **없음(STATELESS)** |
 * | CSRF | 활성(쿠키 인증이라 필요) | **비활성(쿠키를 안 쓴다)** |
 *
 * ## 왜 GW 가 이미 막는데 IAM 도 막나 (중복이 아니다)
 * GW 라우트가 `/iam` 하위 전체를 authenticated 로 잡지만, 그것은 **네트워크 경로 하나**를 지키는 것이다.
 * IAM 은 클러스터 안에서 `:8090` 으로 직접 도달 가능하고(사이드카·잘못된 Service·디버그 포트포워드),
 * 그때 GW 의 보호는 전혀 적용되지 않는다. 여기서 다시 막지 않으면 **GW 를 우회하는 순간 전부 공개**다.
 *
 * ## 토큰이 두 종류라는 것 (헷갈리면 설계가 무너진다)
 * 여기서 검증하는 것은 **호출자(사용자) 토큰**이다. Keycloak Admin 을 부를 권한은 이 토큰에 없다 —
 * 그건 IAM 자신의 service account 토큰이 따로 담당한다([KeycloakAdminProperties] KDoc 참조).
 */
@Configuration
@EnableWebSecurity
class IamSecurityConfig(
  private val environment: Environment,
) {
  @Bean
  fun iamSecurityFilterChain(
    http: HttpSecurity,
    jwtDecoder: JwtDecoder,
    authenticationEntryPoint: AuthenticationEntryPoint,
    accessDeniedHandler: AccessDeniedHandler,
  ): SecurityFilterChain =
    http
      // 쿠키·세션을 쓰지 않으므로 CSRF 공격 표면이 없다. Bearer 토큰은 브라우저가 자동으로
      // 실어 보내지 않으므로(= 교차 사이트 요청에 딸려오지 않으므로) CSRF 의 전제가 성립하지 않는다.
      // 반대로 게이트웨이는 세션 쿠키를 쓰기 때문에 CSRF 를 **켜둔다**.
      .csrf { it.disable() }
      // 요청마다 토큰으로 판단한다. 세션을 만들면 Bearer 인증인데 상태가 생겨 수평 확장이 깨지고,
      // "토큰은 만료됐는데 세션은 살아 있는" 어긋난 상태가 만들어진다.
      .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
      .authorizeHttpRequests { authorize ->
        authorize.requestMatchers(*PUBLIC_PATHS).permitAll()

        // 게이트웨이와 같은 규칙(`SecurityConfig`): local 프로브는 해당 프로파일에서만 연다.
        // `@Profile("local")` 로 컨트롤러 자체가 없더라도, permitAll 목록에 상시 두면
        // 나중에 non-local 용 /debug 가 생기는 순간 무인증으로 열린다.
        if (environment.activeProfiles.contains(LOCAL_PROFILE)) {
          authorize.requestMatchers(*LOCAL_ONLY_PUBLIC_PATHS).permitAll()
        }

        // 나머지는 전부 인증 필요 — **deny by default**. 새 엔드포인트를 추가하고 여기를 잊으면
        // 401 이 날 뿐, 조용히 공개되지는 않는다.
        authorize.anyRequest().authenticated()
      }.oauth2ResourceServer { oauth2 ->
        oauth2.jwt { jwt -> jwt.decoder(jwtDecoder) }
        // ⚠️ Phase 8e: 여기와 아래 exceptionHandling **둘 다** 지정해야 한다.
        // `oauth2ResourceServer` 는 자기 필터(BearerTokenAuthenticationFilter)가 인증에 실패했을 때
        // **자체 entryPoint** 를 쓴다. 그래서 exceptionHandling 만 바꾸면 "토큰이 잘못된" 401 은
        // 여전히 기본 형식(빈 본문)으로 나가고, "토큰이 아예 없는" 401 만 형식이 바뀐다.
        // 두 경로의 응답 형식이 갈리는 이 함정은 응답을 직접 비교해보기 전엔 눈치채기 어렵다.
        oauth2.authenticationEntryPoint(authenticationEntryPoint)
        oauth2.accessDeniedHandler(accessDeniedHandler)
      }.exceptionHandling { exceptions ->
        // 보안 필터 체인의 나머지 구간(토큰이 아예 없는 요청 등)이 쓰는 경로.
        exceptions.authenticationEntryPoint(authenticationEntryPoint)
        exceptions.accessDeniedHandler(accessDeniedHandler)
      }.build()

  /**
   * relay 된 사용자 JWT 검증기.
   *
   * ## `jwk-set-uri` 를 쓰는 이유 (부팅 결합 회피)
   * `JwtDecoders.fromIssuerLocation(issuer)` 은 **부팅 시** discovery 를 HTTP 로 조회한다 →
   * Keycloak 이 안 떠 있으면 IAM 이 기동조차 못 한다. 가입은 Keycloak 이 필요하지만, 그렇다고
   * **IAM 전체의 기동**을 Keycloak 가용성에 묶을 이유는 없다(프로필 조회 등은 IAM DB 만 쓴다).
   * `withJwkSetUri` 는 지연 로딩이라 첫 검증 시점에야 JWKS 를 가져온다.
   * 게이트웨이 `KeycloakJwtDecoderConfig` 와 같은 판단이다.
   *
   * ## 검증 항목
   * - 서명(JWKS, `kid` 회전 시 자동 재조회) — [NimbusJwtDecoder] 담당
   * - `exp`/`nbf`, `iss` — [JwtValidators.createDefaultWithIssuer]
   * - `aud` — 아래 [audienceValidator]. **이게 빠지면 다른 서비스용 토큰도 통과한다.**
   */
  @Bean
  fun jwtDecoder(
    keycloakProperties: KeycloakAdminProperties,
    @Value("\${unigate.iam.security.expected-audience}") expectedAudience: String,
  ): JwtDecoder =
    NimbusJwtDecoder
      .withJwkSetUri(keycloakProperties.jwkSetUri())
      .build()
      .apply {
        setJwtValidator(
          DelegatingOAuth2TokenValidator(
            JwtValidators.createDefaultWithIssuer(keycloakProperties.issuerUrl()),
            audienceValidator(expectedAudience),
          ),
        )
      }

  /**
   * `aud` 에 IAM 이 포함돼 있는지 확인한다.
   *
   * ## 왜 서명·만료·발급자만으로 부족한가
   * 같은 realm 이 발급한 토큰은 **전부** 서명·`iss` 검증을 통과한다. 즉 다운스트림 제품 API 용으로
   * 발급된 토큰을 그대로 IAM 에 들이밀어도 막히지 않는다. 침해된(또는 그냥 호기심 많은) 다운스트림이
   * 자기가 받은 사용자 토큰으로 IAM 관리 API 를 호출하는 것이 **토큰 재사용(token reuse)** 이고,
   * `aud` 는 "이 토큰은 나에게 온 것인가"를 묻는 유일한 클레임이다.
   *
   * ⚠️ 이 검증이 동작하려면 Keycloak 이 `aud` 에 IAM 을 넣어줘야 한다. realm 의 audience mapper
   * (`iam-audience`)가 그 일을 한다 — `docs/KEYCLOAK_REALM_SETUP.md` §4.8. 매퍼 없이 이 코드만 켜면
   * **모든 인증 요청이 401** 이 되고, 원인은 토큰을 디코드해 `aud` 를 눈으로 봐야 알 수 있다.
   *
   * ## `internal` 인 이유
   * 이 검증기는 **가장 조용히 깨지는 보안 통제**다. 슬라이스 테스트가 쓰는 `jwt()` post-processor 는
   * 디코더를 아예 호출하지 않아 여기를 지나가지 않는다 — 즉 이 코드를 통째로 지워도 인가 경계
   * 테스트는 전부 통과한다. 그래서 단위 테스트로 직접 겨냥한다(`JwtAudienceValidationTest`).
   * private 로 두면 그 테스트를 쓸 수 없다.
   */
  internal fun audienceValidator(expectedAudience: String): OAuth2TokenValidator<Jwt> =
    JwtClaimValidator<List<String>?>(JwtClaimNames.AUD) { audience ->
      audience != null && expectedAudience in audience
    }

  companion object {
    private const val LOCAL_PROFILE = "local"

    /**
     * 인증 없이 열어두는 경로.
     *
     * - `/iam/register` — 가입은 사용자 토큰이 없는 상태다(D4 보강). 남용 방어는 게이트웨이의
     *   rate limit 이 담당한다. **경로를 `/iam` 하위 전체로 넓히면 관리 API 까지 공개된다.**
     * - actuator health/info — k8s probe 는 인증 정보를 실을 수 없다.
     *   `prometheus` 는 게이트웨이와 같은 이유로 **의도적으로 제외**한다(정찰 정보).
     */
    private val PUBLIC_PATHS =
      arrayOf(
        "/iam/register",
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
      )

    /** local 프로파일에서만 열리는 검증용 프로브 (`ThreadProbeController`). */
    private val LOCAL_ONLY_PUBLIC_PATHS = arrayOf("/debug/**")
  }
}
