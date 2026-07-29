package me.ramos.unigate.adapter.gatewayIn

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.http.HttpHeaders

/**
 * 계층: L1 단위. 헤더 → 대기 초 계산만 본다.
 *
 * 필터가 **언제 손대지 않는가**를 함께 고정한다. 계산식보다 이쪽이 회귀에 약하다 —
 * "429 면 무조건 붙인다"로 바뀌어도 계산 테스트는 전부 통과하기 때문이다.
 */
class RetryAfterFilterTest :
  BehaviorSpec({
    val filter = RetryAfterFilter()

    fun headersOf(
      replenishRate: String? = null,
      requestedTokens: String? = null,
      remaining: String? = null,
    ) = HttpHeaders().apply {
      replenishRate?.let { set(RedisRateLimiter.REPLENISH_RATE_HEADER, it) }
      requestedTokens?.let { set(RedisRateLimiter.REQUESTED_TOKENS_HEADER, it) }
      remaining?.let { set(RedisRateLimiter.REMAINING_HEADER, it) }
    }

    given("기본 라우트 limiter 가 거절한 응답 (초당 5토큰 보충, 요청당 1토큰)") {
      val headers = headersOf(replenishRate = "5", requestedTokens = "1", remaining = "0")

      `when`("대기 초를 계산하면") {
        then("1초가 된다 — ceil(1/5) 은 0 이 아니라 1로 바닥을 둔다") {
          // 0 을 주면 "즉시 다시 오라"가 되어 거절의 의미가 사라진다.
          filter.retryAfterSeconds(headers) shouldBe 1L
        }
      }
    }

    given("가입 라우트 limiter 가 거절한 응답 (초당 1토큰 보충, 요청당 5토큰)") {
      `when`("토큰이 하나도 없으면") {
        then("5초를 기다리라고 한다") {
          filter.retryAfterSeconds(
            headersOf(replenishRate = "1", requestedTokens = "5", remaining = "0"),
          ) shouldBe 5L
        }
      }

      `when`("토큰이 3개 남아 있으면") {
        then("부족분(2)만큼인 2초만 기다리라고 한다") {
          // 남은 토큰을 무시하고 항상 최대치를 주면 필요 이상으로 기다리게 된다.
          filter.retryAfterSeconds(
            headersOf(replenishRate = "1", requestedTokens = "5", remaining = "3"),
          ) shouldBe 2L
        }
      }
    }

    given("우리 limiter 가 만든 429 가 아닌 경우") {
      `when`("X-RateLimit 헤더가 전혀 없으면") {
        then("계산하지 않고 null 을 준다") {
          // 다운스트림이 준 429 에 게이트웨이가 지어낸 값을 붙이면 없느니만 못하다.
          filter.retryAfterSeconds(HttpHeaders()).shouldBeNull()
        }
      }

      `when`("헤더가 일부만 있으면") {
        then("null 을 준다") {
          filter.retryAfterSeconds(headersOf(replenishRate = "5")).shouldBeNull()
          filter.retryAfterSeconds(headersOf(replenishRate = "5", requestedTokens = "1")).shouldBeNull()
        }
      }

      `when`("헤더 값이 숫자가 아니면") {
        then("null 을 준다") {
          filter
            .retryAfterSeconds(
              headersOf(replenishRate = "unknown", requestedTokens = "1", remaining = "0"),
            ).shouldBeNull()
        }
      }
    }

    given("계산이 성립하지 않는 값") {
      `when`("보충 속도가 0 이면") {
        then("null 을 준다 — 나눗셈이 터지기 전에 막는다") {
          // 0 으로 나누면 Infinity 가 되고 toLong() 이 Long.MAX_VALUE 를 준다.
          // 그대로 나가면 Retry-After 가 천문학적인 초가 된다.
          filter
            .retryAfterSeconds(
              headersOf(replenishRate = "0", requestedTokens = "1", remaining = "0"),
            ).shouldBeNull()
        }
      }

      `when`("토큰이 모자라지 않은데 429 인 경우") {
        then("null 을 준다 — 우리가 아는 원인이 아니면 추측하지 않는다") {
          filter
            .retryAfterSeconds(
              headersOf(replenishRate = "5", requestedTokens = "1", remaining = "5"),
            ).shouldBeNull()
        }
      }
    }
  })
