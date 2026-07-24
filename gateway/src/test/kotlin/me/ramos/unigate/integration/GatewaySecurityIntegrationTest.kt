package me.ramos.unigate.integration

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
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
  fun `CSRF 토큰 없는 POST logout 은 403 으로 거부된다`() {
    // 로그아웃은 상태를 바꾸는 요청이라 CSRF 로 보호된다(SecurityConfig 의 logout 설정).
    // 토큰 없이 POST /logout 하면 CsrfWebFilter 가 라우팅 이전에 403 으로 막는다.
    // (실제 로그아웃 성공 경로 — Keycloak end_session 왕복 — 은 브라우저 e2e 로 검증한다.)
    client
      .post()
      .uri("/logout")
      .exchange()
      .expectStatus()
      .isForbidden
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
