package me.ramos.unigate.iam.adapter.keycloakAdminOut

import com.fasterxml.jackson.annotation.JsonProperty
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * IAM 자신의 **service account 토큰**을 발급·캐싱한다 (`client_credentials`).
 *
 * ## 토큰이 두 종류라는 점이 핵심이다
 * IAM 은 두 개의 서로 다른 인증을 다룬다(`IAM_PLATFORM_DECISION.md` D4 보강). 헷갈리면 설계가 무너진다.
 *
 * | | 무엇 | 어디서 오나 | 무엇에 쓰나 |
 * |---|---|---|---|
 * | **호출자 신원** | 사용자 JWT | 게이트웨이가 relay | "누가 요청했는가" 식별 |
 * | **관리 자격** | service account 토큰 | 여기서 발급 | **Keycloak Admin API 호출** |
 *
 * 사용자 토큰에는 `manage-users` 권한이 없으므로 **Admin API 호출에 쓸 수 없다.** 가입은 사용자 토큰이
 * 아예 없는 상태이기도 하다. 그래서 IAM 이 자기 자격증명으로 별도 토큰을 받는다.
 *
 * ## 왜 `synchronized` 가 아니라 `ReentrantLock` 인가 — VT 환경의 실질적 이유
 * `iam` 은 Virtual Thread 로 동작한다. **`synchronized` 블록 안에서 블로킹하면 캐리어 스레드가 pin 되어**
 * VT 의 이점이 사라진다(JDK 21). 토큰 발급은 네트워크 호출이라 정확히 그 블로킹에 해당한다.
 *
 * `ReentrantLock` 은 VT 를 인지해 대기 시 캐리어를 반납하므로 pinning 이 발생하지 않는다.
 * 락 자체는 필요하다 — 여러 요청이 동시에 만료를 감지하면 토큰을 중복 발급하기 때문이다.
 * (`docs/learning/16-virtual-thread-vs-reactive-two-modules.md` §5 함정 3)
 */
@Component
class ServiceAccountTokenProvider(
  private val properties: KeycloakAdminProperties,
  restClientBuilder: RestClient.Builder,
) {
  private val log = LoggerFactory.getLogger(javaClass)
  private val restClient = restClientBuilder.build()

  private val lock = ReentrantLock()

  @Volatile
  private var cached: CachedToken? = null

  /**
   * 유효한 access token 을 돌려준다. 캐시가 살아 있으면 그대로, 아니면 새로 발급한다.
   *
   * 락 밖에서 한 번 확인하고(빠른 경로), 락 안에서 다시 확인한다(이중 검사). 두 번째 확인이 없으면
   * 락을 기다리던 스레드들이 줄줄이 재발급을 일으킨다.
   */
  fun accessToken(): String {
    cached?.takeIf { it.usableAt(Instant.now()) }?.let { return it.value }

    return lock.withLock {
      cached?.takeIf { it.usableAt(Instant.now()) }?.let { return@withLock it.value }
      issueToken().also { cached = it }.value
    }
  }

  /** 401 을 만났을 때 캐시를 버리고 다음 호출에서 재발급하게 한다. */
  fun invalidate() {
    lock.withLock { cached = null }
  }

  private fun issueToken(): CachedToken {
    val form =
      LinkedMultiValueMap<String, String>().apply {
        add("grant_type", "client_credentials")
        add("client_id", properties.clientId)
        add("client_secret", properties.clientSecret)
      }

    val response =
      try {
        restClient
          .post()
          .uri(properties.tokenUrl())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .body(TokenResponse::class.java)
      } catch (e: RestClientException) {
        // ⚠️ 예외 메시지에 form 본문(=client_secret)이 섞이지 않도록 **원인 메시지를 그대로 넘기지 않는다.**
        // cause 는 보존하되 사람이 읽는 메시지는 우리가 만든 문장만 쓴다.
        log.warn("service account 토큰 발급 실패 clientId={}", properties.clientId)
        throw IdentityProviderUnavailableException("Keycloak 토큰 발급에 실패했습니다", e)
      }

    val token = response ?: throw IdentityProviderUnavailableException("Keycloak 토큰 응답이 비어 있습니다")
    return CachedToken(
      value = token.accessToken,
      // 만료 시각에서 skew 만큼 당겨 잡는다. 정확히 만료 시각까지 쓰면 전송 중 만료될 수 있다.
      usableUntil = Instant.now().plusSeconds(token.expiresIn - properties.tokenRefreshSkewSeconds),
    )
  }

  private data class CachedToken(
    val value: String,
    val usableUntil: Instant,
  ) {
    fun usableAt(now: Instant): Boolean = now.isBefore(usableUntil)
  }

  /**
   * 토큰 응답. 필요한 두 필드만 받는다 — Keycloak 이 주는 나머지 필드에 의존하지 않기 위해서다.
   * (Boot 기본 설정이 미지의 필드를 무시한다.)
   *
   * ⚠️ `@JsonProperty` 를 반드시 명시한다. OAuth2 응답은 **snake_case**(`access_token`)인데 Kotlin
   * 프로퍼티는 camelCase 라 그냥 두면 매핑이 안 돼 `accessToken` 이 null 이 된다. 전역
   * `property-naming-strategy` 를 바꾸는 방법도 있지만 그건 앱 전체의 직렬화를 바꾸므로 쓰지 않는다.
   */
  internal data class TokenResponse(
    @param:JsonProperty("access_token") val accessToken: String,
    @param:JsonProperty("expires_in") val expiresIn: Long,
  )
}
