package me.ramos.unigate.adapter.gatewayIn

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import kotlin.math.ceil

/**
 * 토큰버킷이 거절한 429 에 **`Retry-After`** 를 붙인다.
 *
 * ## 왜 필요했나 — 거절만 하고 언제 다시 오라는 말이 없었다
 * `RateLimitConfig` 의 두 limiter 는 초과 요청을 빠르게 429 로 끊는다. 그런데 응답에는
 * `X-RateLimit-*` 만 있고 **`Retry-After` 가 없었다.** 클라이언트 입장에서 이 둘은 전혀 다르다:
 *
 * | 응답 | 클라이언트가 할 수 있는 일 |
 * |---|---|
 * | `429` 만 | 언제 풀리는지 모른다 → 사람이 새로고침하거나, 무작정 재시도해 버킷을 더 소진한다 |
 * | `429 + Retry-After` | 정확히 그만큼 기다렸다가 한 번만 다시 건다 |
 *
 * 샘플 FE 는 이미 **4xx 를 재시도하지 않는 쪽**으로 정해져 있다(`queries/hooks.ts` 의 `shouldRetry`
 * — "429 는 재시도가 토큰버킷을 더 소진시켜 상황을 악화시킨다"). 즉 클라이언트 정책은 있는데
 * **서버가 근거 값을 안 주고 있었다.** 이 필터가 그 한 조각을 채운다.
 *
 * ## 값을 어떻게 정하나 — 추측하지 않고 limiter 가 준 숫자로만 계산한다
 * 거절한 limiter 자신이 응답 헤더에 파라미터를 실어 보낸다. 그것만으로 계산이 닫힌다:
 *
 * ```
 * 부족한 토큰 = requestedTokens - remaining
 * 대기 초     = ceil(부족한 토큰 / replenishRate)      (최소 1초)
 * ```
 *
 * | | `requestedTokens` | `replenishRate` | `remaining` | 결과 |
 * |---|---|---|---|---|
 * | 기본 라우트 | 1 | 5/s | 0 | `ceil(1/5)` → **1초** |
 * | 가입 라우트 | 5 | 1/s | 0 | `ceil(5/1)` → **5초** |
 * | 가입 라우트 | 5 | 1/s | 3 | `ceil(2/1)` → **2초** |
 *
 * 게이트웨이가 limiter 설정을 **다시 읽지 않는다**는 점이 중요하다. 라우트마다 limiter 가 다르고
 * (`redisRateLimiter` vs `registrationRateLimiter`), 어느 쪽이 거절했는지 필터가 알아내려면
 * 라우트 정의를 파싱해야 한다. 그렇게 얻은 값은 **설정이 바뀌면 조용히 어긋난다.**
 * 응답 헤더는 거절한 그 limiter 가 직접 쓴 값이라 어긋날 수가 없다.
 *
 * ## 언제 손대지 않는가 (셋 다 의도된 것)
 * - **429 가 아니면** — 이 헤더는 429·503 의 의미다.
 * - **이미 `Retry-After` 가 있으면** — 다운스트림이 자기 판단으로 넣은 값을 덮지 않는다.
 * - **`X-RateLimit-*` 이 없으면** — 우리 limiter 가 만든 429 가 아니라는 뜻이다.
 *   다운스트림이 준 429 에 게이트웨이가 **지어낸 값**을 붙이면 없느니만 못하다.
 *
 * ⚠️ 세 번째 조건 때문에 이 필터는 **`spring.cloud.gateway.filter.request-rate-limiter.deny-empty-key`
 * 같은 설정이 아니라 limiter 의 헤더 노출에 의존한다.** SCG 의 `RedisRateLimiter.includeHeaders`
 * 를 끄면(`spring.cloud.gateway.redis-rate-limiter.include-headers=false`) **아무 경고 없이**
 * `Retry-After` 도 함께 사라진다. 헤더를 끌 일이 생기면 이 필터부터 다시 봐야 한다.
 *
 * ## 값이 작은 쪽으로 틀리는 것이 안전하다
 * `Retry-After` 는 규약상 **힌트**다. 짧게 잡아 클라이언트가 일찍 오면 429 를 한 번 더 받고,
 * 그 응답에는 **다시 계산된 정확한 값**이 실린다 — 스스로 교정된다. 길게 잡으면 교정할 방법이
 * 없고 사용자만 기다린다. 그래서 `ceil` 을 쓰되 최소 1초로만 바닥을 두고, 여유분을 얹지 않는다.
 *
 * ## 실행 위치
 * `beforeCommit` 훅은 **응답이 커밋되기 직전**에 실행된다. SCG 의 rate limit 필터는 거절 시
 * `exchange.response.setComplete()` 로 곧장 응답을 닫으므로, 훅은 그보다 **먼저 등록**돼 있어야 한다.
 * 그래서 이 필터는 체인 바깥쪽에 둔다.
 */
@Component
class RetryAfterFilter :
  GlobalFilter,
  Ordered {
  override fun filter(
    exchange: ServerWebExchange,
    chain: GatewayFilterChain,
  ): Mono<Void> {
    // chain.filter() 보다 **먼저** 등록해야 한다. rate limit 필터가 안쪽에서 응답을 즉시 닫기 때문에,
    // 체인이 끝난 뒤에 등록하면 이미 커밋된 응답에 헤더를 못 넣는다.
    exchange.response.beforeCommit {
      Mono.fromRunnable { applyRetryAfter(exchange.response.headers, exchange.response.statusCode) }
    }
    return chain.filter(exchange)
  }

  private fun applyRetryAfter(
    headers: HttpHeaders,
    status: org.springframework.http.HttpStatusCode?,
  ) {
    if (status?.value() != HttpStatus.TOO_MANY_REQUESTS.value()) return
    if (headers.containsKey(HttpHeaders.RETRY_AFTER)) return
    retryAfterSeconds(headers)?.let { headers[HttpHeaders.RETRY_AFTER] = it.toString() }
  }

  /**
   * limiter 가 남긴 헤더만으로 대기 초를 계산한다. 계산에 필요한 값이 하나라도 없으면
   * **null 을 돌려 아무것도 붙이지 않는다** — 지어낸 값보다 헤더가 없는 편이 낫다.
   */
  internal fun retryAfterSeconds(headers: HttpHeaders): Long? {
    val replenishRate = headers.longValue(RedisRateLimiter.REPLENISH_RATE_HEADER) ?: return null
    val requestedTokens = headers.longValue(RedisRateLimiter.REQUESTED_TOKENS_HEADER) ?: return null
    val remaining = headers.longValue(RedisRateLimiter.REMAINING_HEADER) ?: return null

    // replenishRate 가 0 이면 버킷이 영원히 안 채워진다. 나눗셈이 터지기 전에 막는다.
    if (replenishRate <= 0) return null

    val missingTokens = requestedTokens - remaining
    // 거절당했는데 토큰이 모자라지 않다면 우리가 아는 원인이 아니다. 추측하지 않는다.
    if (missingTokens <= 0) return null

    return maxOf(MIN_RETRY_AFTER_SECONDS, ceil(missingTokens.toDouble() / replenishRate).toLong())
  }

  private fun HttpHeaders.longValue(name: String): Long? = getFirst(name)?.toLongOrNull()

  /**
   * `RequestLoggingFilter`(`HIGHEST_PRECEDENCE`)·`DownstreamErrorMappingFilter`(+1) 바로 안쪽.
   * 어느 rate limit 필터보다 바깥이면 되므로 순서 자체가 빡빡하지는 않다.
   */
  override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 2

  companion object {
    /** `Retry-After: 0` 은 "즉시 다시 오라"는 말이라 거절의 의미를 지운다. */
    private const val MIN_RETRY_AFTER_SECONDS = 1L
  }
}
