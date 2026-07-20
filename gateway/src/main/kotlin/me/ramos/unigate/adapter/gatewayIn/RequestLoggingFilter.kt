package me.ramos.unigate.adapter.gatewayIn

import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * 관찰용 전역 필터. 기능이 아니라 **학습이 목적**이다.
 *
 * 두 가지를 눈으로 확인하기 위해 존재한다.
 *
 * 1. 필터가 pre/post 두 구간을 갖는다는 것
 *    - `chain.filter(exchange)` 호출 **전** = pre
 *    - 그 뒤에 이어 붙인 코드 = post (다운스트림 응답이 돌아온 뒤)
 *
 * 2. 요청당 스레드가 없다는 것
 *    - 동시 요청을 아무리 늘려도 `reactor-http-nio-*` 스레드 수는 늘지 않는다
 *    - pre 와 post 가 **서로 다른 스레드**에서 찍힐 수 있다
 *
 * 이 필터를 이벤트 루프에서 블로킹하면 게이트웨이 전체가 멈춘다.
 * 로깅 외의 작업을 여기에 넣지 않는다.
 */
@Component
class RequestLoggingFilter :
  GlobalFilter,
  Ordered {
  private val log = LoggerFactory.getLogger(javaClass)

  override fun filter(
    exchange: ServerWebExchange,
    chain: GatewayFilterChain,
  ): Mono<Void> {
    val request = exchange.request
    val startedAt = System.nanoTime()

    log.info(
      "[pre ] {} {} thread={}",
      request.method,
      request.uri.path,
      Thread.currentThread().name,
    )

    return chain
      .filter(exchange)
      .then(
        Mono.fromRunnable<Void> {
          val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
          log.info(
            "[post] {} {} status={} elapsedMs={} thread={}",
            request.method,
            request.uri.path,
            exchange.response.statusCode,
            elapsedMs,
            Thread.currentThread().name,
          )
        },
      )
  }

  /**
   * pre 구간에서 가장 먼저 실행된다.
   * Reactor 체인 구조상 **가장 먼저 진입한 필터의 post 구간이 가장 나중에** 실행된다.
   */
  override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE
}
