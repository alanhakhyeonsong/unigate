package me.ramos.unigate.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.client.web.server.AuthenticatedPrincipalServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.logout.DelegatingServerLogoutHandler
import org.springframework.security.web.server.authentication.logout.SecurityContextServerLogoutHandler
import org.springframework.security.web.server.authentication.logout.ServerLogoutHandler
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler

/**
 * 게이트웨이 인증 정책 — Authorization Code Flow (BFF).
 *
 * MVC 의 `@EnableWebSecurity` + `SecurityFilterChain` 과 역할은 같지만 타입이 전부 reactive 다
 * (`ServerHttpSecurity` / `SecurityWebFilterChain`). 서블릿 필터가 아니라 `WebFilter` 체인이며
 * SCG 의 라우팅 핸들러보다 **앞에서** 동작한다. 따라서 인증 실패는 다운스트림에 닿기 전에 끝난다.
 *
 * BFF 이므로 토큰은 브라우저에 내려가지 않는다. 브라우저가 받는 것은 세션 쿠키뿐이고
 * access/refresh token 은 세션(=Valkey)에만 존재한다. 그 대가로 **세션 저장소가 인증 가용성**이 된다.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig(
  private val environment: Environment,
) {
  @Bean
  fun securityWebFilterChain(
    http: ServerHttpSecurity,
    authorizationRequestResolver: ServerOAuth2AuthorizationRequestResolver,
    clientRegistrationRepository: ReactiveClientRegistrationRepository,
    authorizedClientRepository: ServerOAuth2AuthorizedClientRepository,
  ): SecurityWebFilterChain =
    http
      .authorizeExchange { exchanges ->
        exchanges.pathMatchers(*PUBLIC_PATHS).permitAll()

        // local 프로브(`/debug/**`)는 @Profile("local") 이라 다른 프로파일에는 라우트 자체가 없다.
        // 그래도 permitAll 목록에 상시 두지 않는다 — 나중에 non-local 용 /debug 가 생기면
        // 그 순간 무인증으로 열리기 때문이다. 권한 완화는 필요한 프로파일에서만 켠다.
        if (environment.activeProfiles.contains(LOCAL_PROFILE)) {
          exchanges.pathMatchers(*LOCAL_ONLY_PUBLIC_PATHS).permitAll()
        }

        // 나머지는 전부 인증 필요. 미인증 요청은 302 로 Keycloak 인가 엔드포인트로 나간다.
        //
        // TODO(샘플 FE 연동 시): XHR(`fetch`)에는 302 대신 401 + 로그인 URL 을 돌려줘야 한다.
        //   fetch 가 302 를 그대로 따라가 Keycloak 로그인 페이지를 요청하면 CORS 에러로 둔갑해
        //   진짜 원인(미인증)이 가려진다. (CLAUDE.md §6.1)
        exchanges.anyExchange().authenticated()
      }.oauth2Login { oauth2 ->
        // PKCE 를 쓰기 위해 resolver 를 명시적으로 갈아끼운다. 이유는 아래 빈 주석 참조.
        oauth2.authorizationRequestResolver(authorizationRequestResolver)
      }.logout { logout ->
        // ── Phase 1.5: RP-Initiated Logout ──────────────────────────────
        // 기본 로그아웃 엔드포인트는 POST /logout (CSRF 보호됨).
        //
        // (1) logoutHandler — 게이트웨이 세션에서 **민감 내용을 폐기**한다:
        //   ① SecurityContext 제거(→ 미인증화)  ② OAuth2AuthorizedClient 제거(→ access/refresh
        //   token 폐기). 둘 다 세션 **업데이트**다(무효화 아님).
        //
        //   ⚠️ 왜 세션을 invalidate() 하지 않는가 (겪은 실패):
        //   Spring Session(reactive Redis)은 요청 종료 시 세션을 **자동 저장**한다. 그런데
        //   WebSessionServerLogoutHandler 로 세션을 invalidate() 하면, 그 자동 저장이
        //   "무효화된 세션을 save" 하려다 `IllegalStateException: Session was invalidated` 로 터진다
        //   (ReactiveRedisSessionRepository.save → POST /logout 이 500).
        //   그래서 무효화 대신 민감 attribute 만 제거해 충돌을 피한다. 남는 빈 세션은 TTL 로 만료된다.
        logout.logoutHandler(
          DelegatingServerLogoutHandler(
            SecurityContextServerLogoutHandler(),
            ServerLogoutHandler { exchange, authentication ->
              authorizedClientRepository.removeAuthorizedClient(
                KEYCLOAK_REGISTRATION_ID,
                authentication,
                exchange.exchange,
              )
            },
          ),
        )
        // (2) logoutSuccessHandler — Keycloak 쪽 세션도 끝낸다.
        //   게이트웨이 세션만 지우면 Keycloak SSO 쿠키(KEYCLOAK_IDENTITY)가 살아 있어,
        //   다음 보호 리소스 접근 시 authorization code 흐름이 **비밀번호 없이 자동 완료**된다.
        //   → 사용자 체감은 "로그아웃이 안 된" 상태. end_session_endpoint 로 리다이렉트해
        //   Keycloak 세션까지 종료해야 진짜 로그아웃이다. (OIDC RP-Initiated Logout)
        logout.logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository))
      }
      // CSRF 는 **기본값(활성)** 으로 되돌렸다. 이전 단계의 `.csrf { it.disable() }` 는
      // 인증이 없던 시기의 임시 조치였다. 세션 쿠키로 인증하는 순간 브라우저는 교차 사이트
      // 요청에도 쿠키를 자동으로 실어 보내므로 CSRF 가 실제 공격 표면이 된다.
      //
      // 지금 검증 대상(`GET /api/echo`, `GET /debug/**`)은 안전 메서드라 영향이 없다.
      // 상태 변경 요청(POST 등)을 다루는 시점 — 즉 샘플 FE 연동 때 — 토큰 전달 방식을 정한다.
      // TODO(샘플 FE 연동 시): SPA 는 세션에 담긴 CSRF 토큰을 읽을 수 없다.
      //   `CookieServerCsrfTokenRepository.withHttpOnlyFalse()` 로 쿠키 전달을 검토하되,
      //   WebFlux 에서는 `CsrfToken` 이 지연 평가돼 응답에 쿠키가 실리지 않는 함정이 있다
      //   (구독을 강제하는 WebFilter 가 별도로 필요하다).
      .build()

  /**
   * Authorization Request 에 PKCE(`code_challenge` / `code_challenge_method=S256`)를 붙인다.
   *
   * Spring Security 는 public client(`client_authentication_method=none`)에는 PKCE 를 자동 적용하지만
   * **confidential client 에는 적용하지 않는다.** unigate 는 client secret 을 가진 confidential client 이므로
   * 이 설정이 없으면 `code_challenge` 없이 인가 요청이 나간다.
   *
   * 실패 모드: Keycloak client 의 "Proof Key for Code Exchange Code Challenge Method" 가 `S256` 으로
   * 강제돼 있으면(현재 realm 구성이 그렇다 — `docs/KEYCLOAK_REALM_SETUP.md` §4.1)
   * 로그인 화면에 닿기도 전에 Keycloak 이 `invalid_request` 로 거절한다.
   * 컴파일·기동은 정상이고 **브라우저에서 처음 로그인할 때만** 드러난다.
   *
   * MVC 라면 `DefaultOAuth2AuthorizationRequestResolver` 를 쓰지만, reactive 는 `Server` 접두사가 붙은
   * [DefaultServerOAuth2AuthorizationRequestResolver] 다. 커스터마이저([OAuth2AuthorizationRequestCustomizers])는
   * 요청 빌더만 다루므로 양쪽이 공용이다.
   *
   * @see DefaultOAuth2AuthorizationRequestResolver MVC 대응물 (여기서는 쓰지 않는다)
   */
  @Bean
  fun authorizationRequestResolver(
    clientRegistrationRepository: ReactiveClientRegistrationRepository,
  ): ServerOAuth2AuthorizationRequestResolver =
    DefaultServerOAuth2AuthorizationRequestResolver(clientRegistrationRepository).apply {
      setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
    }

  /**
   * RP-Initiated Logout 성공 처리기.
   *
   * 로그아웃 후 사용자를 Keycloak 의 `end_session_endpoint` 로 리다이렉트한다. 이때
   * `id_token_hint`(어느 세션을 끝낼지)와 `post_logout_redirect_uri`(끝난 뒤 돌아올 곳)를 붙인다.
   *
   * - `end_session_endpoint` 는 **discovery 메타데이터**에서 온다(issuer-uri 로 부팅 시 조회).
   *   따라서 이 핸들러가 실제로 Keycloak 으로 보내려면 discovery 가 살아 있어야 한다. discovery 없이
   *   정적 엔드포인트만 준 환경(테스트 프로파일)에서는 메타데이터가 없어 Keycloak 왕복 없이
   *   곧장 post-logout URI 로만 리다이렉트한다. (그래서 RP 왕복은 브라우저 e2e 로 검증한다.)
   * - `{baseUrl}` 은 게이트웨이 자신의 베이스 URL(`http://localhost:8080`)로 치환된다. 뒤에 `/` 를
   *   붙여 Keycloak client 에 등록된 post-logout redirect URI(`http://localhost:8080/`)와 정확히 맞춘다.
   *   불일치하면 Keycloak 이 `invalid_redirect_uri` 로 거절한다(KEYCLOAK_REALM_SETUP.md §4.1).
   */
  private fun oidcLogoutSuccessHandler(
    clientRegistrationRepository: ReactiveClientRegistrationRepository,
  ): ServerLogoutSuccessHandler =
    OidcClientInitiatedServerLogoutSuccessHandler(clientRegistrationRepository).apply {
      setPostLogoutRedirectUri("{baseUrl}/")
    }

  /**
   * 발급받은 토큰(access/refresh)을 **세션(= Valkey)** 에 저장한다.
   *
   * 이 빈이 없으면 Spring Security 는 [AuthenticatedPrincipalServerOAuth2AuthorizedClientRepository] 를
   * 기본값으로 쓰고, 그것은 인증된 사용자의 토큰을 [InMemoryReactiveOAuth2AuthorizedClientService]
   * — 즉 **JVM 힙** — 에 넣는다. Spring Session 을 붙여도 토큰만 인메모리에 남는다.
   *
   * 실패 모드가 특히 고약하다. 인증 정보(`SPRING_SECURITY_CONTEXT`)는 Valkey 에 있으므로
   * 재시작·다른 인스턴스에서도 **로그인 상태는 유지된다.** 그런데 토큰만 없다.
   * 결과적으로 "로그인은 되어 있는데 다운스트림 호출만 실패하는" 상태가 되고,
   * 사용자에게는 재로그인 유도조차 뜨지 않는다. 실측 기록은
   * `docs/learning/04-oauth2-authorization-code-bff.md` §4 참조.
   *
   * - 재시작: 토큰 소실 → TokenRelay 불가
   * - replica 2개 이상: 로그인한 인스턴스가 아닌 곳으로 라우팅되면 토큰 없음
   *
   * 대가: 세션 payload 에 토큰이 들어가 크기가 커지고, JDK 직렬화에 의존하게 된다.
   * Spring Security 클래스의 `serialVersionUID` 가 바뀌는 버전 업그레이드 시
   * 기존 세션이 역직렬화에 실패할 수 있다(롤링 배포 중 간헐적 로그인 풀림).
   */
  @Bean
  fun authorizedClientRepository(): ServerOAuth2AuthorizedClientRepository =
    WebSessionServerOAuth2AuthorizedClientRepository()

  companion object {
    private const val LOCAL_PROFILE = "local"

    /** `application-*.yml` 의 `spring.security.oauth2.client.registration.<이 이름>` 과 일치해야 한다. */
    private const val KEYCLOAK_REGISTRATION_ID = "keycloak"

    /**
     * 인증 없이 열어두는 경로.
     *
     * k8s probe 는 인증 정보를 실을 수 없으므로 health 는 반드시 열려야 한다.
     * 반면 `/actuator/prometheus` 는 **의도적으로 제외**했다. 지표에는 경로·상태코드 분포가
     * 담겨 공격자에게 유용한 정찰 정보가 된다. 스크래핑 경로는 Phase 4 에서
     * management port 분리 또는 네트워크 정책으로 따로 다룬다.
     */
    private val PUBLIC_PATHS =
      arrayOf(
        "/actuator/health",
        "/actuator/health/**",
        "/actuator/info",
        // CB fallback — 인증된 /api 요청이 forward 로 도달하지만, 직접 접근해도 503 만 반환하므로
        // 공개로 둔다(민감정보 없음). 인증 필요로 두면 forward 시 재인증에 걸릴 수 있다.
        "/fallback/**",
      )

    /** local 프로파일에서만 열리는 검증용 프로브 (`SessionProbeConfig`, `AuthProbeConfig`). */
    private val LOCAL_ONLY_PUBLIC_PATHS = arrayOf("/debug/**")
  }
}
