package me.ramos.unigate.iam.domain.user.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.user.enums.OnboardingState
import me.ramos.unigate.iam.domain.user.exception.UserProfileDomainException

/**
 * 이메일 변경의 **상태 규칙** 단위 테스트.
 *
 * 여기서 고정하는 것은 "확정 값과 요청 값을 따로 둔다" 는 설계가 실제로 지켜지는가다 —
 * 요청 시점에 확정 값이 흔들리지 않고, 실패해도 되돌릴 것이 요청 값 하나뿐인지.
 *
 * 계층: L1(단위). 프레임워크 없이 돈다.
 */
class EmailChangeTest :
  BehaviorSpec({
    fun activeProfile(email: String = "carol@example.local") =
      UserProfile.restore(
        email = email,
        userRef = UserRef("kc-1"),
        onboardingState = OnboardingState.ACTIVE,
        displayName = "carol",
        locale = "ko-KR",
        consent = null,
      )

    given("활성 프로필") {
      `when`("이메일 변경을 요청하면") {
        val profile = activeProfile()
        profile.requestEmailChange("new@example.local")

        then("확정 값은 그대로다 — 아직 Keycloak 이 모르기 때문") {
          profile.email shouldBe "carol@example.local"
        }

        then("요청 값만 세워진다") {
          profile.pendingEmail shouldBe "new@example.local"
        }
      }

      `when`("반영에 성공해 확정하면") {
        val profile = activeProfile()
        profile.requestEmailChange("new@example.local")
        profile.applyEmailChange()

        then("요청 값이 확정 값으로 승격되고 대기가 비워진다") {
          profile.email shouldBe "new@example.local"
          profile.pendingEmail shouldBe null
        }
      }

      `when`("확정을 두 번 하면 (outbox 는 최소 1회 실행이라 실재한다)") {
        val profile = activeProfile()
        profile.requestEmailChange("new@example.local")
        profile.applyEmailChange()

        then("두 번째는 아무 일도 하지 않는다 — 성공 처리 도중에 실패하면 안 된다") {
          profile.applyEmailChange()
          profile.email shouldBe "new@example.local"
          profile.pendingEmail shouldBe null
        }
      }

      `when`("영구 실패로 보상하면") {
        val profile = activeProfile()
        profile.requestEmailChange("new@example.local")
        profile.cancelEmailChange()

        then("요청만 사라지고 확정 값은 처음 그대로다") {
          profile.email shouldBe "carol@example.local"
          profile.pendingEmail shouldBe null
        }

        then("보상은 멱등하다") {
          profile.cancelEmailChange()
          profile.pendingEmail shouldBe null
        }
      }

      `when`("보상 뒤 다시 요청하면") {
        val profile = activeProfile()
        profile.requestEmailChange("first@example.local")
        profile.cancelEmailChange()

        then("막히지 않는다 — 보상을 빠뜨리면 여기가 영영 막힌다") {
          profile.requestEmailChange("second@example.local")
          profile.pendingEmail shouldBe "second@example.local"
        }
      }
    }

    given("이미 변경이 진행 중인 프로필") {
      val profile = activeProfile()
      profile.requestEmailChange("first@example.local")

      `when`("또 변경을 요청하면") {
        then("거절한다 — 두 지시의 처리 순서를 보장할 수 없다") {
          shouldThrow<UserProfileDomainException.EmailChangeInProgress> {
            profile.requestEmailChange("second@example.local")
          }
        }

        then("앞선 요청은 그대로 살아 있다") {
          profile.pendingEmail shouldBe "first@example.local"
        }
      }
    }

    given("현재와 같은 이메일") {
      `when`("변경을 요청하면") {
        then("거절한다 — 외부 시스템을 두드릴 이유가 없다") {
          shouldThrow<UserProfileDomainException.EmailUnchanged> {
            activeProfile().requestEmailChange("carol@example.local")
          }
        }
      }
    }

    given("아직 Keycloak 신원이 없는 프로필") {
      val pending =
        UserProfile.register(email = "pending@example.local", displayName = "pending")

      `when`("이메일 변경을 요청하면") {
        then("거절한다 — 바꿀 대상이 아직 없다(가입 재시도의 몫)") {
          shouldThrow<UserProfileDomainException.IdentityNotReady> {
            pending.requestEmailChange("new@example.local")
          }
        }
      }
    }

    given("복원") {
      `when`("대기 값이 확정 값과 같은 상태를 복원하려 하면") {
        then("불변식이 막는다 — '진행 중' 표시가 거짓이 되기 때문") {
          shouldThrow<IllegalArgumentException> {
            UserProfile.restore(
              email = "same@example.local",
              pendingEmail = "same@example.local",
              userRef = UserRef("kc-1"),
              onboardingState = OnboardingState.ACTIVE,
              displayName = "carol",
              locale = "ko-KR",
              consent = null,
            )
          }
        }
      }
    }
  })
