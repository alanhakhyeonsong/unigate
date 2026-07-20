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
 * - Filter: 통과하는 요청/응답을 변형 (`stripPrefix`, 이후 TokenRelay·헤더 strip)
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
          // 게이트웨이 진입 경로에서 /api 한 단계를 떼고 다운스트림에 전달한다.
          // /api/echo -> /echo
          .filters { it.stripPrefix(1) }
          .uri(demoUri)
      }.build()
}
