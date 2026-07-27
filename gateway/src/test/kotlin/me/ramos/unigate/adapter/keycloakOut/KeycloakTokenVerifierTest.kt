package me.ramos.unigate.adapter.keycloakOut

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.ramos.unigate.application.auth.exception.TokenVerificationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtValidationException
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * L1 단위 — KeycloakTokenVerifier 의 **매핑·예외 번역** 책임만 검증한다.
 *
 * 디코더(서명·JWKS·클레임 검증)는 [ReactiveJwtDecoder] 를 모킹해 결과/예외만 흉내낸다.
 * JWKS 캐싱·kid 회전 등 실제 crypto 동작은 통합 테스트(KeycloakTokenVerifierJwksTest)에서 본다.
 */
class KeycloakTokenVerifierTest :
  BehaviorSpec({
    val decoder = mockk<ReactiveJwtDecoder>()
    val verifier = KeycloakTokenVerifier(decoder)

    given("검증에 성공한 JWT") {
      val jwt =
        Jwt
          .withTokenValue("t")
          .header("alg", "RS256")
          .subject("alice-sub")
          .claim("email", "alice@example.local")
          .claim("realm_access", mapOf("roles" to listOf("unigate-user", "offline_access")))
          .audience(listOf("unigate-downstream-demo", "account"))
          .issuedAt(Instant.now())
          .expiresAt(Instant.now().plusSeconds(300))
          .build()
      every { decoder.decode(any()) } returns Mono.just(jwt)

      `when`("verify 하면") {
        val principal = verifier.verify("t")

        then("도메인 주체(subject·email·groups·audiences)로 매핑된다") {
          principal.subject shouldBe "alice-sub"
          principal.email shouldBe "alice@example.local"
          // Keycloak 의 realm_access.roles 가 IdP 중립적 groups 로 넘어온다.
          principal.roles shouldBe listOf("unigate-user", "offline_access")
          principal.audiences shouldBe listOf("unigate-downstream-demo", "account")
        }
      }
    }

    given("클레임 검증 실패(aud 불일치)") {
      every { decoder.decode(any()) } returns
        Mono.error(
          JwtValidationException(
            "검증 실패",
            listOf(OAuth2Error(TokenVerificationReason.INVALID_AUDIENCE, "aud 없음", null)),
          ),
        )

      `when`("verify 하면") {
        then("invalid_audience 원인 코드로 예외가 난다") {
          val exception = shouldThrow<TokenVerificationException> { verifier.verify("t") }
          exception.reasonCode shouldBe TokenVerificationReason.INVALID_AUDIENCE
        }
      }
    }

    given("서명 검증 실패") {
      every { decoder.decode(any()) } returns
        Mono.error(BadJwtException("Signed JWT rejected: Invalid signature"))

      `when`("verify 하면") {
        then("invalid_signature 원인 코드로 예외가 난다") {
          shouldThrow<TokenVerificationException> {
            verifier.verify("t")
          }.reasonCode shouldBe TokenVerificationReason.INVALID_SIGNATURE
        }
      }
    }

    given("JWT 형식 오류") {
      every { decoder.decode(any()) } returns Mono.error(BadJwtException("Malformed token"))

      `when`("verify 하면") {
        then("malformed_token 원인 코드로 예외가 난다") {
          shouldThrow<TokenVerificationException> {
            verifier.verify("t")
          }.reasonCode shouldBe TokenVerificationReason.MALFORMED_TOKEN
        }
      }
    }
  })
