package me.ramos.unigate.iam.integration

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import me.ramos.unigate.iam.adapter.jpaOut.repository.OutboxRecordJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.UserProfileJpaRepository
import me.ramos.unigate.iam.application.outbox.service.OutboxProcessor
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderPort
import me.ramos.unigate.iam.application.user.port.outbound.ProfileConcurrentlyModifiedException
import me.ramos.unigate.iam.application.user.port.outbound.UserProfileRepositoryPort
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 프로필 **동시 수정** 통합 테스트 — 실제 PostgreSQL (Phase 8 미해결 6번).
 *
 * ## 왜 이 테스트가 필요한가
 * 낙관적 락은 "`@Version` 을 붙였다" 로 끝나지 않는다. 실제로 막히려면 **조회한 트랜잭션이 그
 * version 을 들고 있다가 UPDATE 의 WHERE 절에 실어야** 한다. 그 전제는 어댑터의 구현
 * (같은 트랜잭션에서 재조회 → 영속성 컨텍스트가 로드 시점 version 반환)에 달려 있고,
 * **단위 테스트로는 확인할 수 없다** — mock 저장소에는 version 도 영속성 컨텍스트도 없다.
 *
 * ## 경합을 결정적으로 만드는 방법
 * 스레드 두 개를 동시에 던지고 운에 맡기면 테스트가 간헐 실패한다(그러면 아무도 안 믿는다).
 * 대신 **순서를 고정**한다:
 *
 * ```
 * 트랜잭션 A: 프로필 로드 (version = N)
 *            └─ 트랜잭션 B (별도 스레드): 로드 → 수정 → 커밋 (version = N+1)
 *            도메인 변경 → save    ← 여기서 UPDATE ... WHERE version = N 이 0 행을 맞춘다
 * ```
 *
 * B 를 **별도 스레드**에서 도는 이유는 트랜잭션이 스레드에 바인딩되기 때문이다. 같은 스레드에서
 * `TransactionTemplate` 을 다시 쓰면 A 에 **참여**해버려 경합이 성립하지 않는다.
 *
 * Keycloak 만 mock 이다. DB·트랜잭션·워커는 전부 진짜다.
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
  ],
)
class ProfileConcurrencyIntegrationTest {
  @Autowired
  private lateinit var mockMvc: MockMvc

  @Autowired
  private lateinit var userProfileRepository: UserProfileJpaRepository

  @Autowired
  private lateinit var outboxRepository: OutboxRecordJpaRepository

  @Autowired
  private lateinit var outboxProcessor: OutboxProcessor

  @Autowired
  private lateinit var userProfilePort: UserProfileRepositoryPort

  @Autowired
  private lateinit var transactionManager: PlatformTransactionManager

  @MockkBean
  private lateinit var identityProviderPort: IdentityProviderPort

  private val txTemplate: TransactionTemplate by lazy { TransactionTemplate(transactionManager) }

  @BeforeEach
  fun cleanUp() {
    outboxRepository.deleteAll()
    userProfileRepository.deleteAll()
  }

  /**
   * `@Version` 컬럼이 **실제로 움직이는지** 먼저 고정한다.
   *
   * 이게 없으면 아래 충돌 테스트가 통과해도 "락이 동작한 것" 인지 "다른 이유로 실패한 것" 인지
   * 구분되지 않는다. 매핑을 빠뜨려 version 이 늘 0 이면 `WHERE version = 0` 이 항상 맞아
   * **락이 조용히 무력화**된다 — 그때도 테이블에는 컬럼이 멀쩡히 있다.
   */
  @Test
  fun `프로필을 고칠 때마다 version 이 올라간다`() {
    registerAndActivate("version@example.local")
    val initial = userProfileRepository.findByUserRef(KEYCLOAK_USER_ID)!!.version

    updateDisplayName("첫 번째")
    val afterFirst = userProfileRepository.findByUserRef(KEYCLOAK_USER_ID)!!.version
    updateDisplayName("두 번째")
    val afterSecond = userProfileRepository.findByUserRef(KEYCLOAK_USER_ID)!!.version

    assertThat(afterFirst).isGreaterThan(initial)
    assertThat(afterSecond).isGreaterThan(afterFirst)
  }

  /**
   * **핵심 테스트 — lost update 가 실제로 막힌다.**
   *
   * 락이 없던 시절 이 상황의 결과는 "예외 없이 B 의 변경이 사라진다" 였다. 에러도 로그도 남지
   * 않아서, 사용자는 "분명 바꿨는데 안 바뀌어 있다" 만 겪는다. 그 조용한 실패를 **시끄러운
   * 실패**로 바꾼 것이 이 변경의 전부다.
   */
  @Test
  fun `먼저 저장된 변경을 나중 트랜잭션이 덮지 못한다`() {
    registerAndActivate("conflict@example.local")

    assertThatThrownBy {
      txTemplate.execute {
        // A: 이 시점의 version 을 영속성 컨텍스트가 들고 있게 된다.
        val profileA = userProfilePort.findByUserRef(UserRef(KEYCLOAK_USER_ID))!!

        // B: 완전히 별개의 트랜잭션이 먼저 끼어들어 커밋한다.
        inSeparateTransaction {
          val profileB = userProfilePort.findByUserRef(UserRef(KEYCLOAK_USER_ID))!!
          profileB.changeLocale("en-US")
          userProfilePort.save(profileB)
        }

        profileA.changeDisplayName("나중에 도착한 변경")
        userProfilePort.save(profileA)
      }
    }.isInstanceOf(ProfileConcurrentlyModifiedException::class.java)

    // B 의 변경은 살아 있고, A 의 변경은 반영되지 않았다.
    val stored = userProfileRepository.findByUserRef(KEYCLOAK_USER_ID)!!
    assertThat(stored.locale).isEqualTo("en-US")
    assertThat(stored.displayName).isNotEqualTo("나중에 도착한 변경")
  }

  /**
   * **도메인 검사가 경합으로 뚫리는 경로 — 락이 그 최종 방어선이다.**
   *
   * `UserProfile.requestEmailChange` 는 "진행 중인 변경이 있으면 거절" 한다. 하지만 그 검사는
   * **읽은 시점의 `pendingEmail`** 을 본다. 두 요청이 동시에 `null` 을 읽으면 **둘 다 통과**하고,
   * outbox 지시가 두 개 만들어진다. 도메인 KDoc 이 "순서를 보장할 수 없으니 동시에 두 개를
   * 만들지 않는다" 고 적어둔 바로 그 상태가 성립해버린다.
   *
   * 여기서 확인하는 것은 **도메인 검사를 통과한 뒤에도 저장이 막힌다**는 것이다.
   */
  @Test
  fun `이메일 변경 요청이 겹치면 지시가 두 개 만들어지지 않는다`() {
    registerAndActivate("race@example.local")
    outboxRepository.deleteAll() // 가입 지시는 이미 처리됐다. 여기서부터 세기 위해 비운다.

    assertThatThrownBy {
      txTemplate.execute {
        val profileA = userProfilePort.findByUserRef(UserRef(KEYCLOAK_USER_ID))!!

        inSeparateTransaction {
          mockMvc
            .perform(
              post("/iam/profile/email-change")
                .with(callerJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"newEmail":"first@example.local"}"""),
            ).andExpect(status().isAccepted)
        }

        // ⚠️ 통과한다 — A 가 읽은 스냅샷에는 pendingEmail 이 없다. 도메인은 잘못이 없다.
        profileA.requestEmailChange("second@example.local")
        userProfilePort.save(profileA)
      }
    }.isInstanceOf(ProfileConcurrentlyModifiedException::class.java)

    // 지시는 **하나뿐**이고, 확정된 요청도 먼저 커밋된 쪽이다.
    assertThat(outboxRepository.findAll()).hasSize(1)
    assertThat(userProfileRepository.findByUserRef(KEYCLOAK_USER_ID)!!.pendingEmail)
      .isEqualTo("first@example.local")
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────

  /**
   * 호출 스레드의 트랜잭션과 **무관한** 트랜잭션에서 실행한다.
   *
   * 같은 스레드로 하면 `TransactionTemplate` 이 진행 중인 트랜잭션에 참여해(PROPAGATION_REQUIRED)
   * 경합이 성립하지 않는다 — 같은 영속성 컨텍스트를 공유하므로 version 도 어긋나지 않는다.
   */
  private fun inSeparateTransaction(block: () -> Unit) {
    val executor = Executors.newSingleThreadExecutor()
    try {
      executor
        .submit { txTemplate.execute { block() } }
        .get(10, TimeUnit.SECONDS)
    } finally {
      executor.shutdown()
    }
  }

  private fun registerAndActivate(email: String) {
    every { identityProviderPort.createUser(any()) } returns UserRef(KEYCLOAK_USER_ID)
    mockMvc
      .perform(
        post("/iam/register")
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"email":"$email","displayName":"이름","firstName":"이","lastName":"름"}"""),
      ).andExpect(status().isCreated)
    assertThat(outboxProcessor.processOne()).isTrue()
  }

  private fun updateDisplayName(name: String) {
    mockMvc
      .perform(
        patch("/iam/profile")
          .with(callerJwt())
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"displayName":"$name"}"""),
      ).andExpect(status().isOk)
  }

  private fun callerJwt(): RequestPostProcessor =
    jwt().jwt { builder -> builder.subject(KEYCLOAK_USER_ID).audience(listOf("unigate-iam")) }

  companion object {
    private const val KEYCLOAK_USER_ID = "kc-user-concurrency-1"
  }
}
