package me.ramos.unigate.iam.integration

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import me.ramos.unigate.iam.adapter.jpaOut.repository.AuditLogJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.OutboxRecordJpaRepository
import me.ramos.unigate.iam.adapter.jpaOut.repository.UserProfileJpaRepository
import me.ramos.unigate.iam.application.outbox.model.OutboxStatus
import me.ramos.unigate.iam.application.outbox.service.OutboxProcessor
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderPort
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.UnexpectedRollbackException

/**
 * **감사 fail-closed 의 대가를 실제로 재현한다** — 실제 PostgreSQL (Phase 8 미해결 2번).
 *
 * ## 무엇을 확인하려는 것인가
 * P8g 의 결정 D3 은 "감사 실패 → 업무 롤백"(fail-closed)이었다. 근거는 *"도메인 변경이 기록 없이
 * 일어나는 편이 더 나쁘다"* 였고, 그 대가는 **감사 저장소가 죽으면 가입도 멈춘다** 는 것이다.
 *
 * 그 대가는 지금까지 **문장으로만** 있었다. `@Transactional` 하나에 의존하는 성질이라, 누군가
 * 감사 호출을 `try/catch` 로 감싸거나 별도 트랜잭션으로 빼면 조용히 fail-open 으로 바뀐다 —
 * 그때도 모든 기존 테스트는 통과한다(정상 경로에서는 차이가 없으므로).
 *
 * ## 장애를 어떻게 만드나
 * 어댑터를 mock 으로 갈아끼우면 "포트가 예외를 던지는 상황" 은 되지만 **테이블 장애의 재현은
 * 아니다.** 여기서 묻는 것은 트랜잭션 경계가 진짜 DB 위에서 성립하는가이므로, `audit_log` 에
 * INSERT 를 거부하는 트리거를 걸어 **DB 쪽에서** 실패시킨다.
 *
 * ⚠️ 트리거를 남기면 이후 모든 테스트가 깨진다. [dropFailingTrigger] 를 `@BeforeEach`·`@AfterEach`
 * 양쪽에서 부르는 이유다 — 앞선 실행이 중간에 죽었을 수도 있다.
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
class AuditFailClosedIntegrationTest {
  @Autowired
  private lateinit var mockMvc: MockMvc

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  @Autowired
  private lateinit var userProfileRepository: UserProfileJpaRepository

  @Autowired
  private lateinit var outboxRepository: OutboxRecordJpaRepository

  @Autowired
  private lateinit var auditLogRepository: AuditLogJpaRepository

  @Autowired
  private lateinit var outboxProcessor: OutboxProcessor

  @MockkBean
  private lateinit var identityProviderPort: IdentityProviderPort

  @BeforeEach
  fun cleanUp() {
    dropFailingTrigger()
    outboxRepository.deleteAll()
    userProfileRepository.deleteAll()
    auditLogRepository.deleteAll()
  }

  @AfterEach
  fun restore() {
    dropFailingTrigger()
  }

  /**
   * **fail-closed 의 본체.** 감사를 못 남기면 가입도 없던 일이 된다.
   *
   * 확인 대상이 셋인 것이 중요하다 — 프로필만 보면 "저장이 안 됐다" 까지만 알 수 있고, outbox 지시가
   * 함께 사라졌는지는 알 수 없다. 지시만 남으면 워커가 **존재하지 않는 프로필**을 채우려 든다.
   */
  @Test
  fun `감사 저장소가 죽으면 가입도 롤백된다 — 프로필도 outbox 지시도 남지 않는다`() {
    createFailingTrigger()

    val thrown = catchThrowable { register("fail-closed@example.local") }

    // ⚠️ "실패했다" 만 보면 약하다 — DB 가 아예 안 붙어도 통과한다. **감사 때문에** 실패했음을
    // 근본 원인까지 내려가 확인한다.
    assertThat(thrown)
      .rootCause()
      .hasMessageContaining("audit storage unavailable")

    assertThat(userProfileRepository.findByEmail("fail-closed@example.local")).isNull()
    assertThat(outboxRepository.findAll()).isEmpty()
    assertThat(auditLogRepository.findAll()).isEmpty()
  }

  /**
   * **대조군.** 위 테스트만 있으면 "감사 때문에 막혔다" 와 "이 환경에서는 원래 가입이 안 된다" 가
   * 구분되지 않는다. 트리거를 떼면 같은 요청이 통과해야 한다.
   *
   * P9f 에서 헤더 하나만 관측했다가 "제거됨" 과 "프로브가 못 읽음" 을 구분하지 못했던 것과 같은
   * 이유로 둔다.
   */
  @Test
  fun `감사 저장소가 정상이면 같은 가입이 통과한다 — 대조군`() {
    register("fail-open-control@example.local")

    assertThat(userProfileRepository.findByEmail("fail-open-control@example.local")).isNotNull()
    assertThat(outboxRepository.findAll()).hasSize(1)
    assertThat(auditLogRepository.findAll()).hasSize(1)
  }

  /**
   * **워커 쪽 대가** — fail-closed 는 HTTP 요청만의 이야기가 아니다.
   *
   * 워커의 성공 처리에도 감사(`IDENTITY_CREATED`)가 같은 트랜잭션에 들어 있다. 감사가 실패하면
   * 그 트랜잭션이 롤백되는데, **롤백에는 클레임과 attempts 증가도 포함된다**
   * (`OutboxProcessor` KDoc 의 "남아 있는 한계" 가 말하는 경로다).
   *
   * 즉 감사 저장소 장애 동안 워커는 같은 레코드를 계속 다시 집는다. 이것이 무한 루프처럼 보이지만
   * **의도된 동작에 가깝다** — DB 가 아픈 상황에서 레코드를 DEAD 로 확정하는 편이 더 위험하다.
   * 여기서 고정하는 것은 "그래서 레코드가 죽지 않고 남아 있다" 는 성질이다.
   *
   * ## ⚠️ 실측이 알려준 것 — **터져 나오는 예외에 원인이 없다**
   * 감사 실패는 `attempt()` 의 미분류 `catch` 에 **먼저 잡힌다.** 그래서 워커는 이것을
   * `unclassified_failure` 로 분류하고 레코드를 DEAD 로 확정하려 시도한다. 하지만 그 시점의
   * 트랜잭션은 DB 예외로 이미 rollback-only 로 마킹돼 있어, 그 `update` 도 커밋되지 못한다.
   *
   * 최종적으로 밖으로 나오는 것은 원래의 SQL 예외가 아니라 다음 하나뿐이다:
   *
   * ```
   * org.springframework.transaction.UnexpectedRollbackException:
   *   Transaction silently rolled back because it has been marked as rollback-only
   * ```
   *
   * **cause 체인이 비어 있다** — 이 예외만 보고는 감사 때문인지 다른 쓰기 때문인지 알 수 없다.
   * 진짜 원인은 워커가 남긴 `outbox 미분류 실패` 로그에만 있다. 결과적으로 레코드는 죽지 않지만,
   * 장애 조사에서는 예외가 아니라 **로그를 봐야 한다**는 뜻이다.
   *
   * ⚠️ Keycloak 호출은 **이미 나갔다.** 롤백되는 것은 우리 DB 뿐이므로 재시도는 그 멱등성에
   * 기댄다(`createUser` 는 조회 → 생성 → 409 면 재조회).
   */
  @Test
  fun `감사 저장소가 죽으면 워커도 진행하지 못하고 지시가 그대로 남는다`() {
    register("worker-fail-closed@example.local")
    every { identityProviderPort.createUser(any()) } returns UserRef("kc-audit-fail-1")

    createFailingTrigger()
    val thrown = catchThrowable { outboxProcessor.processOne() }

    // 원래의 SQL 예외가 아니라 이것이 나온다(위 KDoc). 원인은 예외가 아니라 로그에 있다.
    assertThat(thrown).isInstanceOf(UnexpectedRollbackException::class.java)

    // 지시가 살아 있다 — DEAD 로 확정되지 않았다.
    val record = outboxRepository.findAll().single()
    assertThat(record.status).isEqualTo(OutboxStatus.PENDING)
    assertThat(record.attempts).isEqualTo(0)

    // 프로필도 신원이 채워지지 않은 채 그대로다(전이가 롤백됐다).
    assertThat(userProfileRepository.findByEmail("worker-fail-closed@example.local")!!.userRef).isNull()
  }

  /**
   * **워커 쪽 대조군.** 트리거를 떼면 같은 지시가 정상 처리된다 — 위 테스트의 PENDING 이
   * "감사 때문에 막힌 것" 이지 "이 환경에서 워커가 원래 안 도는 것" 이 아님을 가른다.
   */
  @Test
  fun `감사 저장소가 정상이면 워커가 지시를 완료한다 — 대조군`() {
    register("worker-control@example.local")
    every { identityProviderPort.createUser(any()) } returns UserRef("kc-audit-ok-1")

    assertThat(outboxProcessor.processOne()).isTrue()

    assertThat(outboxRepository.findAll().single().status).isEqualTo(OutboxStatus.COMPLETED)
    assertThat(userProfileRepository.findByEmail("worker-control@example.local")!!.userRef)
      .isEqualTo("kc-audit-ok-1")
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────

  /**
   * `audit_log` 로의 INSERT 를 거부한다 — 감사 저장소 장애 시뮬레이션.
   *
   * 테이블을 지우거나 이름을 바꾸는 방법도 있지만, 그러면 실패 시점이 스키마 검증까지 앞당겨지고
   * 복구도 번거롭다. 트리거는 **쓰기만** 막아 실제 장애(디스크 full·권한 회수)에 더 가깝다.
   */
  private fun createFailingTrigger() {
    jdbcTemplate.execute(
      """
      CREATE OR REPLACE FUNCTION unigate_test_fail_audit() RETURNS trigger AS ${'$'}${'$'}
      BEGIN
        RAISE EXCEPTION 'audit storage unavailable (injected by test)';
      END;
      ${'$'}${'$'} LANGUAGE plpgsql;
      """.trimIndent(),
    )
    jdbcTemplate.execute(
      """
      CREATE TRIGGER unigate_test_fail_audit_trigger
      BEFORE INSERT ON audit_log
      FOR EACH ROW EXECUTE FUNCTION unigate_test_fail_audit();
      """.trimIndent(),
    )
  }

  private fun dropFailingTrigger() {
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS unigate_test_fail_audit_trigger ON audit_log")
    jdbcTemplate.execute("DROP FUNCTION IF EXISTS unigate_test_fail_audit()")
  }

  private fun register(email: String) {
    mockMvc.perform(
      post("/iam/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"email":"$email","displayName":"이름","firstName":"이","lastName":"름"}"""),
    )
  }
}
