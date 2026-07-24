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
 * - Filter: 통과하는 요청/응답을 변형 (`stripPrefix`, `tokenRelay`, 이후 헤더 strip)
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
              // 세션(Valkey)에 보관된 access token 을 Authorization: Bearer 로 붙인다.
              // 만료된 토큰이면 refresh token 으로 갱신한 뒤 붙인다(TokenRelayConfig 참조).
              //
              // ⚠️ tokenRelay 는 setBearerAuth 로 헤더를 **교체**하므로, 인입 Authorization 이
              // 있으면 우리 토큰으로 덮인다. 다만 "덮는 것"과 "제거 후 재주입"은 다르다 —
              // 토큰이 없는 사용자(로그아웃·만료)의 요청에서는 tokenRelay 가 아무것도 하지 않아
              // 인입 위조 헤더가 그대로 통과한다. 그래서 명시적 strip 이 Step 7 에 따로 있다.
              .tokenRelay()
          }.uri(demoUri)
      }.build()
}
