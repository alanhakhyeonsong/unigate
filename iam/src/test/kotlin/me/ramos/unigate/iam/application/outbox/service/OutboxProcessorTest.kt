package me.ramos.unigate.iam.application.outbox.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import me.ramos.unigate.iam.adapter.jacksonOut.JacksonPayloadSerializer
import me.ramos.unigate.iam.application.outbox.model.OutboxEventType
import me.ramos.unigate.iam.application.outbox.model.OutboxRecord
import me.ramos.unigate.iam.application.outbox.model.OutboxStatus
import me.ramos.unigate.iam.application.outbox.port.outbound.OutboxPort
import me.ramos.unigate.iam.application.user.port.outbound.IdentityAlreadyExistsException
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderPort
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderUnavailableException
import me.ramos.unigate.iam.application.user.port.outbound.UserProfileRepositoryPort
import me.ramos.unigate.iam.domain.audit.enums.AuditEventType
import me.ramos.unigate.iam.domain.audit.model.AuditEvent
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.user.enums.OnboardingState
import me.ramos.unigate.iam.domain.user.model.UserProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * outbox 워커의 **예외 분류** 테스트.
 *
 * 이 분류가 틀리면 두 가지로 망가진다.
 * - 영구 실패를 재시도로 분류 → 고칠 수 없는 일을 10번 반복하며 로그만 더럽힌다
 * - 일시 실패를 영구로 분류 → 잠깐의 네트워크 장애로 가입이 **영영 실패**한다
 */
class OutboxProcessorTest {
  private val outboxPort = mockk<OutboxPort>(relaxed = true)
  private val identityProviderPort = mockk<IdentityProviderPort>()
  private val userProfileRepository = mockk<UserProfileRepositoryPort>(relaxed = true)
  private val serializer = JacksonPayloadSerializer(ObjectMapper().registerKotlinModule())
  private val clock = Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC)

  private val recordAuditEventOutPort =
    mockk<me.ramos.unigate.iam.application.audit.port.outbound.RecordAuditEventOutPort>(relaxed = true)

  private val tenantRepository =
    mockk<me.ramos.unigate.iam.application.tenant.port.outbound.TenantRepositoryPort>(relaxed = true)

  private val circuit = OutboxCircuit(clock)

  private val processor =
    OutboxProcessor(
      outboxPort,
      identityProviderPort,
      userProfileRepository,
      serializer,
      recordAuditEventOutPort,
      tenantRepository,
      circuit,
      clock,
    )

  private val payload = """{"email":"alice@example.local","firstName":"alice","lastName":"tester"}"""

  private fun pendingRecord() =
    OutboxRecord(
      id = 1L,
      eventType = OutboxEventType.CREATE_KEYCLOAK_USER,
      payload = payload,
      status = OutboxStatus.PENDING,
      attempts = 0,
      nextAttemptAt = Instant.parse("2026-07-26T00:00:00Z"),
      lastError = null,
    )

  /**
   * **회귀 테스트 — 영구 실패 보상이 이벤트 타입을 가려야 한다.**
   *
   * P9b 가 고친 무한 재시도 루프는 "미분류 예외 → 롤백 → attempts 그대로" 였다. 그 수정은
   * 가입(CREATE_KEYCLOAK_USER) 경로만 안전하게 만들었다 — 영구 실패 시 실행되는 보상 핸들러가
   * payload 를 **항상 가입 payload 로 역직렬화**했기 때문이다.
   *
   * 그룹 이벤트가 미분류 예외로 실패하면 보상 핸들러 안에서 역직렬화가 터지고, 그 예외가
   * 트랜잭션을 롤백시켜 **같은 루프가 되살아난다.** 레코드는 DEAD 로 확정돼야 한다.
   */
  @Test
  fun `그룹 이벤트가 영구 실패해도 DEAD 로 확정된다 — 보상 핸들러에서 터지지 않는다`() {
    val groupRecord =
      OutboxRecord(
        id = 7L,
        eventType = OutboxEventType.CREATE_KEYCLOAK_GROUP,
        payload = """{"tenantId":"acme"}""",
        status = OutboxStatus.PENDING,
        attempts = 0,
        nextAttemptAt = Instant.parse("2026-07-26T00:00:00Z"),
        lastError = null,
      )
    every { outboxPort.claimNext(any()) } returns groupRecord
    // 미분류 실패를 만든다(재시도로 낫지 않는 버그성 실패).
    every { identityProviderPort.createTenantGroup(any()) } throws IllegalStateException("boom")

    processor.processOne()

    val saved = slot<OutboxRecord>()
    verify { outboxPort.update(capture(saved)) }
    assertThat(saved.captured.status).isEqualTo(OutboxStatus.DEAD)
  }

  private fun emailChangeRecord() =
    OutboxRecord(
      id = 9L,
      eventType = OutboxEventType.UPDATE_KEYCLOAK_EMAIL,
      payload = """{"userRef":"kc-1","newEmail":"new@example.local"}""",
      status = OutboxStatus.PENDING,
      attempts = 0,
      nextAttemptAt = Instant.parse("2026-07-26T00:00:00Z"),
      lastError = null,
    )

  private fun activeProfileWithPendingEmail(): UserProfile {
    val profile =
      UserProfile.restore(
        email = "old@example.local",
        userRef = UserRef("kc-1"),
        onboardingState = OnboardingState.ACTIVE,
        displayName = "carol",
        locale = "ko-KR",
        consent = null,
      )
    profile.requestEmailChange("new@example.local")
    return profile
  }

  @Test
  fun `이메일 변경에 성공하면 요청 값이 확정 값으로 승격된다`() {
    val profile = activeProfileWithPendingEmail()
    every { outboxPort.claimNext(any()) } returns emailChangeRecord()
    every { identityProviderPort.updateEmail("kc-1", "new@example.local") } returns Unit
    every { userProfileRepository.findByUserRef(UserRef("kc-1")) } returns profile
    every { userProfileRepository.save(any()) } answers { firstArg() }

    processor.processOne()

    assertThat(profile.email).isEqualTo("new@example.local")
    assertThat(profile.pendingEmail).isNull()
  }

  @Test
  fun `이메일 변경이 확정되면 EMAIL_CHANGED 를 전후 값과 함께 남긴다`() {
    val profile = activeProfileWithPendingEmail()
    every { outboxPort.claimNext(any()) } returns emailChangeRecord()
    every { identityProviderPort.updateEmail(any(), any()) } returns Unit
    every { userProfileRepository.findByUserRef(UserRef("kc-1")) } returns profile
    every { userProfileRepository.save(any()) } answers { firstArg() }

    processor.processOne()

    val event = slot<AuditEvent>()
    verify { recordAuditEventOutPort.record(capture(event)) }
    assertThat(event.captured.type).isEqualTo(AuditEventType.EMAIL_CHANGED)
    assertThat(event.captured.detail).containsEntry("before", "old@example.local")
    assertThat(event.captured.detail).containsEntry("after", "new@example.local")
  }

  /**
   * **보상 테스트.** 이걸 빠뜨리면 `pendingEmail` 이 영원히 남아 도메인이 다음 변경 요청을
   * 계속 거절한다 — 사용자는 "한 번 실패한 뒤로 영영 못 바꾸는" 상태가 된다.
   */
  @Test
  fun `이메일이 남의 것이면 요청을 취소한다 — 확정 값은 건드리지 않는다`() {
    val profile = activeProfileWithPendingEmail()
    every { outboxPort.claimNext(any()) } returns emailChangeRecord()
    every { identityProviderPort.updateEmail(any(), any()) } throws
      IdentityAlreadyExistsException("new@example.local")
    every { userProfileRepository.findByUserRef(UserRef("kc-1")) } returns profile
    every { userProfileRepository.save(any()) } answers { firstArg() }

    processor.processOne()

    assertThat(profile.pendingEmail).isNull()
    assertThat(profile.email).isEqualTo("old@example.local")

    val saved = slot<OutboxRecord>()
    verify { outboxPort.update(capture(saved)) }
    assertThat(saved.captured.status).isEqualTo(OutboxStatus.DEAD)
  }

  @Test
  fun `보상하면 EMAIL_CHANGE_FAILED 를 남긴다 — 사용자에게 알릴 근거다`() {
    val profile = activeProfileWithPendingEmail()
    every { outboxPort.claimNext(any()) } returns emailChangeRecord()
    every { identityProviderPort.updateEmail(any(), any()) } throws
      IdentityAlreadyExistsException("new@example.local")
    every { userProfileRepository.findByUserRef(UserRef("kc-1")) } returns profile
    every { userProfileRepository.save(any()) } answers { firstArg() }

    processor.processOne()

    val event = slot<AuditEvent>()
    verify { recordAuditEventOutPort.record(capture(event)) }
    assertThat(event.captured.type).isEqualTo(AuditEventType.EMAIL_CHANGE_FAILED)
    // 확정 값(유지된 주소)을 남기고, 시도했던 값은 detail 에 둔다.
    assertThat(event.captured.targetEmail).isEqualTo("old@example.local")
    assertThat(event.captured.detail).containsEntry("requestedEmail", "new@example.local")
  }

  @Test
  fun `일시 장애면 요청을 취소하지 않는다 — 다음 시도에서 성공할 수 있다`() {
    val profile = activeProfileWithPendingEmail()
    every { outboxPort.claimNext(any()) } returns emailChangeRecord()
    every { identityProviderPort.updateEmail(any(), any()) } throws
      IdentityProviderUnavailableException("연결 실패")
    every { userProfileRepository.findByUserRef(UserRef("kc-1")) } returns profile

    processor.processOne()

    // 보상은 **영구 실패에서만** 일어난다. 재시도 대상에서 취소하면 사용자의 요청이
    // Keycloak 이 잠깐 흔들렸다는 이유로 사라진다.
    assertThat(profile.pendingEmail).isEqualTo("new@example.local")
  }

  @Test
  fun `집을 게 없으면 false 를 돌려줘 스케줄러가 멈춘다`() {
    every { outboxPort.claimNext(any()) } returns null

    assertThat(processor.processOne()).isFalse()
  }

  @Test
  fun `성공하면 COMPLETED 로 바꾸고 프로필에 신원을 채운다`() {
    val profile = UserProfile.register("alice@example.local", "alice")
    every { outboxPort.claimNext(any()) } returns pendingRecord()
    every { identityProviderPort.createUser(any()) } returns UserRef("kc-123")
    every { userProfileRepository.findByEmail("alice@example.local") } returns profile

    val processed = processor.processOne()

    assertThat(processed).isTrue()
    // 신원이 채워지고 ACTIVE 로 전이됐다 — 둘은 반드시 함께 일어나야 한다.
    assertThat(profile.userRef).isEqualTo(UserRef("kc-123"))
    assertThat(profile.onboardingState).isEqualTo(OnboardingState.ACTIVE)

    val saved = slot<OutboxRecord>()
    verify { outboxPort.update(capture(saved)) }
    assertThat(saved.captured.status).isEqualTo(OutboxStatus.COMPLETED)
  }

  @Test
  fun `일시 장애는 재시도 대상으로 남긴다`() {
    every { outboxPort.claimNext(any()) } returns pendingRecord()
    every { identityProviderPort.createUser(any()) } throws
      IdentityProviderUnavailableException("keycloak down")

    processor.processOne()

    val saved = slot<OutboxRecord>()
    verify { outboxPort.update(capture(saved)) }
    // PENDING 을 유지하고 백오프로 밀린다 — 네트워크 장애로 가입이 영영 실패하면 안 된다.
    assertThat(saved.captured.status).isEqualTo(OutboxStatus.PENDING)
    assertThat(saved.captured.attempts).isEqualTo(1)
    assertThat(saved.captured.nextAttemptAt).isAfter(Instant.parse("2026-07-26T00:00:00Z"))
  }

  @Test
  fun `중복 이메일은 즉시 DEAD 로 보내고 프로필도 실패 상태로 옮긴다`() {
    val profile = UserProfile.register("alice@example.local", "alice")
    every { outboxPort.claimNext(any()) } returns pendingRecord()
    every { identityProviderPort.createUser(any()) } throws
      IdentityAlreadyExistsException("alice@example.local")
    every { userProfileRepository.findByEmail("alice@example.local") } returns profile

    processor.processOne()

    // 재시도해도 소용없으므로 즉시 DEAD.
    val saved = slot<OutboxRecord>()
    verify { outboxPort.update(capture(saved)) }
    assertThat(saved.captured.status).isEqualTo(OutboxStatus.DEAD)

    // 사용자에게 알릴 수 있도록 프로필도 실패 상태가 된다.
    assertThat(profile.onboardingState).isEqualTo(OnboardingState.IDENTITY_FAILED)
  }

  // ── Phase 8g: 감사 ────────────────────────────────────────────────────

  @Test
  fun `신원 생성에 성공하면 IDENTITY_CREATED 를 남긴다`() {
    val profile = UserProfile.register("alice@example.local", "alice")
    every { outboxPort.claimNext(any()) } returns pendingRecord()
    every { identityProviderPort.createUser(any()) } returns UserRef("kc-123")
    every { userProfileRepository.findByEmail("alice@example.local") } returns profile
    every { userProfileRepository.save(any()) } answers { firstArg() }

    processor.processOne()

    val audited = slot<AuditEvent>()
    verify { recordAuditEventOutPort.record(capture(audited)) }
    assertThat(audited.captured.type).isEqualTo(AuditEventType.IDENTITY_CREATED)
    // 이 시점에 비로소 Keycloak sub 를 대상으로 쓸 수 있다.
    assertThat(audited.captured.targetRef).isEqualTo("kc-123")
    // 행위자는 사람이 아니라 워커다.
    assertThat(audited.captured.actorRef).isNull()
  }

  @Test
  fun `영구 실패하면 IDENTITY_CREATION_FAILED 를 남긴다`() {
    val profile = UserProfile.register("alice@example.local", "alice")
    every { outboxPort.claimNext(any()) } returns pendingRecord()
    every { identityProviderPort.createUser(any()) } throws
      IdentityAlreadyExistsException("alice@example.local")
    every { userProfileRepository.findByEmail("alice@example.local") } returns profile

    processor.processOne()

    val audited = slot<AuditEvent>()
    verify { recordAuditEventOutPort.record(capture(audited)) }
    assertThat(audited.captured.type).isEqualTo(AuditEventType.IDENTITY_CREATION_FAILED)
    assertThat(audited.captured.reasonCode).isEqualTo("identity_already_exists")
    // 신원 생성에 **실패했으므로** sub 가 없다. 이 사건은 email 로만 가리킬 수 있다.
    assertThat(audited.captured.targetRef).isNull()
    assertThat(audited.captured.targetEmail).isEqualTo("alice@example.local")
  }

  @Test
  fun `재시도 대상 실패는 감사에 남기지 않는다`() {
    // 아직 확정된 사건이 아니다. 남기면 Keycloak 이 잠깐 흔들릴 때마다 같은 사건이 10건씩 쌓여
    // 정작 확정 사건이 묻힌다. 그 관측은 로그·메트릭의 몫이다.
    every { outboxPort.claimNext(any()) } returns pendingRecord()
    every { identityProviderPort.createUser(any()) } throws
      IdentityProviderUnavailableException("keycloak down")

    processor.processOne()

    verify(exactly = 0) { recordAuditEventOutPort.record(any()) }
  }

  @Test
  fun `저장되는 오류 사유에 외부 응답 원문을 넣지 않는다`() {
    every { outboxPort.claimNext(any()) } returns pendingRecord()
    every { identityProviderPort.createUser(any()) } throws
      IdentityProviderUnavailableException("Bearer secret-token-leaked-here")

    processor.processOne()

    val saved = slot<OutboxRecord>()
    verify { outboxPort.update(capture(saved)) }
    // outbox 레코드는 실패 시 며칠씩 DB 에 남는다. 외부 메시지를 그대로 넣으면 토큰이 샐 수 있다.
    assertThat(saved.captured.lastError).isEqualTo("identity_provider_unavailable")
    assertThat(saved.captured.lastError).doesNotContain("secret-token-leaked-here")
  }

  // ── Phase 9b: 미분류 예외와 회로 차단기 ────────────────────────────────

  @Test
  fun `분류되지 않은 예외도 DEAD 로 보낸다 — 무한 재시도 루프를 막는다`() {
    // ⚠️ 이 테스트가 P9b 의 핵심이다.
    //
    // 예전에는 예외를 두 종류만 잡고 나머지는 전파시켰다. 그러면 @Transactional 이 롤백하는데,
    // **롤백에는 클레임과 attempts 증가도 포함**되어 레코드가 PENDING 그대로 남았다.
    // 결과는 5초마다 같은 실패를 영원히 반복 — 재시도 상한도 DEAD 도 닿지 못하는 경로였다.
    //
    // 실제로 그런 경로가 있었다: 아래처럼 프로필 조회가 null 이면
    // `error("outbox 지시에 대응하는 프로필이 없습니다")` 가 터진다.
    every { outboxPort.claimNext(any()) } returns pendingRecord()
    every { identityProviderPort.createUser(any()) } returns UserRef("kc-123")
    every { userProfileRepository.findByEmail("alice@example.local") } returns null

    val processed = processor.processOne()

    // 예외가 밖으로 나가지 않는다 — 나가면 트랜잭션이 롤백되어 원래 문제로 돌아간다.
    assertThat(processed).isTrue()

    val saved = slot<OutboxRecord>()
    verify { outboxPort.update(capture(saved)) }
    assertThat(saved.captured.status).isEqualTo(OutboxStatus.DEAD)
    assertThat(saved.captured.lastError).isEqualTo("unclassified_failure")
    // 원인 추적용 — 클래스명만 남긴다(메시지에는 외부 응답이 섞일 수 있다).
    assertThat(saved.captured.lastExceptionClass).contains("IllegalStateException")
  }

  @Test
  fun `미분류 실패도 사용자에게 알릴 수 있게 프로필을 실패 상태로 옮긴다`() {
    // 원인이 무엇이든 사용자 입장에서는 "가입했는데 아무 일도 안 일어남" 이다.
    // 프로필이 PENDING_IDENTITY 에 머무르면 알릴 방법이 없다.
    val profile = UserProfile.register("alice@example.local", "alice")
    every { outboxPort.claimNext(any()) } returns pendingRecord()
    every { identityProviderPort.createUser(any()) } throws RuntimeException("예상 못 한 무언가")
    every { userProfileRepository.findByEmail("alice@example.local") } returns profile

    processor.processOne()

    assertThat(profile.onboardingState).isEqualTo(OnboardingState.IDENTITY_FAILED)
    val audited = slot<AuditEvent>()
    verify { recordAuditEventOutPort.record(capture(audited)) }
    assertThat(audited.captured.reasonCode).isEqualTo("unclassified_failure")
  }

  @Test
  fun `회로가 열리면 레코드를 집지 않는다 — attempts 를 보호한다`() {
    // 외부 장애가 길어질 때 클레임을 계속하면, 잘못이 없는 정상 레코드의 attempts 가 소진되어
    // DEAD 로 떨어진다. 그래서 클레임 **전에** 회로를 묻는다.
    every { outboxPort.claimNext(any()) } returns pendingRecord()
    every { identityProviderPort.createUser(any()) } throws
      IdentityProviderUnavailableException("keycloak down")

    // 연속 실패로 회로를 연다.
    repeat(OutboxCircuit.FAILURE_THRESHOLD) { processor.processOne() }

    val claimsBefore = OutboxCircuit.FAILURE_THRESHOLD
    val processed = processor.processOne()

    assertThat(processed).isFalse()
    // 회로가 열린 뒤로는 claimNext 가 더 불리지 않았다.
    verify(exactly = claimsBefore) { outboxPort.claimNext(any()) }
  }

  @Test
  fun `영구 실패는 회로를 열지 않는다 — 외부가 아니라 그 레코드의 문제다`() {
    // 중복 이메일 같은 실패가 회로를 열면, 문제 없는 다른 레코드들까지 멈춰 선다.
    every { outboxPort.claimNext(any()) } returns pendingRecord()
    every { identityProviderPort.createUser(any()) } throws
      IdentityAlreadyExistsException("alice@example.local")
    // 레코드마다 다른 프로필이다(같은 객체를 재사용하면 상태 전이가 겹친다).
    every { userProfileRepository.findByEmail("alice@example.local") } answers {
      UserProfile.register("alice@example.local", "alice")
    }

    repeat(OutboxCircuit.FAILURE_THRESHOLD + 2) { processor.processOne() }

    // 여전히 집는다.
    assertThat(processor.processOne()).isTrue()
  }

  @Test
  fun `이미 실패 상태인 프로필에 다시 실패 처리를 해도 터지지 않는다`() {
    // outbox 는 최소 1회 실행이라 같은 지시가 두 번 올 수 있고, 운영자가 DEAD 를 재처리하면
    // 확실히 두 번 온다. 그때 IDENTITY_FAILED → IDENTITY_FAILED 전이를 시도하면 도메인이
    // 거부해 **실패 처리 도중에 또 실패**한다.
    val profile = UserProfile.register("alice@example.local", "alice")
    profile.failIdentity()

    every { outboxPort.claimNext(any()) } returns pendingRecord()
    every { identityProviderPort.createUser(any()) } throws
      IdentityAlreadyExistsException("alice@example.local")
    every { userProfileRepository.findByEmail("alice@example.local") } returns profile

    // 예외가 밖으로 나가지 않는다.
    assertThat(processor.processOne()).isTrue()

    val saved = slot<OutboxRecord>()
    verify { outboxPort.update(capture(saved)) }
    // 미분류(unclassified_failure)가 아니라 원래 사유로 기록돼야 한다.
    assertThat(saved.captured.lastError).isEqualTo("identity_already_exists")
  }
}
