package me.ramos.unigate.config

import me.ramos.unigate.adapter.gatewayIn.TenantGateFilter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 게이트웨이 라우트 정의.
 *
 * MVC 의 @RestController 와 달리 요청은 핸들러 메서드로 가지 않고
 * Route(Predicate + Filter 체인)를 통과해 다운스트림으로 프록시된다.
 *
 * - Predicate: 이 요청을 이 라우트가 처리할지 판단 (`path`)
 * - Filter: 통과하는 요청/응답을 변형 (`stripPrefix`, 인입 헤더 strip, `tokenRelay`)
 *
 * ## 필터 순서가 곧 보안 (Step 7)
 *
 * 라우트 로컬 필터는 전부 `Ordered` 를 구현하지 않아 SCG 가 order 0 으로 래핑한다
 * (`GatewayFilterSpec.filter` → `OrderedGatewayFilter(f, 0)`). 그리고 필터 정렬은
 * `AnnotationAwareOrderComparator` 의 **안정 정렬(stable sort)** 이라, order 가 같으면
 * **여기 적은 순서가 그대로 pre-phase 실행 순서**가 된다. 즉 아래 체인은 선언 순서대로 흐른다:
 *
 * `stripPrefix` → `removeRequestHeader(Authorization)` → `removeRequestHeader(Cookie)` → `tokenRelay`
 *
 * strip 을 **반드시 tokenRelay 앞**에 둬야 하는 이유: tokenRelay 는 토큰이 있을 때만
 * `setBearerAuth` 로 헤더를 다시 넣는다. strip 을 뒤에 두면 relay 가 방금 주입한 정상 토큰까지
 * 지워 정상 요청이 깨진다. (strip-after-relay 는 오답)
 *
 * ## 라우트 매칭 순서 (Phase 8f)
 *
 * `RoutePredicateHandlerMapping` 은 라우트를 **순서대로 훑어 처음 매칭된 하나**를 쓴다. 순서는
 * `Route.order` 오름차순이며, 같은 order 면 여기 선언한 순서다. `/iam/register` 는
 * `/iam` 하위 전체 패턴에도 매칭되므로 **더 좁은 쪽이 먼저** 평가돼야 한다. 선언 순서에 기대지 않고
 * `order` 를 명시해 고정한다 — 순서가 뒤집히면 가입 요청이 인증 라우트로 빨려 들어가
 * "가입하려면 로그인부터 하라"는 모순이 된다.
 */
@Configuration
class GatewayRouteConfig {
  @Bean
  fun gatewayRoutes(
    builder: RouteLocatorBuilder,
    @Value("\${unigate.downstream.demo-uri}") demoUri: String,
    @Value("\${unigate.iam.uri}") iamUri: String,
    // RedisRateLimiter 빈이 둘(기본·가입 전용)이라 타입만으로는 해석되지 않는다.
    // 파라미터 이름이 빈 이름과 같으면 Spring 이 이름으로 폴백해 주지만, 그 암묵적 규칙에
    // 기대면 파라미터 이름을 바꾸는 순간 조용히 깨진다. 명시적으로 붙인다.
    @Qualifier("redisRateLimiter") redisRateLimiter: RedisRateLimiter,
    @Qualifier("registrationRateLimiter") registrationRateLimiter: RedisRateLimiter,
    rateLimitKeyResolver: KeyResolver,
    tenantGateFilter: TenantGateFilter,
  ): RouteLocator =
    builder
      .routes()
      .route("downstream-demo") { route ->
        route
          .path("/api/**")
          .filters { filters ->
            filters
              // ── Phase 3: Rate limiting (Redis 토큰버킷) ──────────────────
              // 다운스트림을 부르기 **전에** 한도를 검사해 초과분을 429 로 일찍 끊는다.
              // 키는 sub(인증) 우선, 미인증은 IP (RateLimitConfig). 버킷 소진 시 429.
              .requestRateLimiter { config ->
                config.rateLimiter = redisRateLimiter
                config.keyResolver = rateLimitKeyResolver
              }
              // ── Phase 9f: 테넌트 게이트 (coarse 인가) ──────────────────
              // 인입 X-Tenant-Id 를 **제거**하고, X-Requested-Tenant 가 있으면 토큰의 소속과
              // 대조해 통과 시에만 검증된 값을 주입한다. 소속 밖이면 다운스트림 도달 전에 403.
              //
              // ⚠️ tokenRelay **앞**에 둔다. 헤더 조작은 토큰 주입 순서와 무관하지만,
              // 거부되는 요청에 굳이 토큰을 실을 이유가 없다.
              .filter(tenantGateFilter.filter())
              // 게이트웨이 진입 경로에서 /api 한 단계를 떼고 다운스트림에 전달한다.
              // /api/echo -> /echo
              .stripPrefix(1)
              // ── Step 7: 신뢰 경계 헤더 정리 ──────────────────────────────
              // 인입 Authorization 을 **무조건** 제거한다. 클라이언트가 보낸 토큰은 결코 신뢰하지
              // 않는다 — 게이트웨이 세션에서 꺼낸 것만 downstream 으로 간다(바로 아래 tokenRelay).
              //
              // tokenRelay 에만 맡기면 안 되는 이유: tokenRelay 는 토큰이 있을 때만 setBearerAuth 로
              // 헤더를 교체하고, 토큰이 없으면(미인증·로그아웃·refresh 실패) `defaultIfEmpty(exchange)`
              // 로 아무것도 하지 않아 **인입 위조 `Authorization: Bearer FORGED` 가 그대로 통과**한다.
              // 그 상태의 downstream 이 JWT 를 검증하기 전이라면 그대로 인증 우회가 된다.
              // 그래서 relay 의 "덮어쓰기"(부수 효과)가 아니라 여기서 "무조건 제거"(방어)를 한다.
              .removeRequestHeader(AUTHORIZATION_HEADER)
              // 게이트웨이 세션 쿠키(SESSION=...)가 downstream 으로 새어 나가지 않게 막는다.
              // BFF 에서 downstream 은 JWT 만 검증하는 Resource Server 라 브라우저 쿠키가 필요 없다.
              // 세션은 이미 라우팅 이전 WebFilter 단계에서 쿠키로 해석됐으므로, 여기서 떼도
              // 게이트웨이 세션 처리에는 영향이 없다 — downstream 전달분만 사라진다.
              // (누출되면 악의적/침해된 downstream 이 그 쿠키로 게이트웨이에서 사용자를 사칭할 수 있다.)
              .removeRequestHeader(COOKIE_HEADER)
              // ── 재주입: 세션(Valkey)의 access token 을 Authorization: Bearer 로 붙인다 ──
              // 만료된 토큰이면 refresh token 으로 갱신한 뒤 붙인다(TokenRelayConfig 참조).
              // 위에서 Authorization 을 이미 비웠으므로, 여기서 넣는 것만 downstream 에 도달한다.
              .tokenRelay()
              // ── Phase 3: Circuit Breaker + Timeout ──────────────────────
              // 다운스트림 호출을 CB 로 감싼다. 실패율이 임계치를 넘거나 타임아웃(timelimiter)이면
              // 회로가 열려 이후 요청은 다운스트림에 가지 않고 즉시 fallback(/fallback/downstream, 503)
              // 으로 간다 → 장애 시 게이트웨이가 매달리지 않는다. 설정은 application.yml 의
              // resilience4j.circuitbreaker/timelimiter.instances.downstream.
              .circuitBreaker { config ->
                config.setName("downstream")
                config.setFallbackUri("forward:/fallback/downstream")
              }
          }.uri(demoUri)
      }
      // ── Phase 8f: IAM 공개 라우트 (가입) ──────────────────────────────────
      //
      // 가입은 **사용자 토큰이 존재하지 않는** 유일한 IAM 유스케이스다
      // (`IAM_PLATFORM_DECISION.md` D4 보강). 그래서 IAM 라우트를 공개/인증으로 쪼개고
      // 이쪽만 `SecurityConfig.PUBLIC_PATHS` 에 넣는다.
      //
      // ⚠️ 이 라우트가 존재한다는 것은 **인터넷에서 직접 닿는 쓰기 엔드포인트**가 생겼다는 뜻이다.
      // 성공 요청 하나가 Keycloak 사용자 + outbox 레코드라는 영구 상태를 만든다. 조회 API 의
      // 과다 호출과 무게가 다르므로 rate limit 이 이 라우트의 핵심 방어선이다.
      .route("iam-public") { route ->
        route
          // 넓은 `iam-authenticated` 라우트(order 0)보다 먼저 평가돼야 한다.
          // 위 KDoc "라우트 매칭 순서" 참조.
          //
          // ⚠️ `order` 는 `PredicateSpec` 의 메서드라 **`path()` 앞에서만** 호출할 수 있다.
          // `path()` 는 `BooleanSpec` 을 반환하므로 뒤에 붙이면 "Unresolved reference 'order'" 다.
          .order(IAM_PUBLIC_ROUTE_ORDER)
          .path(IAM_PUBLIC_REGISTER_PATH)
          .filters { filters ->
            filters
              // 기본 limiter 가 아니라 **가입 전용**(훨씬 엄격). RateLimitConfig 참조.
              // 버킷 키에 routeId 가 포함되므로 /api/** 트래픽과 섞이지 않는다.
              .requestRateLimiter { config ->
                config.rateLimiter = registrationRateLimiter
                config.keyResolver = rateLimitKeyResolver
              }
              // stripPrefix 를 **쓰지 않는다** — /api/** 라우트와 다른 점이다.
              // IAM 의 컨트롤러가 이미 `@RequestMapping("/iam")` 이라 경로가 그대로 맞는다.
              // 여기서 1단계를 떼면 IAM 에서 404 가 나고, 게이트웨이 로그만 보면 원인이 안 보인다.
              //
              // 인입 헤더 정리는 공개 라우트에서도 그대로 한다. 미인증 경로라 오히려 더 중요하다 —
              // 아무나 `Authorization: Bearer <훔친토큰>` 을 실어 보낼 수 있고, IAM 은
              // Resource Server 라 그것을 호출자 신원으로 읽는다.
              .removeRequestHeader(AUTHORIZATION_HEADER)
              .removeRequestHeader(COOKIE_HEADER)
              // tokenRelay 는 **의도적으로 없다.** 가입 요청에는 relay 할 사용자 토큰이 없고,
              // Keycloak 쓰기는 IAM 이 자기 service account 로 수행한다(D4 보강 ②).
              .circuitBreaker { config ->
                config.setName("iam")
                config.setFallbackUri("forward:/fallback/iam")
              }
          }.uri(iamUri)
      }
      // ── Phase 8f: IAM 인증 라우트 ─────────────────────────────────────────
      //
      // `/iam/profile`, `/iam/admin/**` 등 나머지 전부. catch-all 로 두는 이유는 **deny by default**
      // 를 유지하기 위해서다 — 새 IAM 엔드포인트가 생겼을 때 GW 라우트를 추가하는 것을 잊으면
      // 404 가 날 뿐이지만, 공개 라우트를 넓게 잡아두면 잊는 순간 무인증으로 열린다.
      // 실수했을 때 **닫히는 쪽으로 실패**하는 배치다.
      //
      // 여기서의 인가는 "인증됐는가" 하나뿐이다(coarse). route-level role·테넌트 멤버십 게이트는
      // Phase 9 의 몫이고, 자원 소유권 같은 fine 인가는 IAM 자신이 판단한다.
      .route("iam-authenticated") { route ->
        route
          .path(IAM_PATH_PATTERN)
          .filters { filters ->
            filters
              .requestRateLimiter { config ->
                config.rateLimiter = redisRateLimiter
                config.keyResolver = rateLimitKeyResolver
              }.removeRequestHeader(AUTHORIZATION_HEADER)
              .removeRequestHeader(COOKIE_HEADER)
              // Phase 9f: IAM 라우트에는 테넌트 게이트를 걸지 않는다 — IAM 은 관리 평면이라
              // 대상 테넌트를 **본문·경로**로 받고 자기 도메인에서 권한을 판단한다.
              // 다만 위조 헤더는 여기서도 막아야 한다. 게이트를 안 거치는 경로일수록
              // 인입 헤더가 그대로 흘러갈 위험이 크다.
              .removeRequestHeader(TenantGateFilter.HEADER_TENANT_ID)
              // 세션의 access token 을 Bearer 로 재주입한다. IAM 은 이것을 Resource Server 로
              // 검증해 **호출자 신원**을 얻는다(D4 보강 ①). Keycloak Admin 호출 권한은 여기 없다 —
              // 그건 IAM 의 service account 토큰이 따로 한다.
              .tokenRelay()
              .circuitBreaker { config ->
                config.setName("iam")
                config.setFallbackUri("forward:/fallback/iam")
              }
          }.uri(iamUri)
      }.build()

  companion object {
    /** `removeRequestHeader` 는 HttpHeaders(대소문자 무시) 기준이라 `authorization` 등도 함께 제거된다. */
    private const val AUTHORIZATION_HEADER = "Authorization"
    private const val COOKIE_HEADER = "Cookie"

    /**
     * 공개 가입 경로. `SecurityConfig.PUBLIC_PATHS` 의 값과 **정확히 같아야** 한다.
     * 라우트만 열고 security 를 안 열면 401, 반대면 라우트 404 다.
     */
    const val IAM_PUBLIC_REGISTER_PATH = "/iam/register"

    /** 기본 order 는 0 이다. 음수로 두어 넓은 IAM 라우트보다 먼저 평가되게 고정한다. */
    private const val IAM_PUBLIC_ROUTE_ORDER = -1

    /** IAM 서비스로 향하는 전체 경로 접두사. */
    private const val IAM_PATH_PATTERN = "/iam/**"
  }
}
