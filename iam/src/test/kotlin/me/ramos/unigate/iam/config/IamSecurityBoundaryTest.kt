package me.ramos.unigate.iam.config

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import me.ramos.unigate.iam.adapter.iamIn.CallerProbeController
import me.ramos.unigate.iam.adapter.iamIn.MyMembershipController
import me.ramos.unigate.iam.adapter.iamIn.OutboxAdminController
import me.ramos.unigate.iam.adapter.iamIn.ProblemDetailAccessDeniedHandler
import me.ramos.unigate.iam.adapter.iamIn.ProblemDetailAuthenticationEntryPoint
import me.ramos.unigate.iam.adapter.iamIn.RegisterController
import me.ramos.unigate.iam.adapter.keycloakAdminOut.KeycloakAdminProperties
import me.ramos.unigate.iam.application.outbox.service.OutboxAdminService
import me.ramos.unigate.iam.application.tenant.service.MembershipResult
import me.ramos.unigate.iam.application.tenant.service.MembershipService
import me.ramos.unigate.iam.application.user.dto.RegisterUserResult
import me.ramos.unigate.iam.application.user.port.inbound.RegisterUserInPort
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * IAM Resource Server 의 **인가 경계** 회귀 테스트 (Phase 8f).
 *
 * ## 이 테스트가 지키는 것
 * "게이트웨이가 막아주니까 IAM 은 열어둬도 된다" 는 착각이다. IAM 은 클러스터 안에서 `:8090` 으로
 * 직접 도달 가능하고, 그 경로에는 게이트웨이의 보호가 **하나도** 적용되지 않는다. 그래서
 * `/iam/register` 만 열리고 나머지는 전부 닫혀야 한다.
 *
 * ## 왜 Keycloak 없이 되나
 * `spring-security-test` 의 `jwt()` post-processor 가 검증된 인증 객체를 **직접 주입**한다.
 * 즉 JWKS 조회도 서명 검증도 일어나지 않는다.
 *
 * ⚠️ 그 대가로 **토큰 검증기(`aud` 등)는 이 테스트로 검증되지 않는다.** 그쪽은
 * [JwtAudienceValidationTest] 가 따로 겨냥한다. 이 구분을 잊고 "보안 테스트가 있다"고 안심하면
 * `aud` 검증이 통째로 빠져도 아무도 모른다.
 *
 * 계층: L3(슬라이스). 외부 의존이 없어 `@Tag("testcontainers")` 를 붙이지 않는다 — CI 게이트에서 돈다.
 */
@WebMvcTest(
  controllers = [
    RegisterController::class,
    CallerProbeController::class,
    OutboxAdminController::class,
    MyMembershipController::class,
  ],
)
// Phase 8e: `IamSecurityConfig` 가 Problem Detail 핸들러 두 개를 주입받으므로 함께 올린다.
// 빠뜨리면 컨텍스트 자체가 뜨지 않아 **모든 테스트가 한꺼번에 실패**한다.
@Import(
  IamSecurityConfig::class,
  ProblemDetailAuthenticationEntryPoint::class,
  ProblemDetailAccessDeniedHandler::class,
)
@EnableConfigurationProperties(KeycloakAdminProperties::class)
// IamSecurityConfig 의 LOCAL_ONLY_PUBLIC_PATHS 분기가 이 프로파일에서만 켜진다.
@ActiveProfiles("local")
@TestPropertySource(
  properties = [
    // 존재하지 않는 :1 을 가리킨다. jwtDecoder 는 지연 로딩이라 부팅 시 JWKS 를 조회하지 않고,
    // jwt() post-processor 를 쓰므로 검증 시점에도 조회하지 않는다.
    "unigate.iam.keycloak.server-url=http://localhost:1",
    "unigate.iam.keycloak.realm=test",
    "unigate.iam.keycloak.client-id=unigate-iam",
    "unigate.iam.keycloak.client-secret=not-a-real-secret",
    "unigate.iam.security.expected-audience=unigate-iam",
    // CallerProbeController 는 @ConditionalOnProperty 라 이 값이 없으면 **빈이 아예 없고**,
    // 아래 whoami 테스트가 200 대신 404 로 실패한다. 프로파일이 아니라 속성이 라우트의 존재를 정한다.
    "unigate.iam.probe.caller.enabled=true",
  ],
)
class IamSecurityBoundaryTest {
  @Autowired
  private lateinit var mockMvc: MockMvc

  @MockkBean
  private lateinit var registerUserInPort: RegisterUserInPort

  @MockkBean
  private lateinit var outboxAdminService: OutboxAdminService

  @MockkBean
  private lateinit var membershipService: MembershipService

  @Test
  fun `가입은 인증 없이 열려 있다`() {
    // 가입 요청자는 토큰이 없다. 여기가 닫히면 아무도 가입할 수 없다(D4 보강).
    every { registerUserInPort.register(any()) } returns
      RegisterUserResult(
        email = "newcomer@example.local",
        onboardingState = "PENDING_IDENTITY",
        userRef = null,
      )

    mockMvc
      .perform(
        post("/iam/register")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {"email":"newcomer@example.local","displayName":"뉴커머","firstName":"뉴","lastName":"커머"}
            """.trimIndent(),
          ),
      ).andExpect(status().isCreated)
  }

  @Test
  fun `가입은 CSRF 토큰 없이도 통과한다`() {
    // 위 테스트가 이미 CSRF 없이 POST 하고 있지만, **의도를 드러내려고** 따로 둔다.
    // IAM 은 쿠키를 쓰지 않으므로 CSRF 를 끈다(IamSecurityConfig). 이게 켜지면 MockMvc 의
    // POST 가 403 이 되고, 실제로는 게이트웨이 뒤에서 "가입만 되지 않는" 상태가 된다.
    every { registerUserInPort.register(any()) } returns
      RegisterUserResult(
        email = "csrf@example.local",
        onboardingState = "PENDING_IDENTITY",
        userRef = null,
      )

    mockMvc
      .perform(
        post("/iam/register")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {"email":"csrf@example.local","displayName":"씨","firstName":"씨","lastName":"에스"}
            """.trimIndent(),
          ),
      ).andExpect(status().isCreated)
  }

  @Test
  fun `인증 라우트는 토큰 없이 401 이다`() {
    // 게이트웨이를 우회해 IAM 에 직접 붙었을 때의 방어선.
    mockMvc
      .perform(get("/iam/debug/whoami"))
      .andExpect(status().isUnauthorized)
  }

  @Test
  fun `relay 된 JWT 가 있으면 인증 라우트를 통과하고 호출자 신원이 드러난다`() {
    mockMvc
      .perform(get("/iam/debug/whoami").with(callerToken()))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.subject").value(CALLER_SUBJECT))
      .andExpect(jsonPath("$.preferredUsername").value("alice"))
      // 토큰 원문·서명은 어떤 경우에도 응답에 실리지 않는다(CLAUDE.md §8).
      .andExpect(jsonPath("$.tokenValue").doesNotExist())
  }

  @Test
  fun `등록되지 않은 IAM 경로도 미인증이면 404 가 아니라 401 이다`() {
    // deny by default 확인. 보안 필터가 디스패처보다 **앞**에서 끝내므로, 존재하지 않는 경로라도
    // "그런 것 없다(404)" 가 아니라 "누구냐(401)" 로 답한다. 경로 존재 여부가 새어 나가지 않는다.
    mockMvc
      .perform(get("/iam/admin/tenants"))
      .andExpect(status().isUnauthorized)
  }

  @Test
  fun `actuator health 는 probe 를 위해 열려 있다`() {
    // health 자동설정은 이 슬라이스에 없으므로 200 이 아니라 404 가 정상이다.
    // 확인하려는 것은 상태코드가 아니라 **401 이 아니라는 것** — 즉 인증 요구에 걸리지 않는다는 것.
    mockMvc
      .perform(get("/actuator/health"))
      .andExpect(status().isNotFound)
  }

  // ── Phase 9c: 관리 API 인가 경계 ──────────────────────────────────────

  @Test
  fun `관리 API 는 인증만으로는 통과하지 못한다`() {
    // ⚠️ 이 테스트가 P9c 의 핵심이다.
    //
    // P8e 까지 IAM 의 인가 규칙은 `anyRequest().authenticated()` 하나뿐이었다. 그 상태로 관리
    // API 를 열면 **인증된 아무 사용자나** 남의 자원을 조작할 수 있다. GW 를 우회해 :8090 을
    // 직접 때리는 경로가 실재하므로(P8f 실측), 여기서 막지 못하면 막을 곳이 없다.
    mockMvc
      .perform(get("/iam/admin/outbox/dead").with(callerToken()))
      .andExpect(status().isForbidden)
  }

  @Test
  fun `관리자 권한이 있으면 관리 API 를 통과한다`() {
    every { outboxAdminService.listDead(any()) } returns emptyList()

    mockMvc
      .perform(get("/iam/admin/outbox/dead").with(adminToken()))
      .andExpect(status().isOk)
  }

  @Test
  fun `관리 API 는 토큰이 아예 없으면 403 이 아니라 401 이다`() {
    // 403 은 "누군지 알지만 권한이 없다", 401 은 "누군지 모른다" 다. 이 구분이 무너지면
    // 클라이언트가 재로그인해야 할 상황에서 엉뚱하게 권한 요청 안내를 하게 된다.
    mockMvc
      .perform(get("/iam/admin/outbox/dead"))
      .andExpect(status().isUnauthorized)
  }

  @Test
  fun `관리 경로는 접두사 전체가 막힌다 — 새 엔드포인트를 잊어도 안전하다`() {
    // 아직 만들지 않은 관리 경로다. 규칙을 엔드포인트마다 등록하는 방식이었다면 여기가
    // 인증만으로 뚫렸을 것이다. 접두사로 막으면 **잊어도 안전한 쪽으로** 실패한다.
    mockMvc
      .perform(get("/iam/admin/tenants/some-tenant/members").with(callerToken()))
      .andExpect(status().isForbidden)
  }

  @Test
  fun `초대 수락은 관리자가 아니어도 할 수 있다`() {
    // ⚠️ 이 경로가 /iam/admin 아래에 있으면 **초대 기능이 무의미해진다** —
    // 일반 사용자가 자기 초대를 수락할 수 없기 때문이다.
    // 반대로 관리 경로에 두면 관리자가 남의 초대를 대신 수락할 수 있게 된다.
    //
    // 대상을 토큰 sub 로만 정하므로(경로에 userRef 가 없다) 인가 검사 없이도 안전하다.
    every { membershipService.accept(any(), any()) } returns
      MembershipResult(
        tenantId = "acme",
        userRef = CALLER_SUBJECT,
        role = "member",
        status = "ACTIVE",
        joinedAt = null,
      )

    mockMvc
      .perform(post("/iam/memberships/acme/accept").with(callerToken()))
      .andExpect(status().isOk)
  }

  @Test
  fun `초대 수락도 인증은 필요하다`() {
    mockMvc
      .perform(post("/iam/memberships/acme/accept"))
      .andExpect(status().isUnauthorized)
  }

  @Test
  fun `테넌트 생성도 관리자만 할 수 있다`() {
    // P9c-2 에서 관리 경로가 하나 늘었다. 접두사 규칙 덕에 별도 설정 없이 곧바로 보호된다 —
    // 이 테스트는 그 성질이 유지되는지를 고정한다.
    mockMvc
      .perform(
        post("/iam/admin/tenants")
          .with(callerToken())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"tenantId":"acme","displayName":"에이컴"}"""),
      ).andExpect(status().isForbidden)
  }

  /**
   * 게이트웨이가 relay 했을 법한 토큰을 흉내 낸다.
   *
   * `jwt()` 는 서명·`aud`·`exp` 를 검사하지 않는다 — 이미 검증된 것으로 **간주**하고
   * `JwtAuthenticationToken` 을 SecurityContext 에 넣는다. 그래서 여기에 `aud` 를 넣어도
   * 검증 근거가 되지 않는다(위 KDoc 참조).
   */
  private fun callerToken(): RequestPostProcessor =
    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
      .jwt()
      .jwt { builder ->
        builder
          .subject(CALLER_SUBJECT)
          .claim("preferred_username", "alice")
          .claim("email", "alice@example.local")
          .claim("azp", "unigate-client")
          .audience(listOf("unigate-iam"))
      }

  /**
   * 관리자 권한을 가진 호출자.
   *
   * ⚠️ `authorities(...)` 로 권한을 **직접 지정**한다. `jwt()` post-processor 는 우리
   * [me.ramos.unigate.iam.config.KeycloakRealmRoleConverter] 를 거치지 않으므로,
   * `realm_access` 클레임을 넣어도 권한이 생기지 않는다. 여기서 검증하는 것은 **경로 규칙**이고,
   * 클레임 파싱은 `KeycloakRealmRoleConverterTest` 가 따로 겨냥한다.
   */
  private fun adminToken(): RequestPostProcessor =
    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
      .jwt()
      .jwt { builder ->
        builder
          .subject(ADMIN_SUBJECT)
          .claim("preferred_username", "carol")
          .audience(listOf("unigate-iam"))
      }.authorities(SimpleGrantedAuthority("unigate-admin"))

  companion object {
    private const val CALLER_SUBJECT = "11111111-2222-3333-4444-555555555555"
    private const val ADMIN_SUBJECT = "99999999-8888-7777-6666-555555555555"
  }
}
