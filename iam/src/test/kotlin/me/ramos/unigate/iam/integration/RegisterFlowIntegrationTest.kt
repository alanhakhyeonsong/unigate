package me.ramos.unigate.iam.integration

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import me.ramos.unigate.iam.adapter.jpaOut.repository.OutboxRecordJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.UserProfileJpaRepository
import me.ramos.unigate.iam.application.outbox.model.OutboxStatus
import me.ramos.unigate.iam.application.outbox.service.OutboxProcessor
import me.ramos.unigate.iam.application.user.port.outbound.IdentityAlreadyExistsException
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderPort
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.user.enums.OnboardingState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 가입 흐름 **전 구간** 통합 테스트 — HTTP → 트랜잭션 → outbox → 워커.
 *
 * ## 이 테스트가 지키는 것: "프로필과 지시는 같은 커밋"
 * outbox 패턴의 전제가 무너지는 순간은 `RegisterUserService` 에서 `@Transactional` 이 빠질 때다.
 * 그러면 프로필만 저장되고 outbox 지시가 유실되어 그 사용자는 **영원히 `PENDING_IDENTITY`** 가 된다.
 * 단위 테스트로는 잡히지 않는다 — 실제 트랜잭션이 있어야 드러난다.
 *
 * Keycloak 만 mock 으로 대체한다(외부 시스템). DB 는 실제다.
 */
@Tag("testcontainers")
@SpringBootTest(properties = ["unigate.iam.outbox.polling.enabled=false"])
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@TestPropertySource(
  properties = [
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:3000/unigate_iam_test",
    "spring.datasource.username=testuser",
    "spring.datasource.password=testpass",
    "spring.flyway.url=jdbc:postgresql://127.0.0.1:3000/unigate_iam_test",
    "spring.flyway.user=testuser",
    "spring.flyway.password=testpass",
    "unigate.iam.keycloak.server-url=http://localhost:1",
  ],
)
class RegisterFlowIntegrationTest {
  @Autowired
  private lateinit var mockMvc: MockMvc

  @Autowired
  private lateinit var outboxRepository: OutboxRecordJpaRepository

  @Autowired
  private lateinit var userProfileRepository: UserProfileJpaRepository

  @Autowired
  private lateinit var outboxProcessor: OutboxProcessor

  /** 외부 시스템만 대체한다. DB·트랜잭션·워커는 진짜다. */
  @MockkBean
  private lateinit var identityProviderPort: IdentityProviderPort

  @BeforeEach
  fun cleanUp() {
    outboxRepository.deleteAll()
    userProfileRepository.deleteAll()
  }

  @Test
  fun `가입하면 프로필과 outbox 지시가 함께 저장된다`() {
    register("alice@example.local")
      .andExpect(status().isCreated)
      // 신원은 아직 없다 — outbox 라 정상이다.
      .andExpect(jsonPath("$.onboardingState").value("PENDING_IDENTITY"))
      .andExpect(jsonPath("$.userRef").doesNotExist())

    // 같은 커밋에 둘 다 들어갔는가 — 이것이 outbox 의 전부다.
    assertThat(userProfileRepository.findByEmail("alice@example.local")).isNotNull
    assertThat(outboxRepository.findAll()).hasSize(1)
    assertThat(outboxRepository.findAll().first().status).isEqualTo(OutboxStatus.PENDING)
  }

  @Test
  fun `워커가 처리하면 신원이 채워지고 ACTIVE 가 된다`() {
    every { identityProviderPort.createUser(any()) } returns UserRef("kc-user-1")
    register("bob@example.local").andExpect(status().isCreated)

    val processed = outboxProcessor.processOne()

    assertThat(processed).isTrue()
    val profile = userProfileRepository.findByEmail("bob@example.local")!!
    assertThat(profile.userRef).isEqualTo("kc-user-1")
    assertThat(profile.onboardingState).isEqualTo(OnboardingState.ACTIVE)
    assertThat(outboxRepository.findAll().first().status).isEqualTo(OutboxStatus.COMPLETED)
  }

  @Test
  fun `Keycloak 중복이면 프로필이 IDENTITY_FAILED 로 가고 지시는 DEAD 가 된다`() {
    // IAM DB 에는 없지만 Keycloak 에는 이미 있는 경우 — 외부에서 만들어진 사용자.
    // IAM 의 email unique 는 1차 방어일 뿐이고 SoT 는 Keycloak 이라는 것이 여기서 드러난다.
    every { identityProviderPort.createUser(any()) } throws
      IdentityAlreadyExistsException("carol@example.local")
    register("carol@example.local").andExpect(status().isCreated)

    outboxProcessor.processOne()

    val profile = userProfileRepository.findByEmail("carol@example.local")!!
    assertThat(profile.onboardingState).isEqualTo(OnboardingState.IDENTITY_FAILED)
    // 재시도해도 소용없으므로 즉시 DEAD — 10번 반복하지 않는다.
    assertThat(outboxRepository.findAll().first().status).isEqualTo(OutboxStatus.DEAD)
  }

  @Test
  fun `같은 이메일로 다시 가입하면 409 이고 outbox 가 늘지 않는다`() {
    register("dup@example.local").andExpect(status().isCreated)

    register("dup@example.local")
      .andExpect(status().isConflict)
      .andExpect(jsonPath("$.reasonCode").value("email_already_registered"))

    // 실패한 요청이 지시를 남기면 워커가 헛일을 한다.
    assertThat(outboxRepository.findAll()).hasSize(1)
  }

  @Test
  fun `이메일 형식이 틀리면 400 이고 아무것도 저장되지 않는다`() {
    mockMvc
      .perform(
        post("/iam/register")
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"email":"not-an-email","displayName":"x","firstName":"a","lastName":"b"}"""),
      ).andExpect(status().isBadRequest)

    assertThat(userProfileRepository.findAll()).isEmpty()
    assertThat(outboxRepository.findAll()).isEmpty()
  }

  private fun register(email: String) =
    mockMvc.perform(
      post("/iam/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(
          """{"email":"$email","displayName":"tester","firstName":"first","lastName":"last"}""",
        ),
    )

  /**
   * 동시 가입 경합.
   *
   * `RegisterUserService` 는 저장 전에 이메일을 조회한다(check-then-act). 그 조회와 INSERT 사이에
   * 다른 요청이 끼어들면 DB unique 제약이 막는데, 그 예외를 처리하지 않으면 **500** 이 나간다.
   *
   * ⚠️ **이 테스트는 경합을 재현했다고 보장하지 못한다.** 실측에서는 `[201, 409, 409, 409]` 로
   * 사전 조회가 모두 걸려 unique 위반까지 가지 않았다. 그래도 남기는 이유는 두 가지다.
   * - 어떤 타이밍이든 **결과가 하나**여야 한다는 불변식은 검증된다(프로필 1건).
   * - 500 이 새어나오면 여기서 잡힌다.
   *
   * 경합 자체의 방어는 `RegisterController.handleRaceCondition` 이 담당한다.
   */
  @Test
  fun `동시에 같은 이메일로 가입해도 한 건만 성공하고 500 이 나지 않는다`() {
    val threads = 4
    val start = CountDownLatch(1)
    val done = CountDownLatch(threads)
    val statuses = java.util.Collections.synchronizedList(mutableListOf<Int>())
    val pool = Executors.newFixedThreadPool(threads)

    repeat(threads) {
      pool.submit {
        try {
          start.await()
          statuses.add(register("race@example.local").andReturn().response.status)
        } catch (e: Exception) {
          statuses.add(-1)
        } finally {
          done.countDown()
        }
      }
    }
    start.countDown()
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
    pool.shutdown()

    // 정확히 하나만 성공한다.
    assertThat(statuses.count { it == 201 }).isEqualTo(1)
    // 나머지는 전부 409 — 500(서버 오류)이나 -1(예외)이 있으면 안 된다.
    assertThat(statuses.filter { it != 201 }).allMatch { it == 409 }
    // 그리고 데이터는 하나뿐이다.
    assertThat(userProfileRepository.findAll()).hasSize(1)
    assertThat(outboxRepository.findAll()).hasSize(1)
  }
}
