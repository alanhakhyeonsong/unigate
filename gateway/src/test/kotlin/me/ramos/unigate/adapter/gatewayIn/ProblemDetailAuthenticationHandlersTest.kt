package me.ramos.unigate.adapter.gatewayIn

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest

/**
 * "브라우저 top-level 이동인가"를 판정하는 [isTopLevelNavigation] 단위 테스트.
 *
 * 이 판정 하나가 302(리다이렉트)와 401(problem+json)을 가른다. 잘못 판정하면 SPA 는 원인 불명의
 * CORS 에러를 보게 되므로(CLAUDE.md §6.1), 경계값을 여기서 못 박는다.
 *
 * 계층: L1 단위 테스트 → Kotest BehaviorSpec (testing skill 규칙 2).
 */
class ProblemDetailAuthenticationHandlersTest :
  BehaviorSpec({
    given("Sec-Fetch-Mode 헤더가 있는 모던 브라우저 요청") {
      `when`("주소창 이동(navigate)이면") {
        val request =
          MockServerHttpRequest
            .get("/api/echo")
            .header("Sec-Fetch-Mode", "navigate")
            .build()

        then("top-level 이동으로 판정한다 → 302 로 로그인 페이지를 보여줄 수 있다") {
          isTopLevelNavigation(request) shouldBe true
        }
      }

      `when`("fetch()의 교차 출처 호출(cors)이면") {
        val request =
          MockServerHttpRequest
            .get("/api/echo")
            .header("Sec-Fetch-Mode", "cors")
            .build()

        then("top-level 이동이 아니다 → 401 problem+json 을 줘야 한다") {
          isTopLevelNavigation(request) shouldBe false
        }
      }

      `when`("fetch()의 동일 출처 호출(same-origin)이면") {
        val request =
          MockServerHttpRequest
            .get("/api/echo")
            .header("Sec-Fetch-Mode", "same-origin")
            .build()

        then("top-level 이동이 아니다") {
          isTopLevelNavigation(request) shouldBe false
        }
      }

      `when`("Accept 가 text/html 인데 Sec-Fetch-Mode 는 cors 이면") {
        // Sec-Fetch-Mode 가 Accept 보다 우선한다. 브라우저가 붙이는 값이라 위조가 불가능해
        // 더 신뢰할 수 있기 때문이다.
        val request =
          MockServerHttpRequest
            .get("/api/echo")
            .header("Sec-Fetch-Mode", "cors")
            .header(HttpHeaders.ACCEPT, "text/html")
            .build()

        then("Sec-Fetch-Mode 를 따라 top-level 이동이 아니라고 판정한다") {
          isTopLevelNavigation(request) shouldBe false
        }
      }
    }

    given("Sec-Fetch-Mode 가 없는 요청 (구형 브라우저·curl·서버간 호출)") {
      `when`("Accept 가 브라우저 내비게이션 형태이면") {
        val request =
          MockServerHttpRequest
            .get("/api/echo")
            .header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        then("top-level 이동으로 판정한다") {
          isTopLevelNavigation(request) shouldBe true
        }
      }

      `when`("Accept 가 와일드카드 전체 허용이면") {
        // fetch() 의 기본 Accept 가 정확히 이 값이다. 여기서 true 를 주면 SPA 가 302 를 받아
        // CORS 에러로 둔갑한다 — 이 테스트가 그 회귀를 막는다.
        val request =
          MockServerHttpRequest
            .get("/api/echo")
            .header(HttpHeaders.ACCEPT, "*/*")
            .build()

        then("top-level 이동이 아니다 → 애매하면 401") {
          isTopLevelNavigation(request) shouldBe false
        }
      }

      `when`("Accept 가 application/json 이면") {
        val request =
          MockServerHttpRequest
            .get("/api/echo")
            .header(HttpHeaders.ACCEPT, "application/json")
            .build()

        then("top-level 이동이 아니다") {
          isTopLevelNavigation(request) shouldBe false
        }
      }

      `when`("Accept 헤더가 아예 없으면") {
        val request = MockServerHttpRequest.get("/api/echo").build()

        then("top-level 이동이 아니다 → 안전한 쪽(401)으로 판정한다") {
          isTopLevelNavigation(request) shouldBe false
        }
      }
    }
  })
