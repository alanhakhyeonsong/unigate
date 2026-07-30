package me.ramos.unigate.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

/**
 * CORS 설정 단위 테스트.
 *
 * ## 여기서 고정하는 것
 * 1. **기본은 꺼져 있다** — 허용 origin 을 주지 않으면 아무 CORS 헤더도 나가지 않는다.
 *    로컬(same-origin)에서 의도치 않게 켜지는 것을 막는다.
 * 2. **credentials 를 허용하면서 origin 을 와일드카드로 두지 않는다** — 그 조합은 브라우저가
 *    거부할 뿐 아니라, 통했다면 어떤 사이트든 인증된 요청을 보낼 수 있다는 뜻이 된다.
 * 3. CSRF 토큰 헤더와 테넌트 주장 헤더가 preflight 를 통과한다 — 빠지면 인증된 POST 가
 *    전부 막히는데, 증상이 CORS 에러 하나로 같아 원인 찾기가 어렵다.
 *
 * 계층: L1(단위).
 */
class CorsConfigTest :
  BehaviorSpec({
    val config = CorsConfig()

    fun exchangeFrom(origin: String) =
      MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/orders").header("Origin", origin),
      )

    given("허용 origin 을 지정하지 않은 배포 (로컬 same-origin)") {
      val source = config.corsConfigurationSource(CorsProperties())

      `when`("요청이 들어오면") {
        val resolved = source.getCorsConfiguration(exchangeFrom("https://evil.example.com"))

        then("CORS 설정 자체가 없다 — 켜는 것이 명시적 선택이어야 한다") {
          resolved shouldBe null
        }
      }
    }

    given("FE 콘솔 origin 을 허용한 배포") {
      val console = "https://console.example.test"
      val source = config.corsConfigurationSource(CorsProperties(allowedOrigins = listOf(console)))

      `when`("허용된 origin 에서 오면") {
        val resolved = source.getCorsConfiguration(exchangeFrom(console))

        then("설정이 적용된다") {
          resolved shouldNotBe null
        }

        then("자격증명을 허용한다 — 세션 쿠키가 실려야 하기 때문") {
          resolved?.allowCredentials shouldBe true
        }

        then("origin 은 **정확히 그 값**이다. 와일드카드가 아니다") {
          // 와일드카드 + credentials 조합은 브라우저가 거부한다. 그리고 통한다면
          // 어떤 사이트든 인증된 요청을 보낼 수 있다는 뜻이라 CSRF 방어가 무의미해진다.
          resolved?.allowedOrigins shouldBe listOf(console)
          resolved?.allowedOriginPatterns shouldBe null
        }

        then("CSRF 토큰 헤더가 preflight 를 통과한다") {
          (resolved?.allowedHeaders?.contains("X-XSRF-TOKEN")) shouldBe true
        }

        then("테넌트 **주장** 헤더는 허용하고, 검증된 헤더는 허용하지 않는다") {
          // X-Tenant-Id 는 게이트가 주입하는 값이다. 클라이언트가 보낼 자리가 아니므로
          // 허용 목록에 넣지 않는다(넣어도 게이트가 지우지만, 목록이 곧 계약이다).
          (resolved?.allowedHeaders?.contains("X-Requested-Tenant")) shouldBe true
          (resolved?.allowedHeaders?.contains("X-Tenant-Id")) shouldBe false
        }

        then("`Retry-After` 가 응답 노출 목록에 있다 — 없으면 FE 가 값을 못 읽는다") {
          // 요청 허용 목록(allowedHeaders)과 **다른 목록**이다. 여기 없으면 429 는 도착하는데
          // res.headers.get('Retry-After') 만 조용히 null 이 된다(RetryAfterFilter 무력화).
          (resolved?.exposedHeaders?.contains("Retry-After")) shouldBe true
        }
      }
    }
  })
