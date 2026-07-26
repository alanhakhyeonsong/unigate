package me.ramos.unigate.iam.integration

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import me.ramos.unigate.iam.adapter.jpaOut.repository.OutboxRecordJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.UserProfileJpaRepository
import me.ramos.unigate.iam.application.outbox.service.OutboxProcessor
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderPort
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 프로필 흐름 **전 구간** 통합 테스트 — 실제 PostgreSQL (Phase 8e).
 *
 * ## 단위 테스트로는 못 잡는 것
 * `UpdateMyProfileService` 는 도메인 객체를 변경한 뒤 **명시적으로 `save`** 한다. R2DBC 도 JPA 도
 * 아닌 별개 인스턴스(도메인 모델)를 다루기 때문인데, 이 `save` 를 빠뜨려도 단위 테스트는 통과하고
 * **응답에도 바뀐 값이 담긴다**(메모리상 객체를 그대로 매핑하므로). 실제 DB 를 다시 읽어봐야
 * 사라진 것을 알 수 있다. 그래서 여기서는 매번 **DB 를 재조회해 확인**한다.
 *
 * ## 왜 가입부터 시작하나
 * 프로필은 `userRef` 로 찾는데, `userRef` 는 **outbox 워커가 채운다.** 즉 "조회 가능한 프로필" 을
 * 만들려면 가입 → 워커 처리를 실제로 거쳐야 한다. 그 연결이 끊기면 프로필 API 가 통째로 404 다.
 *
 * Keycloak 만 mock 이다. DB·트랜잭션·워커·보안 필터는 전부 진짜다.
 */
@Tag("testcontainers")
@SpringBootTest(properties = ["unigate.iam.outbox.polling.enabled=false"])
@AutoConfigureMockMvc
@TestPropertySource(
  properties = [
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:3000/unigate_iam_test",
    "spring.datasource.username=testuser",
    "spring.datasource.password=testpass",
    "spring.flyway.url=jdbc:postgresql://127.0.0.1:3000/unigate_iam_test",
    "spring.flyway.user=testuser",
    "spring.flyway.password=testpass",
    "unigate.iam.keycloak.server-url=http://localhost:1",
    "unigate.iam.consent.current-tos-version=v2",
  ],
)
class ProfileFlowIntegrationTest {
  @Autowired
  private lateinit var mockMvc: MockMvc

  @Autowired
  private lateinit var userProfileRepository: UserProfileJpaRepository

  @Autowired
  private lateinit var outboxRepository: OutboxRecordJpaRepository

  @Autowired
  private lateinit var outboxProcessor: OutboxProcessor

  @MockkBean
  private lateinit var identityProviderPort: IdentityProviderPort

  @BeforeEach
  fun cleanUp() {
    outboxRepository.deleteAll()
    userProfileRepository.deleteAll()
  }

  @Test
  fun `가입하고 워커가 신원을 채우면 그때부터 프로필을 조회할 수 있다`() {
    registerAndActivate("alice@example.local", tosVersion = "v2")

    mockMvc
      .perform(get("/iam/profile").with(caller()))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.email").value("alice@example.local"))
      .andExpect(jsonPath("$.userRef").value(KEYCLOAK_USER_ID))
      .andExpect(jsonPath("$.onboardingState").value("ACTIVE"))
      .andExpect(jsonPath("$.consent.valid").value(true))
  }

  @Test
  fun `워커가 처리하기 전에는 프로필을 조회할 수 없다`() {
    // PENDING_IDENTITY 인 프로필은 userRef 가 null 이라 조회 키가 성립하지 않는다.
    // 실제로는 Keycloak 사용자가 없어 로그인 자체가 불가능하므로 도달할 수 없는 상태이지만,
    // 조회가 **다른 사람의 대기 중 프로필을 잡지 않는지**를 여기서 고정한다.
    register("pending@example.local", tosVersion = null).andExpect(status().isCreated)

    mockMvc
      .perform(get("/iam/profile").with(caller()))
      .andExpect(status().isNotFound)
      .andExpect(jsonPath("$.reasonCode").value("profile_not_found"))
  }

  @Test
  fun `표시 이름을 바꾸면 DB 에 실제로 반영된다`() {
    registerAndActivate("bob@example.local", tosVersion = "v2")

    mockMvc
      .perform(
        patch("/iam/profile")
          .with(caller())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"displayName":"바뀐 이름"}"""),
      ).andExpect(status().isOk)
      .andExpect(jsonPath("$.displayName").value("바뀐 이름"))

    // ⚠️ 응답만 보면 성공처럼 보인다. save 누락을 잡는 것은 이 재조회다.
    val stored = userProfileRepository.findByUserRef(KEYCLOAK_USER_ID)!!
    assertThat(stored.displayName).isEqualTo("바뀐 이름")
    // 보내지 않은 필드는 그대로여야 한다.
    assertThat(stored.locale).isEqualTo("ko-KR")
  }

  @Test
  fun `email 은 PATCH 로 바꿀 수 없다`() {
    // 신원 필드라 Keycloak 반영이 필요하고, 그건 outbox 를 타는 별도 유스케이스다.
    // 요청에 email 을 실어도 **무시**돼야 한다(400 이 아니라 무시 — 알 수 없는 필드일 뿐).
    registerAndActivate("carol@example.local", tosVersion = "v2")

    mockMvc
      .perform(
        patch("/iam/profile")
          .with(caller())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"email":"hacked@example.local","displayName":"그대로"}"""),
      ).andExpect(status().isOk)

    assertThat(userProfileRepository.findByUserRef(KEYCLOAK_USER_ID)!!.email)
      .isEqualTo("carol@example.local")
  }

  @Test
  fun `구버전 약관에 동의하려 하면 409 이고 기록이 바뀌지 않는다`() {
    // 가입 때 v1 에 동의했는데 현재 정책은 v2 다(TestPropertySource).
    registerAndActivate("dave@example.local", tosVersion = "v1")

    mockMvc
      .perform(get("/iam/profile").with(caller()))
      .andExpect(jsonPath("$.consent.tosVersion").value("v1"))
      // 서버가 계산해 준다 — 클라이언트가 버전을 비교하지 않아도 재동의가 필요함을 안다.
      .andExpect(jsonPath("$.consent.valid").value(false))

    mockMvc
      .perform(
        post("/iam/profile/consent")
          .with(caller())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"tosVersion":"v1"}"""),
      ).andExpect(status().isConflict)
      .andExpect(jsonPath("$.currentTosVersion").value("v2"))

    assertThat(userProfileRepository.findByUserRef(KEYCLOAK_USER_ID)!!.consentTosVersion).isEqualTo("v1")
  }

  @Test
  fun `현재 버전에 동의하면 기록이 갱신되고 유효해진다`() {
    registerAndActivate("erin@example.local", tosVersion = "v1")

    mockMvc
      .perform(
        post("/iam/profile/consent")
          .with(caller())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"tosVersion":"v2"}"""),
      ).andExpect(status().isOk)
      .andExpect(jsonPath("$.consent.tosVersion").value("v2"))
      .andExpect(jsonPath("$.consent.valid").value(true))

    val stored = userProfileRepository.findByUserRef(KEYCLOAK_USER_ID)!!
    assertThat(stored.consentTosVersion).isEqualTo("v2")
    assertThat(stored.consentAcceptedAt).isNotNull()
  }

  @Test
  fun `남의 userRef 를 본문에 실어도 자기 프로필만 바뀐다`() {
    registerAndActivate("frank@example.local", tosVersion = "v2")

    mockMvc
      .perform(
        patch("/iam/profile")
          .with(caller())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"userRef":"$OTHER_USER_ID","displayName":"침입"}"""),
      ).andExpect(status().isOk)

    // 본문의 userRef 는 매핑되지 않는 필드라 무시된다. 대상은 토큰의 sub 뿐이다.
    assertThat(userProfileRepository.findByUserRef(KEYCLOAK_USER_ID)!!.displayName).isEqualTo("침입")
    assertThat(userProfileRepository.findByUserRef(OTHER_USER_ID)).isNull()
  }

  @Test
  fun `토큰 없이 프로필에 접근하면 401 Problem Detail 이고 WWW-Authenticate 가 남는다`() {
    mockMvc
      .perform(get("/iam/profile"))
      .andExpect(status().isUnauthorized)
      .andExpect(jsonPath("$.reasonCode").value("authentication_required"))
      // 표준 헤더를 잃지 않았는지 — entry point 를 갈아끼우면 사라지기 쉬운 지점이다.
      .andExpect(header().exists("WWW-Authenticate"))
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────

  /** 가입 → 워커 1회 처리까지. 이 과정을 거쳐야 `userRef` 가 생겨 프로필 API 를 쓸 수 있다. */
  private fun registerAndActivate(
    email: String,
    tosVersion: String?,
  ) {
    every { identityProviderPort.createUser(any()) } returns UserRef(KEYCLOAK_USER_ID)
    register(email, tosVersion).andExpect(status().isCreated)
    assertThat(outboxProcessor.processOne()).isTrue()
  }

  private fun register(
    email: String,
    tosVersion: String?,
  ): ResultActions {
    val tos = tosVersion?.let { ""","tosVersion":"$it"""" } ?: ""
    return mockMvc.perform(
      post("/iam/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"email":"$email","displayName":"이름","firstName":"이","lastName":"름"$tos}"""),
    )
  }

  /** 게이트웨이가 relay 했을 법한 토큰. `sub` 가 워커가 채운 `userRef` 와 같아야 조회된다. */
  private fun caller(): RequestPostProcessor =
    jwt().jwt { builder -> builder.subject(KEYCLOAK_USER_ID).audience(listOf("unigate-iam")) }

  companion object {
    private const val KEYCLOAK_USER_ID = "kc-user-profile-1"
    private const val OTHER_USER_ID = "kc-user-profile-2"
  }
}
