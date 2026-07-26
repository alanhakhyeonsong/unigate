package me.ramos.unigate.iam.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.springframework.core.env.Environment
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

/**
 * `aud` 검증기 단위 테스트 (Phase 8f).
 *
 * ## 왜 슬라이스 테스트로는 부족한가
 * `IamSecurityBoundaryTest` 는 `spring-security-test` 의 `jwt()` post-processor 로 인증을 흉내 내는데,
 * 그 방식은 **[org.springframework.security.oauth2.jwt.JwtDecoder] 를 아예 호출하지 않는다.** 즉
 * 인가 경계는 검증되지만 토큰 검증기는 한 번도 실행되지 않는다. 여기를 겨냥한 테스트가 따로 없으면
 * `aud` 검증이 통째로 빠져도 **모든 테스트가 초록불**이다.
 *
 * 이 검증기가 막는 것: 같은 realm 이 발급한 **다른 서비스용 토큰**의 재사용. 서명·`iss`·`exp` 는
 * 그런 토큰도 전부 통과시킨다.
 */
class JwtAudienceValidationTest :
  BehaviorSpec({
    val config = IamSecurityConfig(mockk<Environment>(relaxed = true))
    val validator = config.audienceValidator(EXPECTED_AUDIENCE)

    Given("aud 검증기") {
      When("aud 에 IAM 이 단독으로 들어 있으면") {
        val result = validator.validate(jwtWithAudience(listOf(EXPECTED_AUDIENCE)))

        Then("통과한다") {
          result.hasErrors() shouldBe false
        }
      }

      When("aud 에 여러 수신자가 있고 그 중 IAM 이 포함되면") {
        // 실제 토큰의 모습이다. GW 로그인 client 에 audience mapper 가 여러 개 붙어 있고
        // Keycloak 이 `account` 도 기본으로 넣는다(KEYCLOAK_REALM_SETUP.md §4.8).
        val result =
          validator.validate(
            jwtWithAudience(listOf("unigate-downstream-demo", EXPECTED_AUDIENCE, "account")),
          )

        Then("통과한다 — aud 는 '유일한 수신자'가 아니라 '수신자 목록'이다") {
          result.hasErrors() shouldBe false
        }
      }

      When("aud 에 IAM 이 없으면") {
        // 다운스트림 제품 API 용으로 발급된 토큰을 그대로 IAM 에 들이미는 상황.
        // 서명도 iss 도 정상이므로 이 검증기 말고는 아무도 막지 못한다.
        val result = validator.validate(jwtWithAudience(listOf("unigate-downstream-demo", "account")))

        Then("거부한다") {
          result.hasErrors() shouldBe true
        }
      }

      When("aud 클레임이 아예 없으면") {
        // audience mapper 를 realm 에 넣지 않았을 때의 모습이다. 이 경우가 운영에서 가장 흔한 실수다.
        val result = validator.validate(jwtWithoutAudience())

        Then("거부한다 — 없는 것은 통과가 아니다") {
          result.hasErrors() shouldBe true
        }
      }
    }
  }) {
  companion object {
    private const val EXPECTED_AUDIENCE = "unigate-iam"

    /**
     * 검증기 입력용 최소 [Jwt].
     *
     * 서명·만료 검증은 [org.springframework.security.oauth2.jwt.NimbusJwtDecoder] 와
     * `JwtValidators` 의 몫이므로 여기서는 `aud` 만 의미가 있다. `tokenValue` 는 아무 문자열이어도
     * 되지만 **실제 토큰처럼 보이는 값을 넣지 않는다**(로그·복붙 사고 방지).
     */
    private fun jwtWithAudience(audience: List<String>): Jwt = jwtBuilder().audience(audience).build()

    private fun jwtWithoutAudience(): Jwt = jwtBuilder().build()

    private fun jwtBuilder(): Jwt.Builder =
      Jwt
        .withTokenValue("dummy-token-value")
        .header("alg", "RS256")
        .subject("11111111-2222-3333-4444-555555555555")
        .issuer("http://localhost:1/realms/test")
        .issuedAt(Instant.EPOCH)
        .expiresAt(Instant.EPOCH.plusSeconds(300))
  }
}
