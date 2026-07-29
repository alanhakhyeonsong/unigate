package me.ramos.unigate.iam.adapter.keycloakAdminOut

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * **`ServiceAccountTokenProvider` 의 락 경로를 실제로 태운다.**
 *
 * ## 왜 이 테스트가 따로 필요했나
 * 이 클래스의 KDoc 은 "`iam` 은 VT 라 `synchronized` 대신 `ReentrantLock` 을 쓴다"고 설명하지만,
 * **그 락을 지나는 코드는 어떤 테스트도 실행한 적이 없었다.** outbox 에 PENDING 이 없으면 워커가
 * Keycloak Admin 을 부르지 않아, 앱을 띄워 부하를 걸어도 이 경로는 조용히 비켜 간다
 * (`docs/learning/37-vt-pinning-measurement.md` §6 에서 실제로 못 태운 기록).
 *
 * ## 무엇을 검증하고 무엇을 검증하지 않는가
 * | | 여기서 | 어디서 |
 * |---|---|---|
 * | 동시 호출이 토큰을 **중복 발급하지 않는가**(이중 검사 락) | ✅ | — |
 * | 그 호출이 **virtual thread 에서** 일어나는가 | ✅ | — |
 * | 만료·무효화 시 **다시 발급하는가** | ✅ | — |
 * | `ReentrantLock` 이 캐리어를 **pin 하지 않는가** | ❌ | [37](../../../../../../../../docs/learning/37-vt-pinning-measurement.md) §4.1 |
 *
 * 마지막 줄이 중요하다. pinning 여부는 **JVM 플래그로만 관측**되는 성질이라 단언으로 바꿀 수 없다.
 * 여기서 "통과했으니 pin 이 없다"고 주장하면 [15](../../../../../../../../docs/learning/15-archunit-dependency-guard.md)
 * 가 경고한 **"통과만 하는 가드"** 가 된다. 메커니즘은 37 이 반례까지 포함해 증명했고,
 * 이 테스트는 **그 락을 실제로 지나간다**는 나머지 절반만 맡는다.
 *
 * ## 왜 Keycloak 도 Spring 컨텍스트도 띄우지 않나
 * 필요한 것은 "토큰 엔드포인트가 몇 번 불렸는가" 뿐이다. JDK 내장 `HttpServer` 를 로컬에 띄우면
 * **외부 인프라 없이** 호출 횟수를 정확히 셀 수 있고, 공유 Keycloak 에 부수효과도 남기지 않는다.
 * 그래서 `@Tag("testcontainers")` 가 없다 — `./gradlew build` 에서 매번 돈다.
 */
class ServiceAccountTokenProviderConcurrencyTest {
  private lateinit var server: HttpServer

  /** 토큰 엔드포인트가 실제로 불린 횟수. 이 테스트의 핵심 관측값이다. */
  private val issueCount = AtomicInteger(0)

  /** 발급 응답의 `expires_in`. 테스트마다 바꿔 만료 상황을 만든다. */
  private var expiresInSeconds = 300L

  @BeforeEach
  fun startTokenEndpoint() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.executor = Executors.newVirtualThreadPerTaskExecutor()
    server.createContext("/realms/test/protocol/openid-connect/token") { exchange ->
      val n = issueCount.incrementAndGet()
      // 발급이 **느리다**는 것이 중요하다. 즉시 끝나면 두 번째 호출이 락에 도달하기도 전에
      // 캐시가 채워져, 락이 없어도 이 테스트가 통과해 버린다.
      Thread.sleep(200)
      respond(exchange, """{"access_token":"token-$n","expires_in":$expiresInSeconds}""")
    }
    server.start()
  }

  @AfterEach
  fun stopTokenEndpoint() {
    server.stop(0)
  }

  @Test
  fun `동시에 32개가 토큰을 요구해도 발급은 한 번뿐이다`() {
    val provider = newProvider()
    val callers = 32
    val startGate = CountDownLatch(1)
    val done = CountDownLatch(callers)
    val tokens = ConcurrentLinkedQueue<String>()
    val virtualFlags = ConcurrentLinkedQueue<Boolean>()

    repeat(callers) {
      Thread.ofVirtual().start {
        try {
          startGate.await() // 최대한 동시에 출발시킨다 — 경합을 만들기 위해
          tokens.add(provider.accessToken())
          virtualFlags.add(Thread.currentThread().isVirtual)
        } finally {
          done.countDown()
        }
      }
    }

    startGate.countDown()
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()

    // 핵심 단언 — 이중 검사 락이 없으면 32번(혹은 그에 가깝게) 발급된다.
    assertThat(issueCount.get()).isEqualTo(1)
    // 전원이 같은 토큰을 받았다. 값이 갈리면 캐시가 덮어써진 것이다.
    assertThat(tokens).hasSize(callers)
    assertThat(tokens.distinct()).containsExactly("token-1")
    // 그리고 그 경로가 **virtual thread 위에서** 실행됐다.
    // 플랫폼 스레드로 돌았다면 이 클래스가 ReentrantLock 을 쓰는 근거 자체가 성립하지 않는다.
    assertThat(virtualFlags).hasSize(callers).containsOnly(true)
  }

  @Test
  fun `무효화하면 다음 호출에서 다시 발급한다`() {
    val provider = newProvider()

    assertThat(provider.accessToken()).isEqualTo("token-1")
    assertThat(provider.accessToken()).isEqualTo("token-1") // 캐시 히트 — 발급 없음
    assertThat(issueCount.get()).isEqualTo(1)

    // 401 을 만났을 때의 경로. 캐시를 버리지 못하면 죽은 토큰으로 영원히 재시도한다.
    provider.invalidate()

    assertThat(provider.accessToken()).isEqualTo("token-2")
    assertThat(issueCount.get()).isEqualTo(2)
  }

  @Test
  fun `skew 때문에 이미 만료로 취급되는 토큰은 캐시되지 않는다`() {
    // 만료 시각에서 skew 만큼 당겨 잡으므로, expiresIn <= skew 면 **받자마자 쓸 수 없다.**
    // 이 조합은 설정 실수(짧은 토큰 수명 + 큰 skew)로 실제로 만들어질 수 있고,
    // 그때 증상은 "매 호출마다 Keycloak 을 두드린다" 이다 — 조용한 성능 사고다.
    expiresInSeconds = 30
    val provider = newProvider(skewSeconds = 30)

    provider.accessToken()
    provider.accessToken()

    assertThat(issueCount.get()).isEqualTo(2)
  }

  private fun newProvider(skewSeconds: Long = 30): ServiceAccountTokenProvider =
    ServiceAccountTokenProvider(
      KeycloakAdminProperties(
        serverUrl = "http://127.0.0.1:${server.address.port}",
        realm = "test",
        clientId = "unigate-iam",
        // 실제 값이 아니다. 이 테스트의 가짜 엔드포인트는 자격증명을 검사하지 않는다.
        clientSecret = "test-secret",
        tokenRefreshSkewSeconds = skewSeconds,
      ),
      RestClient.builder(),
    )

  private fun respond(
    exchange: HttpExchange,
    body: String,
  ) {
    val bytes = body.toByteArray()
    exchange.responseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }
}
