package me.ramos.unigate.config

import org.springframework.beans.factory.annotation.Value
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
 */
@Configuration
class GatewayRouteConfig {
  @Bean
  fun gatewayRoutes(
    builder: RouteLocatorBuilder,
    @Value("\${unigate.downstream.demo-uri}") demoUri: String,
  ): RouteLocator =
    builder
      .routes()
      .route("downstream-demo") { route ->
        route
          .path("/api/**")
          .filters { filters ->
            filters
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
          }.uri(demoUri)
      }.build()

  companion object {
    /** `removeRequestHeader` 는 HttpHeaders(대소문자 무시) 기준이라 `authorization` 등도 함께 제거된다. */
    private const val AUTHORIZATION_HEADER = "Authorization"
    private const val COOKIE_HEADER = "Cookie"
  }
}
