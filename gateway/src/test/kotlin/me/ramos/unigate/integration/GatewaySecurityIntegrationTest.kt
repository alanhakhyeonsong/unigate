package me.ramos.unigate.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Phase 1 마감 통합 테스트 — SecurityConfig 의 인가 경계를 오프라인으로 회귀 고정한다.
 *
 * ## 왜 이렇게(가벼운 통합) 테스트하나
 *
 * 게이트웨이는 `issuer-uri` discovery 로 Keycloak 을 부팅 시 호출하고, 세션은 Valkey, 감사로그는
 * PostgreSQL 에 의존한다. 이 셋을 다 띄우면 CI 에서 못 돌린다. 그래서 test 프로파일이
 * (1) provider 엔드포인트를 **정적으로** 박아 discovery 를 없애고, (2) DB/Redis 자동구성을 제외해
 * **Docker 없이** 부팅한다(`application-test.yml`). 로그인 "완료"가 아니라 **리다이렉트 시작까지**만
 * 검증하므로 실제 IdP·다운스트림 호출이 없다.
 *
 * ## 무엇을 지키는가
 * - 공개 경로(health)는 인증 없이 열려야 한다(k8s probe).
 * - 그 외 경로는 미인증이면 **라우팅 이전에** 로그인으로 리다이렉트된다 → 다운스트림에 닿지 못한다.
 * - confidential client 라도 인가 요청에 **PKCE(S256)** 가 붙는다(SecurityConfig 의 미묘한 실패 모드 가드).
 *
 * 계층: L4(풀 컨텍스트 @SpringBootTest)지만 외부 의존이 없어 `@Tag("testcontainers")` 를 붙이지 않는다
 * (CI 게이트에서 실행됨). JUnit5 사용(testing skill 규칙 2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewaySecurityIntegrationTest {
  @LocalServerPort
  private var port: Int = 0

  private lateinit var client: WebTestClient

  @BeforeEach
  fun setUp() {
    // WebTestClient 는 기본적으로 리다이렉트를 따라가지 않는다 → 302 를 그대로 관찰할 수 있다.
    client =
      WebTestClient
        .bindToServer()
        .baseUrl("http://localhost:$port")
        .build()
  }

  @Test
  fun `공개 경로 actuator health 는 인증 없이 200 을 준다`() {
    client
      .get()
      .uri("/actuator/health")
      .exchange()
      .expectStatus()
      .isOk
  }

  @Test
  fun `미인증 요청은 라우팅 이전에 로그인 시작점으로 리다이렉트된다`() {
    // 다운스트림(demo-uri)은 존재하지 않는 :1 이지만, 리다이렉트로 막히므로 절대 호출되지 않는다.
    client
      .get()
      .uri("/api/echo")
      .accept(MediaType.TEXT_HTML)
      .exchange()
      .expectStatus()
      .is3xxRedirection
      .expectHeader()
      .valueMatches("Location", ".*/oauth2/authorization/keycloak")
  }

  @Test
  fun `위조 Authorization 헤더를 실어도 미인증이면 리다이렉트로 차단된다`() {
    // Step 7(헤더 strip)의 보완선: 인증이 없으면 위조 토큰이 있어도 라우팅 전에 끝난다.
    client
      .get()
      .uri("/api/echo")
      .accept(MediaType.TEXT_HTML)
      .header("Authorization", "Bearer FORGED")
      .exchange()
      .expectStatus()
      .is3xxRedirection
  }

  @Test
  fun `CB fallback 경로는 공개이며 503 Problem Detail 을 반환한다`() {
    // CircuitBreaker 가 open/타임아웃 시 forward 하는 목적지. 직접 접근해도 인증 없이 503 을 준다.
    // (CB 가 실제로 열려 여기로 흘러오는 것은 다운스트림을 죽인 브라우저 e2e 로 검증한다.)
    client
      .get()
      .uri("/fallback/downstream")
      .exchange()
      .expectStatus()
      .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
      .expectHeader()
      .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
      .expectBody()
      .jsonPath("$.reasonCode")
      .isEqualTo("downstream_unavailable")
  }

  @Test
  fun `안전한 요청에 CSRF 토큰이 쿠키로 내려온다`() {
    // ⚠️ 이 테스트가 없으면 **아무도 POST 를 성공시킬 수 없는 상태**가 조용히 유지된다.
    //
    // CSRF 토큰 저장소가 기본값(세션)이면 토큰이 세션 안에만 있어 API 클라이언트가 읽을 방법이
    // 없다. GW 는 토큰을 심어줄 HTML 을 렌더링하지도 않는다. 그런데 그 상태에서도 GET 은 전부
    // 정상 200 이라 아무 문제가 없어 보인다 — 인증된 POST 를 실제로 보내봐야 403 으로 드러난다.
    //
    // 저장소를 쿠키로 바꾼 것만으로도 부족하다. WebFlux 에서 CsrfToken 은 lazy 한 Mono 라
    // 구독하지 않으면 쿠키가 실리지 않는다(CsrfTokenCookieFilter 가 구독을 강제한다).
    // 그 필터를 빼면 이 테스트만 빨갛게 된다.
    client
      .get()
      .uri("/actuator/health")
      .exchange()
      .expectStatus()
      .isOk
      .expectCookie()
      .exists("XSRF-TOKEN")
      // JS 가 읽어야 하므로 HttpOnly 여서는 안 된다. double-submit 패턴의 전제다.
      .expectCookie()
      .httpOnly("XSRF-TOKEN", false)
  }

  @Test
  fun `쿠키로 받은 CSRF 토큰을 헤더로 보내면 통과한다`() {
    // 토큰을 **받을 수 있다**는 것과 그 토큰이 **통한다**는 것은 다른 문제다.
    //
    // Spring Security 6 의 기본 핸들러(XorServerCsrfTokenRequestAttributeHandler)는 토큰에
    // 요청마다 다른 마스크를 씌워 내보낸다. 그 방식에서는 쿠키의 원본 값을 그대로 헤더에 실으면
    // **디코딩에 실패해 403** 이고, 로그에는 "Did not find a CSRF token in the request" 로 찍힌다 —
    // 토큰을 분명히 보냈는데 "없다"고 하므로 원인을 찾기 어렵다.
    //
    // 이 테스트는 그 조합(쿠키 저장소 + XOR 해제)이 실제로 통하는지를 고정한다.
    val token =
      client
        .get()
        .uri("/actuator/health")
        .exchange()
        .expectStatus()
        .isOk
        .returnResult(String::class.java)
        .responseCookies
        .getFirst("XSRF-TOKEN")
        ?.value

    assertThat(token).isNotBlank()

    client
      .post()
      .uri("/logout")
      .cookie("XSRF-TOKEN", token!!)
      .header("X-XSRF-TOKEN", token)
      .exchange()
      // 확인하려는 것은 상태코드 자체가 아니라 **CsrfWebFilter 에 막히지 않았다**는 사실이다.
      // (로그아웃 자체는 미인증이라 리다이렉트로 끝난다.)
      .expectStatus()
      .value { status -> assertThat(status).isNotEqualTo(HttpStatus.FORBIDDEN.value()) }
  }

  @Test
  fun `CSRF 토큰 없는 POST logout 은 403 Problem Detail 로 거부된다`() {
    // 로그아웃은 상태를 바꾸는 요청이라 CSRF 로 보호된다(SecurityConfig 의 logout 설정).
    // 토큰 없이 POST /logout 하면 CsrfWebFilter 가 라우팅 이전에 403 으로 막는다.
    // (실제 로그아웃 성공 경로 — Keycloak end_session 왕복 — 은 브라우저 e2e 로 검증한다.)
    //
    // Phase 4: 403 도 problem+json 으로 통일된다(ProblemDetailAccessDeniedHandler).
    // 403 은 로그인해도 해결되지 않으므로 **리다이렉트하지 않는다** — 302 를 주면 무한 로그인 루프가 된다.
    client
      .post()
      .uri("/logout")
      .exchange()
      .expectStatus()
      .isForbidden
      .expectHeader()
      .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
      .expectBody()
      .jsonPath("$.reasonCode")
      .isEqualTo("access_denied")
  }

  // ── Phase 4: 미인증 응답이 요청 성격에 따라 갈라지는가 ──────────────────────

  @Test
  fun `XHR 미인증 요청은 302 가 아니라 401 Problem Detail 과 loginUrl 을 받는다`() {
    // 이 테스트가 지키는 것: fetch() 가 302 를 따라가 Keycloak 을 직접 호출하고 CORS 에러로
    // 둔갑하는 사고(CLAUDE.md §6.1). 302 가 아니라 401 이어야 FE 가 원인을 알 수 있다.
    client
      .get()
      .uri("/api/echo")
      .header("Sec-Fetch-Mode", "cors")
      .exchange()
      .expectStatus()
      .isUnauthorized
      .expectHeader()
      .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
      .expectBody()
      .jsonPath("$.reasonCode")
      .isEqualTo("authentication_required")
      // FE 는 이 값으로 window.location 을 바꿔 **top-level 이동**을 일으킨다.
      .jsonPath("$.loginUrl")
      .isEqualTo("/oauth2/authorization/keycloak")
      .jsonPath("$.status")
      .isEqualTo(401)
  }

  @Test
  fun `Accept 가 application_json 인 미인증 요청도 401 Problem Detail 을 받는다`() {
    // Sec-Fetch-Mode 를 붙이지 않는 클라이언트(구형 브라우저·서버간 호출) 대비 폴백 경로.
    client
      .get()
      .uri("/api/echo")
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus()
      .isUnauthorized
      .expectHeader()
      .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
  }

  @Test
  fun `브라우저 top-level 이동은 종전대로 302 로 Keycloak 에 보낸다`() {
    // 401 도입이 브라우저 로그인 경로를 깨지 않았는지 확인한다(회귀 가드).
    client
      .get()
      .uri("/api/echo")
      .header("Sec-Fetch-Mode", "navigate")
      .accept(MediaType.TEXT_HTML)
      .exchange()
      .expectStatus()
      .is3xxRedirection
      .expectHeader()
      .valueMatches("Location", ".*/oauth2/authorization/keycloak")
  }

  // ── Phase 8f: IAM 라우트 — 공개(가입)와 인증(그 외)이 실제로 갈라지는가 ──────────────

  @Test
  fun `가입 경로는 인증 없이 라우팅까지 통과한다`() {
    // 이 테스트의 요점은 상태코드가 503 이라는 것이 아니라 **401·302 가 아니라는 것**이다.
    // 503 은 CB fallback(iam_unavailable)이 낸 것이고, 그 지점에 도달했다는 것은
    // security 를 통과해 iam-public 라우트로 프록시를 시도했다는 뜻이다.
    // (test 프로파일의 iam.uri 는 존재하지 않는 :1 이다.)
    //
    // ⚠️ 여기서 CSRF 도 함께 검증된다. CSRF 예외를 빼면 이 POST 는 IAM 에 닿기도 전에 403 이다.
    client
      .post()
      .uri("/iam/register")
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{"email":"a@example.local","displayName":"a","firstName":"a","lastName":"a"}""")
      .exchange()
      .expectStatus()
      .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
      .expectBody()
      .jsonPath("$.reasonCode")
      .isEqualTo("iam_unavailable")
  }

  @Test
  fun `IAM 인증 라우트는 미인증 XHR 에 401 을 준다`() {
    // 공개로 열린 것은 /iam/register 하나뿐이다. 나머지 IAM 경로가 함께 열리면
    // 프로필·관리 API 가 통째로 무인증이 된다 — 그 회귀를 막는다.
    client
      .get()
      .uri("/iam/profile")
      .header("Sec-Fetch-Mode", "cors")
      .exchange()
      .expectStatus()
      .isUnauthorized
      .expectBody()
      .jsonPath("$.reasonCode")
      .isEqualTo("authentication_required")
  }

  @Test
  fun `가입 경로와 한 글자라도 다른 GET 은 인증을 요구한다`() {
    // permitAll·CSRF 예외 모두 **정확한 경로 매칭**이다. 접두사가 같다고 열리지 않는다
    // (`/iam/register-admin` 같은 경로가 나중에 생겨도 자동으로 공개되지 않는다).
    client
      .get()
      .uri("/iam/registerX")
      .header("Sec-Fetch-Mode", "cors")
      .exchange()
      .expectStatus()
      .isUnauthorized
  }

  @Test
  fun `가입 경로가 아닌 POST 는 CSRF 에서 먼저 막힌다`() {
    // 실측으로 확인한 순서다. `CsrfWebFilter` 는 인가 판정보다 **앞**에서 돌기 때문에,
    // 미인증 POST 는 401 이 아니라 **403** 이 된다.
    //
    // 처음엔 401 을 기대했다가 403 을 받고 알게 된 사실이다. 방어 자체는 더 이른 단계에서
    // 성립하므로 문제가 아니지만, "미인증 = 401" 이라고 단정하면 진단을 헛짚는다 —
    // 403 을 보고 권한 설정을 뒤지게 되는데 실제 원인은 CSRF 토큰 부재다.
    client
      .post()
      .uri("/iam/registerX")
      .header("Sec-Fetch-Mode", "cors")
      .exchange()
      .expectStatus()
      .isForbidden
      .expectBody()
      .jsonPath("$.reasonCode")
      .isEqualTo("access_denied")
  }

  @Test
  fun `IAM CB fallback 은 다운스트림과 다른 reasonCode 를 준다`() {
    // 무엇이 죽었는지 구분할 수 있어야 한다. 둘을 같은 코드로 합치면 가입 실패와
    // 제품 API 실패가 대시보드에서 구분되지 않는다.
    client
      .get()
      .uri("/fallback/iam")
      .exchange()
      .expectStatus()
      .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
      .expectHeader()
      .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
      .expectBody()
      .jsonPath("$.reasonCode")
      .isEqualTo("iam_unavailable")
  }

  @Test
  fun `로그인 시작 요청은 PKCE(S256)를 붙여 Keycloak 인가 엔드포인트로 리다이렉트한다`() {
    // confidential client 에는 Spring 이 PKCE 를 자동 적용하지 않는다 → SecurityConfig 의
    // authorizationRequestResolver(withPkce) 가 실제로 code_challenge 를 붙이는지 회귀 검증한다.
    // 이게 빠지면 realm 이 PKCE 를 강제할 때 로그인 화면 전에 invalid_request 로 거절된다.
    client
      .get()
      .uri("/oauth2/authorization/keycloak")
      .exchange()
      .expectStatus()
      .is3xxRedirection
      .expectHeader()
      .valueMatches(
        "Location",
        "http://localhost:1/realms/test/protocol/openid-connect/auth\\?.*code_challenge=[^&]+.*code_challenge_method=S256.*",
      )
  }
}
