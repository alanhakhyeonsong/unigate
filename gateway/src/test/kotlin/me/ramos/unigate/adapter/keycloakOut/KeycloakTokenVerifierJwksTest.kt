package me.ramos.unigate.adapter.keycloakOut

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.runBlocking
import me.ramos.unigate.application.auth.exception.TokenVerificationException
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date

/**
 * 통합 — **실제 crypto** 로 JWKS 로컬 검증을 확인한다(Docker·Keycloak 없이, CI 실행).
 *
 * 자가서명 RSA 키로 JWT 를 발급하고, 공개키(JWKS)를 MockWebServer 로 띄워 디코더가 로컬 검증하게 한다.
 * 여기서만 볼 수 있는 것: 정상 서명 통과, 서명 위조/만료/aud 실패, 그리고 **kid 회전 시 JWKS 재조회**.
 */
class KeycloakTokenVerifierJwksTest {
  private lateinit var server: MockWebServer

  private lateinit var signingKey: RSAKey

  /** 현재 JWKS 응답(JSON). 키 회전을 흉내내기 위해 가변으로 둔다. */
  @Volatile
  private var servedJwks: String = ""

  /** JWKS 가 실제로 몇 번 조회됐는지 — 재조회(캐시 미스) 발생을 계측한다. */
  @Volatile
  private var jwksFetchCount: Int = 0

  @BeforeEach
  fun setUp() {
    signingKey = RSAKeyGenerator(2048).keyID("k1").generate()
    servedJwks = JWKSet(signingKey.toPublicJWK()).toString()
    server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          jwksFetchCount++
          return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(servedJwks)
        }
      }
    server.start()
  }

  @AfterEach
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `정상 서명 토큰은 검증되어 도메인 주체로 매핑된다`() =
    runBlocking {
      val principal = verifier().verify(signedToken(signingKey))

      assertEquals("alice", principal.subject)
      assertEquals(listOf("unigate-user"), principal.roles)
      assertEquals(listOf(AUDIENCE), principal.audiences)
    }

  @Test
  fun `JWKS 에 없는 키로 서명하면 invalid_signature 로 거부된다`() {
    // kid 는 k1 로 맞추되 키 자체가 다르다 → 캐시된 진짜 k1 공개키로 검증 실패.
    val rogueKey = RSAKeyGenerator(2048).keyID("k1").generate()

    val exception =
      assertThrows(TokenVerificationException::class.java) {
        runBlocking { verifier().verify(signedToken(rogueKey)) }
      }
    assertEquals(TokenVerificationReason.INVALID_SIGNATURE, exception.reasonCode)
  }

  @Test
  fun `만료된 토큰은 token_expired 로 거부된다`() {
    val exception =
      assertThrows(TokenVerificationException::class.java) {
        runBlocking { verifier().verify(signedToken(signingKey, expiresIn = -60)) }
      }
    assertEquals(TokenVerificationReason.TOKEN_EXPIRED, exception.reasonCode)
  }

  @Test
  fun `aud 가 다른 토큰은 invalid_audience 로 거부된다`() {
    val exception =
      assertThrows(TokenVerificationException::class.java) {
        runBlocking { verifier().verify(signedToken(signingKey, audience = "someone-else")) }
      }
    assertEquals(TokenVerificationReason.INVALID_AUDIENCE, exception.reasonCode)
  }

  @Test
  fun `키 회전 — 캐시에 없는 kid 를 만나면 JWKS 를 재조회해 검증한다`() =
    runBlocking {
      val verifier = verifier() // 하나의 디코더 = 하나의 JWKS 캐시

      // 1) k1 로 검증 → JWKS 최초 조회 후 캐시({k1}).
      verifier.verify(signedToken(signingKey))
      val fetchesAfterFirst = jwksFetchCount

      // 2) 키 회전: 새 키 k2 를 발급하고 JWKS 응답을 {k1, k2} 로 교체.
      val rotatedKey = RSAKeyGenerator(2048).keyID("k2").generate()
      servedJwks = JWKSet(listOf(signingKey.toPublicJWK(), rotatedKey.toPublicJWK())).toString()

      // 3) k2 로 서명한 토큰 → 캐시에 kid=k2 없음 → 디코더가 JWKS 재조회 → 검증 성공.
      val principal = verifier.verify(signedToken(rotatedKey))

      assertEquals("alice", principal.subject)
      // 캐시 미스로 실제 재조회가 일어났어야 한다.
      assert(jwksFetchCount > fetchesAfterFirst) {
        "kid 미스 시 JWKS 재조회가 발생해야 한다 (fetches: $fetchesAfterFirst -> $jwksFetchCount)"
      }
    }

  // --- helpers -------------------------------------------------------------

  private fun verifier(expectedAudience: String = AUDIENCE): KeycloakTokenVerifier {
    val decoder = buildJwkSetDecoder(server.url("/certs").toString(), ISSUER, expectedAudience)
    return KeycloakTokenVerifier(decoder)
  }

  private fun signedToken(
    key: RSAKey,
    issuer: String = ISSUER,
    audience: String = AUDIENCE,
    expiresIn: Long = 300,
    subject: String = "alice",
  ): String {
    val now = Instant.now()
    val expiresAt = now.plusSeconds(expiresIn)
    // iat 는 항상 exp 보다 앞서야 한다(exp<iat 는 "만료"가 아니라 형식 오류로 거부됨).
    // 그래서 exp 기준 300초 전으로 둔다 → 만료 케이스(expiresIn<0)도 진짜 "만료된" 토큰이 된다.
    val issuedAt = expiresAt.minusSeconds(300)
    val claims =
      JWTClaimsSet
        .Builder()
        .subject(subject)
        .issuer(issuer)
        .audience(audience)
        .issueTime(Date.from(issuedAt))
        .expirationTime(Date.from(expiresAt))
        .claim("email", "alice@example.local")
        .claim("realm_access", mapOf("roles" to listOf("unigate-user")))
        .build()
    val signed =
      SignedJWT(
        JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(),
        claims,
      )
    signed.sign(RSASSASigner(key))
    return signed.serialize()
  }

  companion object {
    private const val ISSUER = "https://issuer.test/realms/test"
    private const val AUDIENCE = "unigate-downstream-demo"
  }
}
