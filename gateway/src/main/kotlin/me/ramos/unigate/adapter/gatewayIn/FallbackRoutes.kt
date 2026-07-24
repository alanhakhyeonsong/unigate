package me.ramos.unigate.adapter.gatewayIn

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * Circuit Breaker fallback 응답.
 *
 * 다운스트림이 열린 회로(open) 상태이거나 타임아웃일 때, `GatewayRouteConfig` 의 circuitBreaker 필터가
 * 요청을 `forward:/fallback/downstream` 으로 넘긴다. 여기서 **매달리지 않고** RFC 7807 Problem Detail
 * 로 503 을 즉시 돌려준다.
 *
 * 왜 즉시 503 인가: CB 가 없으면 다운스트림 장애 시 모든 요청이 타임아웃까지 대기해 게이트웨이
 * 스레드/커넥션이 고갈되고 장애가 전파된다. CB 는 빠르게 실패시켜 그 전파를 끊는다(bulkhead 효과).
 *
 * 어떤 HTTP 메서드로 forward 되든 응답하도록 path 서술자만 쓴다(메서드 무관).
 */
@Configuration
class FallbackRoutes {
  @Bean
  fun downstreamFallbackRouter(): RouterFunction<ServerResponse> =
    RouterFunctions.route(RequestPredicates.path(FALLBACK_PATH)) {
      val problem =
        ProblemDetail.forStatusAndDetail(
          HttpStatus.SERVICE_UNAVAILABLE,
          "다운스트림 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도하세요.",
        )
      problem.title = "Downstream Unavailable"
      problem.setProperty("reasonCode", "downstream_unavailable")
      ServerResponse
        .status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyValue(problem)
    }

  companion object {
    private const val FALLBACK_PATH = "/fallback/downstream"
  }
}
