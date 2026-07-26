package me.ramos.unigate.iam.application.user.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ramos.unigate.iam.application.user.dto.AcceptConsentCommand
import me.ramos.unigate.iam.application.user.dto.UpdateMyProfileCommand
import me.ramos.unigate.iam.application.user.policy.ConsentPolicy
import me.ramos.unigate.iam.application.user.port.outbound.UserProfileRepositoryPort
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.user.model.UserProfile
import me.ramos.unigate.iam.domain.user.vo.ConsentRecord
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 프로필 유스케이스 3종 단위 테스트 (Phase 8e).
 *
 * OutPort 만 모킹하고 도메인은 실물을 쓴다(testing skill). 상태 전이·불변식은 도메인이 책임지므로
 * 여기서 검증할 것은 **오케스트레이션** — 무엇을 찾고, 무엇을 저장하고, 언제 거부하는가 — 이다.
 */
class ProfileServiceTest :
  BehaviorSpec({
    val repository = mockk<UserProfileRepositoryPort>()
    val policy = ConsentPolicy(CURRENT_TOS)
    val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    Given("조회 유스케이스") {
      val service = GetMyProfileService(repository, policy)

      When("호출자의 프로필이 있으면") {
        every { repository.findByUserRef(UserRef(CALLER)) } returns activeProfile()
        val result = service.get(CALLER)

        Then("본인 프로필을 돌려준다") {
          result.userRef shouldBe CALLER
          result.email shouldBe "alice@example.local"
          result.onboardingState shouldBe "ACTIVE"
        }
      }

      When("동의 버전이 현재와 같으면") {
        every { repository.findByUserRef(UserRef(CALLER)) } returns activeProfile(tosVersion = CURRENT_TOS)
        val result = service.get(CALLER)

        Then("valid 를 서버가 계산해 true 로 준다") {
          result.consent?.valid shouldBe true
        }
      }

      When("동의 버전이 구버전이면") {
        // 약관이 개정된 상황. 클라이언트가 버전을 비교하지 않아도 알 수 있어야 한다.
        every { repository.findByUserRef(UserRef(CALLER)) } returns activeProfile(tosVersion = "v0")
        val result = service.get(CALLER)

        Then("valid 가 false 다") {
          result.consent?.tosVersion shouldBe "v0"
          result.consent?.valid shouldBe false
        }
      }

      When("토큰은 유효하나 프로필이 없으면") {
        // Keycloak 에 직접 만들어진 사용자. 인증은 됐지만 IAM 도메인에는 없다.
        every { repository.findByUserRef(UserRef(CALLER)) } returns null

        Then("ProfileNotFoundException — 자동 생성하지 않는다") {
          shouldThrow<ProfileNotFoundException> { service.get(CALLER) }
        }
      }
    }

    Given("수정 유스케이스") {
      val service = UpdateMyProfileService(repository, policy)

      When("표시 이름만 보내면") {
        val profile = activeProfile()
        every { repository.findByUserRef(UserRef(CALLER)) } returns profile
        val saved = slot<UserProfile>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        service.update(UpdateMyProfileCommand(userRef = CALLER, displayName = "새 이름"))

        Then("locale 은 건드리지 않는다 — null 은 '변경 안 함'") {
          saved.captured.displayName shouldBe "새 이름"
          saved.captured.locale shouldBe "ko-KR"
        }
      }

      When("두 필드를 모두 보내면") {
        val profile = activeProfile()
        every { repository.findByUserRef(UserRef(CALLER)) } returns profile
        val saved = slot<UserProfile>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        val result = service.update(UpdateMyProfileCommand(CALLER, displayName = "둘 다", locale = "en-US"))

        Then("둘 다 반영하고 저장한다") {
          saved.captured.displayName shouldBe "둘 다"
          saved.captured.locale shouldBe "en-US"
          result.displayName shouldBe "둘 다"
        }
      }

      When("아무 필드도 보내지 않으면") {
        val profile = activeProfile()
        every { repository.findByUserRef(UserRef(CALLER)) } returns profile
        val saved = slot<UserProfile>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        service.update(UpdateMyProfileCommand(userRef = CALLER))

        Then("아무것도 바뀌지 않는다 (no-op 이 에러가 아니다)") {
          saved.captured.displayName shouldBe "Alice"
          saved.captured.locale shouldBe "ko-KR"
        }
      }

      When("빈 표시 이름을 보내면") {
        every { repository.findByUserRef(UserRef(CALLER)) } returns activeProfile()

        Then("도메인 불변식이 막는다 — 유스케이스가 중복 검증하지 않는다") {
          shouldThrow<IllegalArgumentException> {
            service.update(UpdateMyProfileCommand(userRef = CALLER, displayName = "  "))
          }
        }
      }
    }

    Given("약관 동의 유스케이스") {
      val service = AcceptConsentService(repository, policy, clock)

      When("현재 버전에 동의하면") {
        val profile = activeProfile(tosVersion = "v0")
        every { repository.findByUserRef(UserRef(CALLER)) } returns profile
        val saved = slot<UserProfile>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        val result = service.accept(AcceptConsentCommand(CALLER, CURRENT_TOS))

        Then("동의 시각을 서버 시계로 찍는다") {
          saved.captured.consent?.tosVersion shouldBe CURRENT_TOS
          // 클라이언트가 보낸 시각이 아니라 주입된 Clock 의 값이어야 한다.
          saved.captured.consent?.acceptedAt shouldBe NOW
          result.consent?.valid shouldBe true
        }
      }

      When("구버전에 동의하려 하면") {
        // 오래된 화면을 열어둔 채 약관이 개정된 상황.
        Then("거부하고 현재 버전을 알려준다") {
          val e =
            shouldThrow<ConsentVersionMismatchException> {
              service.accept(AcceptConsentCommand(CALLER, "v0"))
            }
          e.current shouldBe CURRENT_TOS
          e.requested shouldBe "v0"
        }
      }

      When("존재하지 않는 버전을 보내면") {
        // 이 검증이 없으면 임의 문자열로 '최신 약관 동의' 상태를 만들 수 있다.
        Then("같은 경로로 거부한다 — 조회조차 하지 않는다") {
          shouldThrow<ConsentVersionMismatchException> {
            service.accept(AcceptConsentCommand(CALLER, "v99"))
          }
        }
      }
    }
  }) {
  companion object {
    private const val CALLER = "11111111-2222-3333-4444-555555555555"
    private const val CURRENT_TOS = "v1"
    private val NOW: Instant = Instant.parse("2026-07-26T00:00:00Z")

    /** `userRef` 로 조회되는 프로필은 반드시 신원이 채워진 상태다. */
    private fun activeProfile(tosVersion: String? = CURRENT_TOS): UserProfile =
      UserProfile.restore(
        email = "alice@example.local",
        userRef = UserRef(CALLER),
        onboardingState = me.ramos.unigate.iam.domain.user.enums.OnboardingState.ACTIVE,
        displayName = "Alice",
        locale = "ko-KR",
        consent = tosVersion?.let { ConsentRecord(tosVersion = it, acceptedAt = NOW) },
      )
  }
}
