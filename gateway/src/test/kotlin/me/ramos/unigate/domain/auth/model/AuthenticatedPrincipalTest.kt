package me.ramos.unigate.domain.auth.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AuthenticatedPrincipalTest :
  BehaviorSpec({
    given("검증된 인증 주체") {
      val principal =
        AuthenticatedPrincipal(
          subject = "user-123",
          email = "user@example.com",
          roles = listOf("dev", "ops"),
          tenants = listOf("acme"),
          audiences = listOf("solution-a"),
        )
      `when`("필드를 조회하면") {
        then("설정한 값이 그대로 반환된다") {
          principal.subject shouldBe "user-123"
          principal.roles shouldBe listOf("dev", "ops")
          principal.tenants shouldBe listOf("acme")
          principal.audiences shouldBe listOf("solution-a")
        }
      }
    }
  })
