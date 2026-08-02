package me.ramos.unigate.config

import me.ramos.unigate.adapter.gatewayIn.CsrfTokenCookieFilter
import me.ramos.unigate.adapter.gatewayIn.CsrfTokenEndpoint
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
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
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler
import org.springframework.security.web.server.authentication.logout.DelegatingServerLogoutHandler
import org.springframework.security.web.server.authentication.logout.SecurityContextServerLogoutHandler
import org.springframework.security.web.server.authentication.logout.ServerLogoutHandler
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository
import org.springframework.security.web.server.csrf.CsrfWebFilter
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
import org.springframework.web.cors.reactive.CorsConfigurationSource
import java.net.URI

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
  /**
   * FE 가 게이트웨이와 **다른 호스트**일 때의 FE 베이스 URI. 비어 있으면 same-origin 배포로 본다.
   *
   * 로그인 착지(`AuditingAuthenticationSuccessHandler`)와 로그아웃 착지(아래)가 **같은 값**을 봐야 한다.
   * 한쪽만 FE 를 가리키면 "로그인하면 FE, 로그아웃하면 게이트웨이 404" 처럼 절반만 어긋난다.
   */
  @Value("\${unigate.frontend.base-uri:}") private val frontendBaseUri: String,
) {
  @Bean
  fun securityWebFilterChain(
    http: ServerHttpSecurity,
    authorizationRequestResolver: ServerOAuth2AuthorizationRequestResolver,
    clientRegistrationRepository: ReactiveClientRegistrationRepository,
    authorizedClientRepository: ServerOAuth2AuthorizedClientRepository,
    authenticationSuccessHandler: ServerAuthenticationSuccessHandler,
    authenticationFailureHandler: ServerAuthenticationFailureHandler,
    auditingLogoutHandler: ServerLogoutHandler,
    authenticationEntryPoint: ServerAuthenticationEntryPoint,
    accessDeniedHandler: ServerAccessDeniedHandler,
    corsConfigurationSource: CorsConfigurationSource,
  ): SecurityWebFilterChain =
    http
      // ── CORS ────────────────────────────────────────────────────────────
      // FE 를 다른 호스트에 두는 배포에서만 실제로 동작한다(허용 origin 이 비면 무효과).
      // ⚠️ 보안 체인에 **연결**해야 한다 — 별도 CorsWebFilter 빈만 두면 인증 필터가 먼저 돌아
      // preflight(OPTIONS, 자격증명 없음)가 401 로 끊기고, 브라우저 콘솔에는 CORS 에러만 뜬다.
      .cors { cors -> cors.configurationSource(corsConfigurationSource) }
      // ── Phase 4: 인증·인가 실패를 RFC 9457 Problem Detail 로 ──────────────
      // 미인증 요청은 지금까지 **무조건** 302 로 나갔다. 브라우저 주소창 이동에는 맞지만
      // SPA 의 fetch() 에는 CORS 에러로 둔갑해 진짜 원인(미인증)을 감춘다(CLAUDE.md §6.1).
      // entryPoint 가 요청 성격을 보고 302(내비게이션) / 401 problem+json(XHR) 으로 가른다.
      //
      // ⚠️ 이 블록은 oauth2Login **앞이든 뒤든** 상관없지만, 여기서 지정하지 않으면 oauth2Login 이
      // 등록하는 기본 RedirectServerAuthenticationEntryPoint 가 그대로 쓰인다.
      .exceptionHandling { exceptions ->
        exceptions.authenticationEntryPoint(authenticationEntryPoint)
        exceptions.accessDeniedHandler(accessDeniedHandler)
      }
      // ── 저장된 요청을 쓰지 않는다 (2026-08-02, alpha 실측 후 추가) ──────────
      //
      // ## 무엇이 터졌나
      // 로그인에 성공했는데 FE 가 아니라 게이트웨이의 `/login?logout` 으로 착지해 **404** 가 났다.
      // `unigate.frontend.base-uri` 는 정상 주입돼 있었는데도 그랬다.
      //
      // ## 왜 그런가 — 저장된 요청이 설정한 착지를 **이긴다**
      // `RedirectServerAuthenticationSuccessHandler` 는 이렇게 동작한다:
      //
      //     requestCache.getRedirectUri(exchange).defaultIfEmpty(location).flatMap(::sendRedirect)
      //
      // 즉 `setLocation()` 으로 넣은 값은 **저장된 요청이 없을 때만** 쓰인다. 그리고 기본
      // requestCache 는 `WebSessionServerRequestCache` 라 세션에 실제로 저장된다.
      //
      // 저장하는 주체는 [me.ramos.unigate.adapter.gatewayIn.ProblemDetailAuthenticationEntryPoint]
      // 다. 이 진입점은 **분기형**이고, 브라우저 top-level 이동 분기에서
      // `RedirectServerAuthenticationEntryPoint` 에 위임하는데 그 구현이 `saveRequest()` 를 부른다.
      // 401 분기만 보고 "우리는 저장하지 않는다" 고 단정했던 것이 오독이었다.
      //
      // ## 왜 NoOp 이 맞는 선택인가 (덮어쓰기·경로 예외가 아니라)
      // "원래 보려던 페이지로 복귀" 는 **게이트웨이가 화면을 서빙할 때** 의미가 있다. 이 게이트웨이는
      // API 프록시 + BFF 이고 자기 HTML 페이지가 없다. 따라서 저장될 수 있는 것은 ① API 경로
      // (복귀하면 JSON 이 뜬다) 아니면 ② 존재하지도 않는 `/login` 뿐이다 — **둘 다 착지로 부적절**하다.
      // 복귀 가치가 0 이므로 기능을 끄는 것이 특수 케이스를 쌓는 것보다 낫다.
      //
      // ## ⚠️ 이 줄 **하나만으로는 고쳐지지 않는다**
      // 실제로 저장·조회를 하는 두 핸들러는 우리가 직접 `new` 로 만든 객체라, 각자 자기 소유의
      // `WebSessionServerRequestCache` 를 들고 있고 이 빌더 설정이 그 인스턴스에 주입되지 않는다.
      // 이 줄만 넣고 테스트를 돌렸을 때 **여전히 저장이 일어났다.** 그래서 실제 차단은 두 곳에서 한다:
      //   - `ProblemDetailAuthenticationEntryPoint`  → 저장을 막는다
      //   - `AuditingAuthenticationSuccessHandler`   → 읽기를 막는다
      // 여기 설정은 **Spring 이 스스로 만드는** 컴포넌트에 대한 몫이다(우리가 못 보는 경로 대비).
      //
      // 회귀 가드: `GatewaySecurityIntegrationTest`(미인증 302 가 세션을 만들지 않는다).
      .requestCache { cache -> cache.requestCache(NoOpServerRequestCache.getInstance()) }
      .authorizeExchange { exchanges ->
        exchanges.pathMatchers(*PUBLIC_PATHS).permitAll()

        // local 프로브(`/debug/**`)는 @Profile("local") 이라 다른 프로파일에는 라우트 자체가 없다.
        // 그래도 permitAll 목록에 상시 두지 않는다 — 나중에 non-local 용 /debug 가 생기면
        // 그 순간 무인증으로 열리기 때문이다. 권한 완화는 필요한 프로파일에서만 켠다.
        if (environment.activeProfiles.contains(LOCAL_PROFILE)) {
          exchanges.pathMatchers(*LOCAL_ONLY_PUBLIC_PATHS).permitAll()
        }

        // 나머지는 전부 인증 필요. 미인증 요청의 응답 형태는 위 exceptionHandling 이 정한다
        // (브라우저 내비게이션 → 302 Keycloak / XHR → 401 problem+json + loginUrl).
        exchanges.anyExchange().authenticated()
      }.oauth2Login { oauth2 ->
        // PKCE 를 쓰기 위해 resolver 를 명시적으로 갈아끼운다. 이유는 아래 빈 주석 참조.
        oauth2.authorizationRequestResolver(authorizationRequestResolver)
        // Phase 4: 로그인 성공/실패를 감사로그 + 메트릭으로 남긴 뒤 기본 리다이렉트로 위임한다.
        // (WebFlux 는 인증 이벤트를 기본 발행하지 않아 핸들러를 직접 갈아끼운다.)
        oauth2.authenticationSuccessHandler(authenticationSuccessHandler)
        oauth2.authenticationFailureHandler(authenticationFailureHandler)
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
            // Phase 4: 세션을 지우기 **전에** 누가 로그아웃했는지 감사로그로 남긴다(인증 정보가 아직 살아있음).
            auditingLogoutHandler,
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
      // ⚠️ CSRF 거부는 exceptionHandling 의 accessDeniedHandler 를 타지 않는다.
      // `CsrfWebFilter` 가 자체 handler(기본값 `HttpStatusServerAccessDeniedHandler`)를 직접 호출하기
      // 때문이다. 실제로 여기를 빠뜨렸을 때 CSRF 403 만 `text/plain: Access Denied` 로 나갔다.
      // 같은 핸들러를 명시적으로 다시 꽂아 403 응답 형식을 하나로 맞춘다.
      .csrf { csrf ->
        // ── 토큰 저장소를 쿠키로 (Phase 9c) ──────────────────────────────────
        // 기본값은 **세션 저장소**다. 그러면 토큰이 세션 안에만 있어 API 클라이언트(SPA·스크립트)가
        // 읽을 방법이 없다 — GW 는 토큰을 심어줄 HTML 페이지도 렌더링하지 않는다.
        // 그 상태에서는 **인증된 POST 를 아무도 성공시킬 수 없다.**
        //
        // `withHttpOnlyFalse()` 는 JS 가 읽어야 하므로 필수다. 이것이 XSS 에 토큰을 노출하는 것은
        // 맞지만, 애초에 XSS 가 성립하면 공격자는 그 페이지에서 직접 요청을 보낼 수 있어
        // CSRF 토큰 유무가 방어선이 되지 못한다. 표준 double-submit 패턴의 전제다.
        csrf.csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())

        // ── XOR 인코딩을 끈다 (Phase 9c) ────────────────────────────────────
        // Spring Security 6 의 기본값은 `XorServerCsrfTokenRequestAttributeHandler` 다.
        // BREACH 방어를 위해 토큰에 **요청마다 다른 마스크**를 씌워 내보내고, 받을 때 그것을
        // 풀어 원본과 비교한다.
        //
        // 그 방식은 **서버가 렌더링한 폼**에 토큰을 심는 구조를 전제한다. 쿠키에서 읽어 헤더로
        // 보내는 클라이언트에게는 맞지 않는다 — 쿠키에 저장되는 것은 **원본**이고 서버가 기대하는
        // 것은 **마스킹된 값**이라, 쿠키 값을 그대로 보내면 디코딩에 실패한다.
        //
        // 실제 증상: `Did not find a CSRF token in the request`(TRACE) 와 함께 **403**.
        // 토큰을 분명히 실어 보냈는데 "없다"고 하므로, 로그를 켜기 전에는 값 불일치로 보인다.
        //
        // 대가로 BREACH 완화를 포기한다. 그 공격은 (1) 응답이 압축되고 (2) 공격자가 요청을
        // 반복 유도하며 (3) 응답 크기를 관찰할 수 있어야 성립한다. 토큰을 쿠키로 내려보내는 순간
        // 어차피 응답 본문에 싣지 않으므로 그 조건이 약해진다.
        csrf.csrfTokenRequestHandler(ServerCsrfTokenRequestAttributeHandler())

        csrf.accessDeniedHandler(accessDeniedHandler)
        csrf.requireCsrfProtectionMatcher(csrfProtectionMatcher())
      }
      // ⚠️ 저장소만 바꾸면 **쿠키가 실리지 않는다.** WebFlux 에서 `CsrfToken` 은 lazy 한 `Mono` 라
      // 아무도 구독하지 않으면 토큰 생성도 쿠키 쓰기도 일어나지 않는다. 그런데 응답은 정상 200 이라
      // 실패가 드러나지 않는다([CsrfTokenCookieFilter] KDoc 에 증상과 원리를 적어뒀다).
      //
      // `CSRF` **뒤**에 등록해야 한다 — `CsrfWebFilter` 가 attribute 를 넣은 다음이어야 읽을 수 있다.
      .addFilterAfter(CsrfTokenCookieFilter(), SecurityWebFiltersOrder.CSRF)
      .build()

  /**
   * CSRF 보호 대상 판정 — 기본 규칙(안전하지 않은 메서드)에서 **공개 가입만 제외**한다 (Phase 8f).
   *
   * ## 제외하지 않으면 무슨 일이 벌어지나
   * `CsrfWebFilter` 의 기본 매처는 인증 여부와 무관하게 POST/PUT/PATCH/DELETE 를 전부 잡는다.
   * 그런데 가입 요청자는 **세션도 CSRF 토큰도 없는** 상태다. 토큰을 실을 방법이 없으니
   * `POST /iam/register` 는 IAM 에 닿기도 전에 **항상 403** 이 된다. 컴파일·기동은 정상이고
   * 브라우저에서 가입 버튼을 눌러야 드러난다.
   *
   * ## 제외해도 되는 근거
   * CSRF 는 "브라우저가 **자동으로 실어 보내는 인증 정보**(세션 쿠키)를 공격자가 빌려 쓰는 것"을
   * 막는 방어다. 이 엔드포인트는 그 인증 정보를 **아예 쓰지 않는다** — 요청 본문만으로 판단하므로
   * 피해자의 권한으로 무언가를 대신 수행시킬 수 없다. 공격자가 할 수 있는 일은 자기 손으로도
   * 할 수 있는 "가입 요청 보내기"뿐이고, 그건 rate limit 의 영역이다.
   *
   * ⚠️ 그래서 **제외 대상은 공개 라우트 하나로 한정한다.** `/iam` 하위 전체를 빼면 세션 쿠키로
   * 인증되는 프로필·관리 API 가 CSRF 에 그대로 노출된다.
   *
   * (KDoc 안에 `/iam` + 와일드카드 두 개를 그대로 적으면 Kotlin 이 **중첩 블록 주석 시작**으로
   * 읽어 파일 끝까지 주석이 되어버린다. 그래서 이 문서에서는 풀어 쓴다.)
   */
  private fun csrfProtectionMatcher(): ServerWebExchangeMatcher =
    AndServerWebExchangeMatcher(
      // 기본 규칙(= 안전하지 않은 메서드만)을 그대로 상속한다. 직접 메서드 목록을 나열하면
      // Spring Security 가 기본값을 바꿨을 때 따라가지 못한다.
      CsrfWebFilter.DEFAULT_CSRF_MATCHER,
      NegatedServerWebExchangeMatcher(
        ServerWebExchangeMatchers.pathMatchers(GatewayRouteConfig.IAM_PUBLIC_REGISTER_PATH),
      ),
    )

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
   *
   * ## FE 가 다른 호스트면 `{baseUrl}/` 는 게이트웨이 루트다
   * 로그인 착지와 **같은 함정**이다([AuditingAuthenticationSuccessHandler]). `{baseUrl}` 은 언제나
   * 게이트웨이 자신이라, FE 를 분리한 배포에서는 로그아웃 후 FE 가 아니라 게이트웨이 루트로 간다.
   *
   * ⚠️ 여기서 쓰는 값은 **Keycloak client 의 `post.logout.redirect.uris` 에 등록돼 있어야 한다.**
   * `setup-realm.sh --console-host <fe-host>` 가 그 항목을 추가한다. 등록이 없으면 Keycloak 이
   * `invalid_redirect_uri` 로 거절하는데, 증상은 "로그아웃이 안 된다" 로만 보인다.
   */
  private fun oidcLogoutSuccessHandler(
    clientRegistrationRepository: ReactiveClientRegistrationRepository,
  ): ServerLogoutSuccessHandler =
    OidcClientInitiatedServerLogoutSuccessHandler(clientRegistrationRepository).apply {
      setPostLogoutRedirectUri(postLogoutRedirectUri(frontendBaseUri))
      // ── fallback 착지 (2026-08-02, alpha 실측 후 추가) ──────────────────────
      //
      // `setPostLogoutRedirectUri` 는 **OIDC 사용자로 로그인돼 있을 때만** 쓰인다. 인증되지 않은
      // 상태에서 `POST /logout` 이 오면(세션 만료 뒤 로그아웃 버튼, 시크릿 창 첫 방문 등)
      // 위임체 `RedirectServerLogoutSuccessHandler` 의 기본값 **`/login?logout`** 으로 간다.
      //
      // 그 경로는 이 게이트웨이에 **존재하지 않는다** — BFF 라 로그인 페이지를 서빙하지 않는다.
      // 게다가 공개 경로도 아니라 인증 진입점에 걸려 302 → Keycloak 으로 튄다. 실측에서는
      // 이것이 저장된 요청으로 남아 로그인 성공 후 착지를 덮고 **404** 로 끝났다
      // (위 `requestCache` 주석 참조). 두 결함이 연쇄해야 드러나는 형태였다.
      setLogoutSuccessUrl(logoutFallbackUri(frontendBaseUri))
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

    /** FE 를 분리하지 않은 배포의 로그아웃 착지. `{baseUrl}` 은 게이트웨이 자신으로 치환된다. */
    private const val SAME_ORIGIN_POST_LOGOUT_URI = "{baseUrl}/"

    /** same-origin 배포의 루트. `{baseUrl}` 을 치환해 줄 주체가 없는 자리에서 쓴다. */
    private const val SAME_ORIGIN_ROOT = "/"

    /**
     * 로그아웃 착지 URI. FE 베이스가 설정돼 있으면 그쪽, 아니면 게이트웨이 자신.
     *
     * 공백만 있는 값을 그대로 넘기면 Keycloak 이 등록 목록과 대조에 실패해 `invalid_redirect_uri`
     * 가 된다. 설정 실수를 "로그아웃이 안 된다" 로 만나지 않도록 여기서 빈 값과 같게 취급한다.
     */
    internal fun postLogoutRedirectUri(frontendBaseUri: String): String =
      frontendBaseUri.trim().ifEmpty { SAME_ORIGIN_POST_LOGOUT_URI }

    /**
     * 미인증 로그아웃의 착지. Keycloak 을 거치지 않으므로 **`{baseUrl}` 치환이 없다.**
     *
     * [postLogoutRedirectUri] 와 값이 갈리는 유일한 이유가 이것이다 — 그쪽은 Keycloak 에
     * `post_logout_redirect_uri` 로 넘기는 문자열이라 `{baseUrl}` 플레이스홀더가 살아 있지만,
     * 이쪽은 [URI] 로 바로 리다이렉트하므로 치환해 줄 주체가 없다. 같은 문자열을 넣으면
     * 브라우저가 `/%7BbaseUrl%7D/` 로 가서 404 가 난다.
     *
     * same-origin 배포에서 `/` 는 곧 FE 다(로컬은 Vite dev proxy). 분리 배포에서는 FE 절대 주소다.
     */
    internal fun logoutFallbackUri(frontendBaseUri: String): URI =
      URI.create(frontendBaseUri.trim().ifEmpty { SAME_ORIGIN_ROOT })

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
        // CSRF 토큰 조회. cross-origin FE 는 `XSRF-TOKEN` 쿠키를 읽을 수 없어(host-only)
        // 이 경로로 받아 간다. 공개인 이유와 그래도 안전한 근거는 [CsrfTokenEndpoint] KDoc 참조 —
        // 요약하면 **접근 제어를 CORS 허용 목록이 대신한다.**
        //
        // ⚠️ 인증 필요로 바꾸면 미인증 상태에서 토큰을 못 받는다. 지금은 가입이 CSRF 예외라
        // 문제가 없지만, 인증 없이 하는 POST 가 하나라도 늘면 그 순간 조용히 403 이 된다.
        CsrfTokenEndpoint.CSRF_TOKEN_PATH,
        // CB fallback — 인증된 /api 요청이 forward 로 도달하지만, 직접 접근해도 503 만 반환하므로
        // 공개로 둔다(민감정보 없음). 인증 필요로 두면 forward 시 재인증에 걸릴 수 있다.
        "/fallback/**",
        // Phase 8f: IAM 가입. 사용자 토큰이 아직 없는 유일한 IAM 유스케이스라 인증을 요구할 수 없다
        // (IAM_PLATFORM_DECISION.md D4 보강). 대신 게이트웨이에서 **강한 rate limit** 을 건다
        // (GatewayRouteConfig 의 iam-public 라우트 + RateLimitConfig.registrationRateLimiter).
        //
        // ⚠️ 여기에 `/iam/**` 를 넣으면 프로필·관리 API 까지 통째로 무인증이 된다. 경로는
        // GatewayRouteConfig.IAM_PUBLIC_REGISTER_PATH 와 **한 글자도 다르지 않게** 유지한다.
        GatewayRouteConfig.IAM_PUBLIC_REGISTER_PATH,
      )

    /** local 프로파일에서만 열리는 검증용 프로브 (`SessionProbeConfig`, `AuthProbeConfig`). */
    private val LOCAL_ONLY_PUBLIC_PATHS = arrayOf("/debug/**")
  }
}
