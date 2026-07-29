package me.ramos.unigate.adapter.gatewayIn

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import org.springframework.http.HttpHeaders
import org.springframework.security.web.server.csrf.CsrfToken
import org.springframework.security.web.server.csrf.DefaultCsrfToken
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.HandlerStrategies
import reactor.core.publisher.Mono

/**
 * 계층: L2 슬라이스. 라우터만 띄워 **응답 형태**를 고정한다.
 *
 * 이 엔드포인트는 cross-origin FE 가 CSRF 토큰을 얻는 **유일한 경로**다. 형태가 어긋나면
 * 증상이 403 하나로만 나타나 원인이 서버인지 클라이언트인지 갈리지 않는다.
 */
class CsrfTokenEndpointTest :
  BehaviorSpec({
    val routes = CsrfTokenEndpoint().csrfTokenRoutes()

    fun clientWith(token: CsrfToken?): WebTestClient =
      WebTestClient
        .bindToRouterFunction(routes)
        .handlerStrategies(
          HandlerStrategies
            .builder()
            .webFilter { exchange, chain ->
              // `CsrfWebFilter` 가 하는 일을 흉내 낸다 — attribute 에 lazy Mono 를 넣는다.
              if (token != null) {
                exchange.attributes[CsrfToken::class.java.name] = Mono.just(token)
              }
              chain.filter(exchange)
            }.build(),
        ).build()

    given("CSRF 가 켜진 정상 구성") {
      val token = DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token-value-1234")

      `when`("토큰을 조회하면") {
        then("토큰과 **헤더·파라미터 이름**을 함께 준다") {
          // 이름까지 주는 이유: 클라이언트가 하드코딩하면 서버가 이름을 바꿨을 때
          // 403 하나로만 드러나 원인을 못 찾는다.
          clientWith(token)
            .get()
            .uri(CsrfTokenEndpoint.CSRF_TOKEN_PATH)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.token")
            .isEqualTo("token-value-1234")
            .jsonPath("$.headerName")
            .isEqualTo("X-XSRF-TOKEN")
            .jsonPath("$.parameterName")
            .isEqualTo("_csrf")
        }

        then("캐시를 금지한다") {
          // 중간 캐시가 재사용하면 **남의 토큰**을 받게 되거나,
          // 세션이 바뀐 뒤에도 옛 토큰을 계속 쓰게 된다.
          val cacheControl =
            clientWith(token)
              .get()
              .uri(CsrfTokenEndpoint.CSRF_TOKEN_PATH)
              .exchange()
              .expectStatus()
              .isOk
              .returnResult(String::class.java)
              .responseHeaders
              .getFirst(HttpHeaders.CACHE_CONTROL)

          cacheControl!!.shouldContain("no-store")
        }
      }
    }

    given("CSRF 가 꺼진 구성 (attribute 자체가 없다)") {
      `when`("토큰을 조회하면") {
        then("값을 지어내지 않고 204 로 '줄 것이 없음'을 알린다") {
          // 빈 문자열을 주면 클라이언트가 그것을 토큰으로 실어 보내 403 이 되고,
          // 원인이 "서버에 CSRF 가 꺼져 있다" 는 사실이 가려진다.
          clientWith(null)
            .get()
            .uri(CsrfTokenEndpoint.CSRF_TOKEN_PATH)
            .exchange()
            .expectStatus()
            .isNoContent
        }
      }
    }
  })
