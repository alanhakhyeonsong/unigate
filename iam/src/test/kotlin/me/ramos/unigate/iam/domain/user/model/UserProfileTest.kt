package me.ramos.unigate.iam.domain.user.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.user.enums.OnboardingState
import me.ramos.unigate.iam.domain.user.exception.UserProfileDomainException
import me.ramos.unigate.iam.domain.user.vo.ConsentRecord
import java.time.Instant

/**
 * `UserProfile` 테스트 — **outbox 패턴이 도메인에 남긴 흔적**을 검증한다.
 *
 * 핵심은 "신원(userRef) 없이 존재하는 프로필이 정상"이라는 점이다. 동기 방식(Keycloak 먼저)이었다면
 * 없었을 상태이며, 그 대신 Keycloak 고아 사용자 문제를 안았을 것이다
 * (`IAM_PLATFORM_DECISION.md` §6.3 · §16).
 */
class UserProfileTest :
  BehaviorSpec({
    val ref = UserRef("115f2213-2d36-4bf0-a187-b124f7817b7d")

    given("가입 요청으로 만든 프로필") {
      `when`("생성 직후를 보면") {
        val profile = UserProfile.register(email = "alice@example.local", displayName = "alice")

        then("PENDING_IDENTITY 이고 userRef 가 없다 — outbox 라 Keycloak 반영 전이다") {
          profile.onboardingState shouldBe OnboardingState.PENDING_IDENTITY
          profile.userRef.shouldBeNull()
        }
      }

      `when`("워커가 Keycloak 생성에 성공하면") {
        val profile =
          UserProfile.register("alice@example.local", "alice").apply {
            completeIdentity(ref)
          }

        then("ACTIVE 가 되고 userRef 가 채워진다 — 두 변화가 함께 일어난다") {
          profile.onboardingState shouldBe OnboardingState.ACTIVE
          profile.userRef shouldBe ref
        }
      }

      `when`("워커가 실패하면 (이메일 중복 등)") {
        val profile =
          UserProfile.register("alice@example.local", "alice").apply {
            failIdentity()
          }

        then("IDENTITY_FAILED 가 된다 — 중복은 Keycloak 이 SoT 라 이 시점에야 안다") {
          profile.onboardingState shouldBe OnboardingState.IDENTITY_FAILED
        }

        then("정정 후 재시도할 수 있다 — 실패가 종착이면 영영 가입할 수 없다") {
          profile.retryIdentity()
          profile.onboardingState shouldBe OnboardingState.PENDING_IDENTITY
        }
      }
    }

    given("ACTIVE 프로필") {
      val active = UserProfile.register("alice@example.local", "alice").apply { completeIdentity(ref) }

      `when`("다시 상태를 바꾸려 하면") {
        then("거부된다 — ACTIVE 는 종착이다") {
          shouldThrow<UserProfileDomainException.InvalidStateTransition> { active.failIdentity() }
          shouldThrow<UserProfileDomainException.InvalidStateTransition> { active.retryIdentity() }
        }
      }
    }

    given("상태와 신원의 정합성 불변식") {
      `when`("ACTIVE 인데 userRef 없이 복원하려 하면") {
        then("거부된다 — 로그인은 되는데 프로필을 못 찾는 상태를 막는다") {
          shouldThrow<UserProfileDomainException.InconsistentIdentity> {
            UserProfile.restore(
              email = "alice@example.local",
              userRef = null,
              onboardingState = OnboardingState.ACTIVE,
              displayName = "alice",
              locale = "ko-KR",
              consent = null,
            )
          }
        }
      }

      `when`("PENDING_IDENTITY 이고 userRef 가 없으면") {
        then("정상이다 — outbox 의 정상 중간 상태") {
          val restored =
            UserProfile.restore(
              email = "alice@example.local",
              userRef = null,
              onboardingState = OnboardingState.PENDING_IDENTITY,
              displayName = "alice",
              locale = "ko-KR",
              consent = null,
            )
          restored.userRef.shouldBeNull()
        }
      }
    }

    given("약관 동의") {
      val now = Instant.parse("2026-07-26T00:00:00Z")

      `when`("동의 기록이 없으면") {
        val profile = UserProfile.register("alice@example.local", "alice")

        then("유효한 동의가 아니다") {
          profile.hasValidConsent("v2") shouldBe false
        }
      }

      `when`("구버전에만 동의했으면") {
        val profile =
          UserProfile.register("alice@example.local", "alice").apply {
            acceptConsent(ConsentRecord(tosVersion = "v1", acceptedAt = now))
          }

        then("현행 버전에 대해서는 무효다 — boolean 하나로는 부족한 이유") {
          profile.hasValidConsent("v2") shouldBe false
          profile.hasValidConsent("v1") shouldBe true
        }
      }
    }

    given("UserRef") {
      `when`("빈 값이면") {
        then("거부된다 — 참조가 실재하는지는 도메인 관심사다") {
          shouldThrow<IllegalArgumentException> { UserRef("  ") }
        }
      }
    }
  })
