package me.ramos.unigate.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import reactor.core.publisher.Mono

/**
 * Rate limiting 구성 — Redis(Valkey) 토큰버킷.
 *
 * ## 토큰버킷을 왜 Redis 로 하나
 * 게이트웨이가 여러 replica 로 뜨면 in-memory 카운터는 인스턴스마다 따로 세어 실제 한도가
 * N배로 샌다. Redis 에 버킷을 두면 **모든 replica 가 같은 버킷**을 본다. SCG 의 RedisRateLimiter 는
 * 토큰 보충·소비를 **Lua 스크립트**로 원자적으로 처리해 경쟁 조건 없이 분산 한도를 지킨다.
 *
 * ## 무엇을 "1인"으로 보나 (키 해석)
 * 인증된 사용자는 **sub**(주체) 기준으로 센다 — IP 를 공유하는 사내망에서도 사용자별로 공평하게.
 * 미인증 요청은 sub 가 없으므로 **원격 IP** 로 떨어진다(로그인 시작 등). 키가 곧 "버킷 1개"다.
 */
@Configuration
class RateLimitConfig {
  /**
   * 요청을 어떤 버킷에 넣을지 결정한다: 인증되면 sub, 아니면 원격 IP.
   *
   * SCG 필터 체인은 SecurityWebFilterChain 이 채운 Reactor Context 안에서 돌기 때문에
   * [ReactiveSecurityContextHolder] 로 현재 인증을 읽을 수 있다. 인증이 없으면 context 가 비어
   * `switchIfEmpty` 로 IP 폴백이 실행된다.
   */
  @Bean
  fun rateLimitKeyResolver(): KeyResolver =
    KeyResolver { exchange ->
      ReactiveSecurityContextHolder
        .getContext()
        .map { it.authentication?.name }
        .filter { !it.isNullOrBlank() }
        .switchIfEmpty(
          Mono.fromSupplier {
            exchange.request.remoteAddress
              ?.address
              ?.hostAddress ?: FALLBACK_KEY
          },
        )
    }

  /**
   * 기본 토큰버킷 파라미터.
   * - replenishRate: 초당 보충 토큰 수(= 정상 지속 처리율)
   * - burstCapacity: 버킷 최대 용량(= 순간 허용 버스트)
   * - requestedTokens: 요청당 소비 토큰 수
   *
   * burst > replenish 로 두어 짧은 폭주는 흡수하되 지속 초과는 429 로 막는다.
   */
  @Bean
  fun redisRateLimiter(
    @Value("\${unigate.ratelimit.replenish-rate:5}") replenishRate: Int,
    @Value("\${unigate.ratelimit.burst-capacity:10}") burstCapacity: Int,
    @Value("\${unigate.ratelimit.requested-tokens:1}") requestedTokens: Int,
  ): RedisRateLimiter = RedisRateLimiter(replenishRate, burstCapacity, requestedTokens)

  companion object {
    /** remoteAddress 조차 없을 때(테스트·이상 케이스) 쓰는 최후 키. */
    private const val FALLBACK_KEY = "unknown"
  }
}
