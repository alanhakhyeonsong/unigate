package me.ramos.unigate.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
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
   *
   * ## `@Primary` 가 필요한 이유 (Phase 8f 에서 실제로 기동이 깨졌다)
   * SCG 의 `GatewayAutoConfiguration.requestRateLimiterGatewayFilterFactory` 가 `RateLimiter<?>` 를
   * **생성자 주입**으로 받는다. 라우트마다 limiter 를 명시하고 있어도 그 팩토리 빈 자체는 기본값을
   * 하나 요구하므로, [registrationRateLimiter] 를 추가한 순간 후보가 둘이 되어 부팅이 실패했다:
   *
   * ```
   * Parameter 0 of method requestRateLimiterGatewayFilterFactory ...
   *   required a single bean, but 2 were found: redisRateLimiter, registrationRateLimiter
   * ```
   *
   * 라우트별 지정이 있으니 어느 쪽이 primary 든 실제 동작은 같지만, **기본값은 넓은 쪽**이어야
   * 안전하다 — 새 라우트가 limiter 를 깜빡했을 때 가입용 초강력 limit 이 걸리면 그 라우트가
   * 사실상 죽는다.
   */
  @Bean
  @Primary
  fun redisRateLimiter(
    @Value("\${unigate.ratelimit.replenish-rate:5}") replenishRate: Int,
    @Value("\${unigate.ratelimit.burst-capacity:10}") burstCapacity: Int,
    @Value("\${unigate.ratelimit.requested-tokens:1}") requestedTokens: Int,
  ): RedisRateLimiter = RedisRateLimiter(replenishRate, burstCapacity, requestedTokens)

  /**
   * 가입(`POST /iam/register`) 전용 — **훨씬 엄격한** 두 번째 토큰버킷 (Phase 8f).
   *
   * ## 왜 기본 limiter 를 쓰면 안 되나
   * 기본값(초당 5, 버스트 10)은 **인증된 사용자가 자기 화면을 쓰는 속도**를 전제로 잡은 값이다.
   * 가입은 성격이 정반대다:
   * - **미인증 공개 엔드포인트** — 누구나 무한정 두드릴 수 있다.
   * - **계정 열거**(account enumeration) — `RegisterController` 는 중복 이메일에 409 를 준다.
   *   가입 UX 상 필요한 응답이지만, 초당 5회면 이메일 목록을 빠르게 훑을 수 있다.
   * - **스팸 가입** — 성공 요청 하나가 Keycloak 사용자 + outbox 레코드를 만든다. 즉 429 로 막지
   *   못한 요청은 **영구 상태(state)** 를 남긴다. 조회 API 의 초과 요청과 무게가 다르다.
   *
   * ## 1초에 1회보다 느리게 잡는 법 — `requestedTokens` 를 키운다
   * `replenishRate` 는 **Int** 라 0.2/s 같은 값을 넣을 수 없다. 대신 요청당 소비 토큰을 늘린다:
   *
   * | 설정 | 의미 |
   * |---|---|
   * | `replenish-rate: 1` | 초당 1토큰 보충 |
   * | `requested-tokens: 5` | 요청 1건이 5토큰 소비 → **지속 처리율 = 초당 0.2회(= 분당 12회)** |
   * | `burst-capacity: 15` | 15토큰 = **연속 3건**까지 즉시 허용 |
   *
   * ⚠️ 이 세 값은 독립적이지 않다. `burstCapacity < requestedTokens` 로 두면 버킷이 가득 차도
   * 요청 1건을 감당하지 못해 **모든 요청이 429** 가 된다. 설정 오류인데 부팅은 정상이고
   * "가입이 아예 안 된다"는 증상으로만 드러난다. 그래서 아래에서 부팅 시 검증한다.
   *
   * 사람이 회원가입을 초당 여러 번 할 이유는 없으므로 분 단위 감각으로 잡는다.
   *
   * ## 버킷은 라우트별로 분리된다 — 확인한 사실
   * `RedisRateLimiter.getKeys(id, routeId)` 가 `request_rate_limiter.{routeId.id}.tokens` 로
   * **routeId 를 키에 포함**한다(spring-cloud-gateway-server 4.3.0 소스 확인). 따라서 같은 IP 가
   * `/api` 라우트를 쓰던 중이어도 가입 버킷은 별개이고, 파라미터가 다른 두 limiter 가 같은 키를
   * 덮어쓰는 사고가 없다. 키에 routeId 가 없던 구버전 감각으로 "키에 접두사를 직접 붙여야 한다"고
   * 판단하면 불필요한 KeyResolver 를 하나 더 만들게 된다.
   *
   * ## 키는 그대로 [rateLimitKeyResolver] 를 쓴다
   * 가입 요청은 미인증이라 어차피 IP 로 떨어진다. 다만 IP 기반 제한은 NAT·회사망 뒤의 여러 사용자를
   * 한 버킷에 묶는다는 한계가 있다 — 그래서 "가입이 아예 불가능"해지지 않도록 버스트를 0 이 아닌
   * 값으로 둔다.
   */
  @Bean
  fun registrationRateLimiter(
    @Value("\${unigate.ratelimit.register.replenish-rate:1}") replenishRate: Int,
    @Value("\${unigate.ratelimit.register.burst-capacity:15}") burstCapacity: Int,
    @Value("\${unigate.ratelimit.register.requested-tokens:5}") requestedTokens: Int,
  ): RedisRateLimiter {
    // 설정 실수를 **부팅 시** 잡는다. 런타임에 드러나면 "가입만 전부 429" 라는 원인 찾기 어려운
    // 증상이 되고, 그 사이 정상 사용자가 막힌다.
    require(burstCapacity >= requestedTokens) {
      "unigate.ratelimit.register: burst-capacity($burstCapacity) 가 requested-tokens($requestedTokens) " +
        "보다 작으면 버킷이 가득 차도 요청을 통과시킬 수 없어 모든 가입이 429 가 된다."
    }
    return RedisRateLimiter(replenishRate, burstCapacity, requestedTokens)
  }

  companion object {
    /** remoteAddress 조차 없을 때(테스트·이상 케이스) 쓰는 최후 키. */
    private const val FALLBACK_KEY = "unknown"
  }
}
