package me.ramos.unigate.iam.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

/**
 * realm 역할 → 권한 변환 단위 테스트 (Phase 9c).
 *
 * ## 이 테스트가 **따로** 필요한 이유
 * 인가 경계 슬라이스 테스트(`IamSecurityBoundaryTest`)는 `jwt()` post-processor 를 쓰는데,
 * 그것은 **이 변환기를 거치지 않는다** — 이미 인증된 `JwtAuthenticationToken` 을 직접 만들어
 * SecurityContext 에 넣기 때문이다. 즉 **이 클래스를 통째로 지워도 그 테스트는 전부 통과한다.**
 *
 * `IamSecurityConfig.audienceValidator` 를 단위 테스트로 따로 겨냥한 것과 같은 구도다
 * (P8f). 슬라이스가 지나가지 않는 코드는 슬라이스로 지킬 수 없다.
 */
class KeycloakRealmRoleConverterTest :
  BehaviorSpec({
    val converter = KeycloakRealmRoleConverter()

    fun jwtWith(claims: Map<String, Any>): Jwt =
      Jwt
        .withTokenValue("token")
        .header("alg", "RS256")
        .subject("11111111-2222-3333-4444-555555555555")
        .issuedAt(Instant.parse("2026-07-27T00:00:00Z"))
        .expiresAt(Instant.parse("2026-07-27T00:05:00Z"))
        .also { builder -> claims.forEach { (k, v) -> builder.claim(k, v) } }
        .build()

    given("realm_access.roles 가 있는 토큰") {
      `when`("변환하면") {
        val authorities =
          converter.convert(
            jwtWith(mapOf("realm_access" to mapOf("roles" to listOf("unigate-user", "unigate-admin")))),
          )

        then("역할 이름이 **그대로** 권한이 된다") {
          // ⚠️ `ROLE_` 접두사를 붙이지 않는다. Keycloak realm 의 역할 이름과 코드에 적히는
          // 문자열이 같아야 디버깅할 때 나란히 놓고 비교할 수 있다.
          authorities.map { it.authority } shouldContainExactlyInAnyOrder
            listOf("unigate-user", "unigate-admin")
        }
      }
    }

    given("역할이 없는 토큰") {
      `when`("realm_access 자체가 없으면") {
        then("권한이 비어 있다 — 예외를 던지지 않는다") {
          // 예외를 던지면 **토큰 검증 실패(401)** 가 되어, 원인은 인가인데 인증 문제처럼 보인다.
          converter.convert(jwtWith(mapOf("email" to "alice@example.local"))).shouldBeEmptyList()
        }
      }

      `when`("realm_access 는 있는데 roles 가 없으면") {
        then("권한이 비어 있다") {
          converter.convert(jwtWith(mapOf("realm_access" to mapOf("other" to "x")))).shouldBeEmptyList()
        }
      }
    }

    given("형태가 예상과 다른 토큰") {
      `when`("roles 가 배열이 아니면") {
        then("권한이 비어 있다 — 외부 입력이므로 단정하지 않는다") {
          converter.convert(jwtWith(mapOf("realm_access" to mapOf("roles" to "unigate-admin")))).shouldBeEmptyList()
        }
      }

      `when`("roles 안에 문자열이 아닌 값이 섞이면") {
        val authorities =
          converter.convert(
            jwtWith(mapOf("realm_access" to mapOf("roles" to listOf("unigate-admin", 42, null)))),
          )

        then("문자열만 걸러 권한으로 만든다") {
          authorities.map { it.authority } shouldBe listOf("unigate-admin")
        }
      }
    }
  })

private fun Collection<*>.shouldBeEmptyList() {
  this.isEmpty() shouldBe true
}
