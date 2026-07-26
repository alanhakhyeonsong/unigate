package me.ramos.unigate.iam.integration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import me.ramos.unigate.iam.adapter.jpaOut.repository.AuditLogJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.OutboxRecordJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.UserProfileJpaRepository
import me.ramos.unigate.iam.application.outbox.service.OutboxProcessor
import me.ramos.unigate.iam.application.user.port.outbound.IdentityAlreadyExistsException
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderPort
import me.ramos.unigate.iam.domain.audit.enums.AuditEventType
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * IAM 감사 스트림 통합 테스트 — 실제 PostgreSQL (Phase 8g).
 *
 * ## 단위 테스트로는 못 잡는 것 세 가지
 * 1. **`traceId` 가 실제로 찍히는가.** 어댑터가 `Tracer` 에서 읽는데, 단위 테스트에서는 포트를
 *    모킹하므로 그 코드가 아예 실행되지 않는다.
 * 2. **JSONB 매핑이 되는가.** `detail` 을 `@JdbcTypeCode(SqlTypes.JSON)` 없이 넣으면 PostgreSQL 이
 *    `column "detail" is of type jsonb but expression is of type character varying` 로 거절한다.
 *    H2 나 mock 으로는 재현되지 않는다.
 * 3. **fail-closed 가 실제로 성립하는가.** 감사 저장이 실패하면 업무도 롤백돼야 한다.
 *    트랜잭션이 진짜여야 확인된다.
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
    // traceId 가 찍히는지 보려면 모든 요청에 span 이 있어야 한다. 샘플링에서 빠지면 null 이 된다.
    "management.tracing.sampling.probability=1.0",
  ],
)
class AuditFlowIntegrationTest {
  @Autowired
  private lateinit var mockMvc: MockMvc

  @Autowired
  private lateinit var auditLogRepository: AuditLogJpaRepository

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
    auditLogRepository.deleteAll()
    outboxRepository.deleteAll()
    userProfileRepository.deleteAll()
  }

  @Test
  fun `가입하면 프로필·outbox 와 같은 커밋에 감사가 남는다`() {
    register("alice@example.local").andExpect(status().isCreated)

    val events = auditLogRepository.findAll()
    assertThat(events).hasSize(1)
    val event = events.first()
    assertThat(event.eventType).isEqualTo(AuditEventType.USER_REGISTERED)
    // 가입은 미인증 요청이라 행위자 토큰이 없다. 대상도 아직 sub 가 없어 email 로만 가리킨다.
    assertThat(event.actorRef).isNull()
    assertThat(event.targetRef).isNull()
    assertThat(event.targetEmail).isEqualTo("alice@example.local")
  }

  @Test
  fun `HTTP 요청에서 발생한 감사에는 traceId 가 찍힌다`() {
    // 이게 GW 감사와 IAM 감사를 잇는 **유일한 열쇠**다. null 이면 두 스트림을 연결할 방법이 없다.
    register("trace@example.local").andExpect(status().isCreated)

    val event = auditLogRepository.findAll().first()
    assertThat(event.traceId).isNotBlank()
  }

  @Test
  fun `detail 이 JSONB 로 저장된다`() {
    register("json@example.local").andExpect(status().isCreated)

    val event = auditLogRepository.findAll().first()
    // 저장 자체가 성공했다는 것이 매핑이 맞았다는 뜻이다(틀리면 INSERT 가 예외로 터진다).
    assertThat(event.detail).contains("onboardingState")
    assertThat(event.detail).contains("PENDING_IDENTITY")
  }

  @Test
  fun `프로필을 수정하면 변경 전후가 감사에 남는다`() {
    registerAndActivate("bob@example.local")
    auditLogRepository.deleteAll() // 가입·신원 사건을 지우고 수정 사건만 본다

    mockMvc
      .perform(
        patch("/iam/profile")
          .with(caller())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"displayName":"바뀐 이름"}"""),
      ).andExpect(status().isOk)

    val event = auditLogRepository.findAll().single()
    assertThat(event.eventType).isEqualTo(AuditEventType.PROFILE_UPDATED)
    assertThat(event.actorRef).isEqualTo(KEYCLOAK_USER_ID)
    assertThat(event.targetRef).isEqualTo(KEYCLOAK_USER_ID)
    assertThat(event.detail).contains("before").contains("after").contains("바뀐 이름")
    // 보내지 않은 필드는 담기지 않는다.
    assertThat(event.detail).doesNotContain("locale")
  }

  @Test
  fun `약관에 동의하면 이전 버전과 함께 감사에 남는다`() {
    registerAndActivate("carol@example.local", tosVersion = "v1")
    auditLogRepository.deleteAll()

    mockMvc
      .perform(
        post("/iam/profile/consent")
          .with(caller())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"tosVersion":"v2"}"""),
      ).andExpect(status().isOk)

    val event = auditLogRepository.findAll().single()
    assertThat(event.eventType).isEqualTo(AuditEventType.CONSENT_ACCEPTED)
    // user_profile 에는 현재 버전만 남으므로, 개정 이력은 이 기록에만 있다.
    //
    // ⚠️ 저장된 문자열을 **그대로 비교하지 않는다.** jsonb 는 저장 시 정규화되어 되읽으면
    // 공백과 키 순서가 넣을 때와 다르다(실제 값: `{"tosVersion": "v2", "previousVersion": "v1"}`).
    // `"previousVersion":"v1"` 처럼 붙여 쓴 패턴으로 단언하면 내용이 맞는데도 실패한다.
    assertThat(readDetail(event.detail)).containsEntry("previousVersion", "v1")
    assertThat(readDetail(event.detail)).containsEntry("tosVersion", "v2")
  }

  @Test
  fun `워커가 신원을 만들면 그 사건도 감사에 남는다`() {
    registerAndActivate("dave@example.local")

    val all = auditLogRepository.findAll()
    val registered = all.single { it.eventType == AuditEventType.USER_REGISTERED }
    val identityEvents = all.filter { it.eventType == AuditEventType.IDENTITY_CREATED }

    assertThat(identityEvents).hasSize(1)
    assertThat(identityEvents.first().targetRef).isEqualTo(KEYCLOAK_USER_ID)

    // ⚠️ 워커 사건은 **가입 요청과 다른 trace** 다. 그래서 traceId 로는 둘을 이을 수 없고
    // target_email 로 이어야 한다.
    //
    // "워커니까 traceId 가 null" 이라고 단언하지 않는다 — 실제로 띄워보면 스케줄러가 자기 span 을
    // 만들어 **값이 찍힌다**(이 테스트는 processOne() 을 직접 불러 null 이지만 그건 테스트 사정이다).
    // 이 단언이 지켜야 하는 성질은 "없다" 가 아니라 **"같지 않다"** 이며, 그건 양쪽 모두에서 성립한다.
    assertThat(identityEvents.first().traceId).isNotEqualTo(registered.traceId)
  }

  @Test
  fun `신원 생성이 영구 실패하면 그 사실도 감사에 남는다`() {
    every { identityProviderPort.createUser(any()) } throws
      IdentityAlreadyExistsException("erin@example.local")
    register("erin@example.local").andExpect(status().isCreated)

    outboxProcessor.processOne()

    val failure =
      auditLogRepository.findAll().single { it.eventType == AuditEventType.IDENTITY_CREATION_FAILED }
    assertThat(failure.reasonCode).isEqualTo("identity_already_exists")
    // 신원 생성에 실패했으므로 sub 가 없다.
    assertThat(failure.targetRef).isNull()
    assertThat(failure.targetEmail).isEqualTo("erin@example.local")
  }

  @Test
  fun `한 사용자의 이력이 시간순으로 이어진다`() {
    // 감사 스트림의 실제 쓰임 — "이 사용자에게 무슨 일이 있었나".
    registerAndActivate("frank@example.local", tosVersion = "v1")
    mockMvc
      .perform(
        post("/iam/profile/consent")
          .with(caller())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"tosVersion":"v2"}"""),
      ).andExpect(status().isOk)

    val timeline = auditLogRepository.findAll().sortedBy { it.id }.map { it.eventType }
    assertThat(timeline).containsExactly(
      AuditEventType.USER_REGISTERED,
      AuditEventType.IDENTITY_CREATED,
      AuditEventType.CONSENT_ACCEPTED,
    )
  }

  @Test
  fun `가입이 실패하면 감사도 남지 않는다 — 같은 트랜잭션`() {
    // 중복 가입은 예외로 끝난다. 그때 감사만 남으면 "실패한 가입" 이 성공처럼 기록된다.
    register("dup@example.local").andExpect(status().isCreated)
    auditLogRepository.deleteAll()

    register("dup@example.local").andExpect(status().isConflict)

    assertThat(auditLogRepository.findAll()).isEmpty()
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────

  private fun registerAndActivate(
    email: String,
    tosVersion: String? = null,
  ) {
    every { identityProviderPort.createUser(any()) } returns UserRef(KEYCLOAK_USER_ID)
    register(email, tosVersion).andExpect(status().isCreated)
    assertThat(outboxProcessor.processOne()).isTrue()
  }

  private fun register(
    email: String,
    tosVersion: String? = null,
  ) = mockMvc.perform(
    post("/iam/register")
      .contentType(MediaType.APPLICATION_JSON)
      .content(
        """{"email":"$email","displayName":"이름","firstName":"이","lastName":"름"""" +
          (tosVersion?.let { ""","tosVersion":"$it"""" } ?: "") + "}",
      ),
  )

  private fun caller(): RequestPostProcessor =
    jwt().jwt { builder -> builder.subject(KEYCLOAK_USER_ID).audience(listOf("unigate-iam")) }

  /** jsonb 는 정규화되어 되읽히므로 문자열이 아니라 **구조로** 비교한다. */
  private fun readDetail(detail: String?): Map<String, Any?> =
    jacksonObjectMapper().readValue(requireNotNull(detail) { "detail 이 비어 있습니다" })

  companion object {
    private const val KEYCLOAK_USER_ID = "kc-user-audit-1"
  }
}
