package me.ramos.unigate.iam.adapter.iamIn

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.slot
import me.ramos.unigate.iam.adapter.keycloakAdminOut.KeycloakAdminProperties
import me.ramos.unigate.iam.application.user.dto.ConsentResult
import me.ramos.unigate.iam.application.user.dto.MyProfileResult
import me.ramos.unigate.iam.application.user.dto.UpdateMyProfileCommand
import me.ramos.unigate.iam.application.user.port.inbound.AcceptConsentInPort
import me.ramos.unigate.iam.application.user.port.inbound.ChangeMyEmailInPort
import me.ramos.unigate.iam.application.user.port.inbound.GetMyProfileInPort
import me.ramos.unigate.iam.application.user.port.inbound.UpdateMyProfileInPort
import me.ramos.unigate.iam.application.user.port.outbound.ProfileConcurrentlyModifiedException
import me.ramos.unigate.iam.application.user.service.ConsentVersionMismatchException
import me.ramos.unigate.iam.application.user.service.ProfileNotFoundException
import me.ramos.unigate.iam.config.IamSecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * 프로필 API 웹 계층 슬라이스 테스트 (Phase 8e).
 *
 * ## 이 테스트가 지키는 것 중 가장 중요한 하나
 * **대상 자원이 요청이 아니라 토큰에서 온다**는 계약이다. 요청 본문에 남의 `userRef` 를 넣어도
 * 무시돼야 한다. 이게 깨지면 프로필 API 전체가 IDOR 로 뚫린다.
 *
 * 나머지는 응답 형식(Problem Detail 통일)과 상태코드 선택(404 vs 401, 409 vs 400)의 회귀 고정이다.
 */
@WebMvcTest(controllers = [ProfileController::class])
@Import(IamSecurityConfig::class, ProblemDetailAuthenticationEntryPoint::class, ProblemDetailAccessDeniedHandler::class)
@EnableConfigurationProperties(KeycloakAdminProperties::class)
@TestPropertySource(
  properties = [
    "unigate.iam.keycloak.server-url=http://localhost:1",
    "unigate.iam.keycloak.realm=test",
    "unigate.iam.keycloak.client-id=unigate-iam",
    "unigate.iam.keycloak.client-secret=not-a-real-secret",
    "unigate.iam.security.expected-audience=unigate-iam",
    "spring.mvc.problemdetails.enabled=true",
  ],
)
class ProfileControllerTest {
  @Autowired
  private lateinit var mockMvc: MockMvc

  @MockkBean
  private lateinit var getMyProfileInPort: GetMyProfileInPort

  @MockkBean
  private lateinit var updateMyProfileInPort: UpdateMyProfileInPort

  @MockkBean
  private lateinit var acceptConsentInPort: AcceptConsentInPort

  @MockkBean
  private lateinit var changeMyEmailInPort: ChangeMyEmailInPort

  // ── 인가 경계 ────────────────────────────────────────────────────────────

  @Test
  fun `토큰 없이 프로필을 조회하면 401 Problem Detail 이다`() {
    // P8f 까지는 본문이 비어 있었다. 형식 통일이 이 테스트의 대상이다.
    mockMvc
      .perform(get("/iam/profile"))
      .andExpect(status().isUnauthorized)
      // `contentTypeCompatibleWith` 를 쓴다 — 정확 일치로 비교하면 charset 파라미터
      // (`application/problem+json;charset=UTF-8`) 때문에 실패한다.
      .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
      .andExpect(jsonPath("$.reasonCode").value("authentication_required"))
      .andExpect(jsonPath("$.instance").value("/iam/profile"))
      // 표준 헤더는 그대로 남긴다 — OAuth2 클라이언트가 재인증 방법을 아는 공식 경로다.
      .andExpect(header().exists("WWW-Authenticate"))
  }

  @Test
  fun `대상 사용자는 요청 본문이 아니라 토큰에서만 온다`() {
    // 본문에 남의 userRef 를 실어 보낸다. 무시돼야 한다.
    val command = slot<UpdateMyProfileCommand>()
    every { updateMyProfileInPort.update(capture(command)) } answers { profileResult() }

    mockMvc
      .perform(
        patch("/iam/profile")
          .with(callerToken())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"displayName":"공격자","userRef":"$OTHER_USER"}"""),
      ).andExpect(status().isOk)

    // 유스케이스에 전달된 userRef 는 토큰의 sub 여야 한다.
    assert(command.captured.userRef == CALLER) {
      "요청 본문의 userRef 가 반영됐다 — IDOR 취약점: ${command.captured.userRef}"
    }
  }

  // ── 정상 경로 ────────────────────────────────────────────────────────────

  @Test
  fun `프로필을 조회하면 동의 유효성까지 서버가 계산해 준다`() {
    every { getMyProfileInPort.get(CALLER) } returns profileResult()

    mockMvc
      .perform(get("/iam/profile").with(callerToken()))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.userRef").value(CALLER))
      .andExpect(jsonPath("$.email").value("alice@example.local"))
      .andExpect(jsonPath("$.consent.valid").value(true))
  }

  @Test
  fun `필드를 생략한 PATCH 는 null 로 전달된다 — 변경 안 함`() {
    val command = slot<UpdateMyProfileCommand>()
    every { updateMyProfileInPort.update(capture(command)) } answers { profileResult() }

    mockMvc
      .perform(
        patch("/iam/profile")
          .with(callerToken())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"displayName":"새 이름"}"""),
      ).andExpect(status().isOk)

    assert(command.captured.displayName == "새 이름")
    assert(command.captured.locale == null) { "생략한 필드가 null 이 아니면 부분 갱신이 깨진다" }
  }

  @Test
  fun `약관에 동의하면 200 이다`() {
    every { acceptConsentInPort.accept(any()) } returns profileResult()

    mockMvc
      .perform(
        post("/iam/profile/consent")
          .with(callerToken())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"tosVersion":"v1"}"""),
      ).andExpect(status().isOk)
      .andExpect(jsonPath("$.consent.tosVersion").value("v1"))
  }

  // ── 실패 경로의 상태코드 선택 ────────────────────────────────────────────

  @Test
  fun `프로필이 없으면 401 이 아니라 404 다`() {
    // 401 을 주면 클라이언트가 재로그인 → 성공 → 다시 401 로 무한 루프에 빠진다.
    every { getMyProfileInPort.get(CALLER) } throws ProfileNotFoundException(CALLER)

    mockMvc
      .perform(get("/iam/profile").with(callerToken()))
      .andExpect(status().isNotFound)
      .andExpect(jsonPath("$.reasonCode").value("profile_not_found"))
  }

  @Test
  fun `약관 버전이 다르면 409 이고 현재 버전을 알려준다`() {
    // 400 이 아닌 이유: 요청은 올바르고 서버 상태와 충돌했을 뿐이다. 재시도로 해결된다.
    every { acceptConsentInPort.accept(any()) } throws ConsentVersionMismatchException("v0", "v1")

    mockMvc
      .perform(
        post("/iam/profile/consent")
          .with(callerToken())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"tosVersion":"v0"}"""),
      ).andExpect(status().isConflict)
      .andExpect(jsonPath("$.reasonCode").value("consent_version_mismatch"))
      // 이 필드가 있어야 클라이언트가 한 번의 왕복으로 재시도할 수 있다.
      .andExpect(jsonPath("$.currentTosVersion").value("v1"))
  }

  /**
   * 동시 수정 충돌이 **500 이 아니라 409** 로 나가는지 고정한다.
   *
   * 이 매핑이 없으면 낙관적 락 예외가 그대로 올라가 500 이 된다. 사용자에게는 "서버가 고장났다"
   * 로 보이지만 실제로는 **그대로 다시 보내면 성공하는** 상황이라, 클라이언트가 취할 행동이
   * 완전히 달라진다.
   */
  @Test
  fun `다른 요청이 먼저 프로필을 바꿨으면 500 이 아니라 409 다`() {
    every { updateMyProfileInPort.update(any()) } throws ProfileConcurrentlyModifiedException()

    mockMvc
      .perform(
        patch("/iam/profile")
          .with(callerToken())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"displayName":"늦게 도착한 변경"}"""),
      ).andExpect(status().isConflict)
      .andExpect(jsonPath("$.reasonCode").value("profile_modified_concurrently"))
  }

  @Test
  fun `약관 버전이 비어 있으면 400 Problem Detail 이다`() {
    // spring.mvc.problemdetails.enabled 가 꺼지면 Spring 기본 에러 본문으로 돌아가 형식이 갈린다.
    mockMvc
      .perform(
        post("/iam/profile/consent")
          .with(callerToken())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"tosVersion":"  "}"""),
      ).andExpect(status().isBadRequest)
      // `contentTypeCompatibleWith` 를 쓴다 — 정확 일치로 비교하면 charset 파라미터
      // (`application/problem+json;charset=UTF-8`) 때문에 실패한다.
      .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
  }

  private fun callerToken(): RequestPostProcessor =
    jwt().jwt { builder ->
      builder.subject(CALLER).claim("preferred_username", "alice").audience(listOf("unigate-iam"))
    }

  private fun profileResult(): MyProfileResult =
    MyProfileResult(
      email = "alice@example.local",
      pendingEmail = null,
      displayName = "Alice",
      locale = "ko-KR",
      onboardingState = "ACTIVE",
      userRef = CALLER,
      consent = ConsentResult(tosVersion = "v1", acceptedAt = Instant.EPOCH, valid = true),
    )

  companion object {
    private const val CALLER = "11111111-2222-3333-4444-555555555555"
    private const val OTHER_USER = "99999999-9999-9999-9999-999999999999"
  }
}
