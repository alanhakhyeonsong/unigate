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

  private val processor =
    OutboxProcessor(outboxPort, identityProviderPort, userProfileRepository, serializer, clock)

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
}
