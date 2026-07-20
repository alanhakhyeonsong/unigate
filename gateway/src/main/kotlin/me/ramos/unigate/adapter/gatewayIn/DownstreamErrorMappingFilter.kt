package me.ramos.unigate.adapter.gatewayIn

import io.netty.channel.ConnectTimeoutException
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.ConnectException

/**
 * 다운스트림 통신 실패를 **의미에 맞는 상태코드**로 변환한다.
 *
 * SCG 는 `ConnectException` 을 매핑하지 않는다. 그대로 두면 처리되지 않은 예외가
 * WebFlux 범용 핸들러(`AbstractErrorWebExceptionHandler`)까지 올라가 기본값 **500** 이 된다.
 * 다운스트림만 죽었는데 게이트웨이가 500 을 반환하는 셈이다.
 *
 * 상태코드는 단순한 숫자가 아니라 **누구를 봐야 하는지에 대한 신호**다.
 *
 * | 코드 | 의미 | 온콜의 행동 | 재시도 |
 * |---|---|---|---|
 * | 500 | 게이트웨이 자신의 버그 | 게이트웨이 코드를 본다 | 하면 안 됨 |
 * | 502 | 업스트림이 응답하지 못함 | 다운스트림을 본다 | 가능 |
 * | 504 | 업스트림이 제때 응답하지 못함 | 다운스트림 지연을 본다 | 가능 |
 *
 * 500 으로 나가면 온콜은 게이트웨이부터 뒤지고, LB·클라이언트는 재시도해도 안전한 요청을
 * 재시도하지 않으며, 대시보드의 5xx 가 전부 게이트웨이 탓으로 집계된다.
 *
 * ## 왜 `ErrorWebExceptionHandler` 가 아니라 예외 변환인가
 *
 * `ResponseStatusException` 으로 바꿔주기만 하면 상태코드 결정·응답 본문 렌더링·로깅은
 * Spring Boot 의 기존 에러 처리가 그대로 해준다. 핸들러를 직접 구현하면 404·인증 실패 등
 * **지금 잘 동작하는 경로까지 전부 떠안게 된다.** 바꾸려는 것은 "어떻게 그릴 것인가"가 아니라
 * "이 예외가 무슨 뜻인가" 하나뿐이므로, 변환이 더 작고 회귀 위험이 낮다.
 *
 * 응답 본문의 형식이나 필드를 게이트웨이 표준으로 통일해야 할 때 비로소
 * `ErrorWebExceptionHandler` 가 필요해진다.
 *
 * ## 실행 위치
 *
 * `onErrorMap` 은 **자신보다 안쪽 필터에서 올라온 에러**만 볼 수 있다. 실제 프록시 호출은
 * 체인의 가장 안쪽인 `NettyRoutingFilter` 가 하므로, 이 필터는 그보다 바깥이면 어디든 된다.
 * `RequestLoggingFilter` 바로 다음에 두어, 관찰 필터가 **변환된 최종 상태코드**를 보게 한다.
 */
@Component
class DownstreamErrorMappingFilter :
  GlobalFilter,
  Ordered {
  private val log = LoggerFactory.getLogger(javaClass)

  override fun filter(
    exchange: ServerWebExchange,
    chain: GatewayFilterChain,
  ): Mono<Void> = chain.filter(exchange).onErrorMap(::toGatewayError)

  /**
   * 매핑 대상이 아니면 **받은 예외를 그대로 반환한다.** `onErrorMap` 은 모든 에러를 거치므로,
   * 여기서 삼키면 게이트웨이 자신의 버그까지 502 로 위장된다. 그건 500 오분류보다 나쁘다.
   */
  internal fun toGatewayError(error: Throwable): Throwable =
    when (error) {
      // 반드시 ConnectException 보다 먼저 검사한다.
      // ConnectTimeoutException 은 ConnectException 의 하위 타입이라 순서가 바뀌면
      // 연결 타임아웃이 502 로 흡수되어 영영 504 가 나오지 않는다.
      is ConnectTimeoutException ->
        toResponseStatus(HttpStatus.GATEWAY_TIMEOUT, DOWNSTREAM_CONNECT_TIMEOUT, error)

      // Netty 의 AnnotatedConnectException 도 이 타입에 포함된다.
      // 확인 3(다운스트림 종료)에서 실제로 관찰된 경로다.
      is ConnectException ->
        toResponseStatus(HttpStatus.BAD_GATEWAY, DOWNSTREAM_UNREACHABLE, error)

      else -> error
    }

  /**
   * 변환과 **동시에 WARN 을 남긴다.** 변환하지 않으면 500 + `ERROR` + 스택트레이스가 남지만,
   * `ResponseStatusException` 으로 바꾸는 순간 Spring 은 이를 "의도된 응답"으로 보고
   * `DEBUG` 로 낮춘다. 즉 **상태코드를 고치면 원인 로그가 사라진다.**
   *
   * 다운스트림 장애는 기본 로그 레벨에서 반드시 보여야 하므로 여기서 직접 남긴다.
   * 원인 예외를 함께 넘겨 스택트레이스도 보존한다.
   */
  private fun toResponseStatus(
    status: HttpStatus,
    reason: String,
    cause: Throwable,
  ): ResponseStatusException {
    log.warn("다운스트림 통신 실패 status={} cause={}", status.value(), cause.toString(), cause)
    return ResponseStatusException(status, reason, cause)
  }

  /**
   * `RequestLoggingFilter`(`HIGHEST_PRECEDENCE`) 바로 안쪽.
   */
  override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1

  companion object {
    // reason 은 기본 설정(server.error.include-message=never)에서 응답 본문에 노출되지 않는다.
    // 서버 로그에만 남으므로 다운스트림 주소 같은 좌표를 넣지 않는다.
    private const val DOWNSTREAM_UNREACHABLE = "다운스트림에 연결할 수 없습니다."
    private const val DOWNSTREAM_CONNECT_TIMEOUT = "다운스트림 연결이 시간 내에 완료되지 않았습니다."
  }
}
