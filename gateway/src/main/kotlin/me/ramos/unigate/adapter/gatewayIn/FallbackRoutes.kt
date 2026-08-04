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
 *
 * ## 왜 서비스마다 fallback 을 따로 두나 (Phase 8f)
 * `reasonCode` 는 FE·온콜이 **무엇이 죽었는지** 구분하는 신호다. IAM 장애와 제품 다운스트림 장애를
 * 하나의 `downstream_unavailable` 로 합치면, 가입이 실패했는데 대시보드에는 "다운스트림 장애"로만
 * 남아 원인 서비스를 좁힐 수 없다. CB 인스턴스도 `downstream`/`iam` 으로 분리돼 있으므로
 * (한쪽 장애가 다른 쪽 회로를 열지 않는다) 응답도 같은 입도로 나누는 것이 일관된다.
 */
@Configuration
class FallbackRoutes {
  @Bean
  fun downstreamFallbackRouter(): RouterFunction<ServerResponse> =
    unavailableRoute(
      path = DOWNSTREAM_FALLBACK_PATH,
      title = "Downstream Unavailable",
      reasonCode = "downstream_unavailable",
      detail = "다운스트림 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도하세요.",
    )

  /**
   * 2대째 다운스트림(billing) 전용. `reasonCode` 를 나누는 이유는 위 KDoc 그대로다 —
   * **제품이 둘이 되는 순간 "다운스트림 장애" 라는 한 마디로는 어느 제품인지 좁힐 수 없다.**
   * CB 인스턴스가 `downstream`/`billing` 으로 갈라져 있으므로 응답 입도도 같이 나눈다.
   */
  @Bean
  fun billingFallbackRouter(): RouterFunction<ServerResponse> =
    unavailableRoute(
      path = BILLING_FALLBACK_PATH,
      title = "Billing Unavailable",
      reasonCode = "billing_unavailable",
      detail = "청구 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도하세요.",
    )

  @Bean
  fun iamFallbackRouter(): RouterFunction<ServerResponse> =
    unavailableRoute(
      path = IAM_FALLBACK_PATH,
      title = "IAM Unavailable",
      reasonCode = "iam_unavailable",
      detail = "IAM 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도하세요.",
    )

  private fun unavailableRoute(
    path: String,
    title: String,
    reasonCode: String,
    detail: String,
  ): RouterFunction<ServerResponse> =
    RouterFunctions.route(RequestPredicates.path(path)) {
      val problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail)
      problem.title = title
      problem.setProperty("reasonCode", reasonCode)
      ServerResponse
        .status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyValue(problem)
    }

  companion object {
    private const val DOWNSTREAM_FALLBACK_PATH = "/fallback/downstream"
    private const val BILLING_FALLBACK_PATH = "/fallback/billing"
    private const val IAM_FALLBACK_PATH = "/fallback/iam"
  }
}
