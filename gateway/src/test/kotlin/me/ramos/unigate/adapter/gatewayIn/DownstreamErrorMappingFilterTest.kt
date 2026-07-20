package me.ramos.unigate.adapter.gatewayIn

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.netty.channel.ConnectTimeoutException
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.net.ConnectException

class DownstreamErrorMappingFilterTest :
  BehaviorSpec({
    val filter = DownstreamErrorMappingFilter()

    given("다운스트림에 연결하지 못한 경우") {
      val cause = ConnectException("Connection refused")

      `when`("에러를 변환하면") {
        val mapped = filter.toGatewayError(cause)

        then("502 Bad Gateway 가 된다") {
          mapped.shouldBeInstanceOf<ResponseStatusException>()
          mapped.statusCode shouldBe HttpStatus.BAD_GATEWAY
        }
        then("원인 예외가 보존된다") {
          // 원인이 끊기면 로그에 "왜 연결이 안 됐는지"가 사라진다.
          mapped.cause shouldBe cause
        }
      }
    }

    given("다운스트림 연결이 타임아웃된 경우") {
      // ConnectTimeoutException 은 ConnectException 의 하위 타입이다.
      // when 분기 순서가 뒤집히면 502 로 흡수되어 이 테스트가 깨진다.
      val cause = ConnectTimeoutException("connection timed out")

      `when`("에러를 변환하면") {
        val mapped = filter.toGatewayError(cause)

        then("502 가 아니라 504 Gateway Timeout 이 된다") {
          mapped.shouldBeInstanceOf<ResponseStatusException>()
          mapped.statusCode shouldBe HttpStatus.GATEWAY_TIMEOUT
        }
      }
    }

    given("다운스트림 통신과 무관한 예외") {
      val cause = IllegalStateException("게이트웨이 자신의 버그")

      `when`("에러를 변환하면") {
        val mapped = filter.toGatewayError(cause)

        then("변환하지 않고 그대로 돌려준다") {
          // 여기서 삼키면 게이트웨이 버그까지 502 로 위장된다.
          // 500 오분류보다 나쁘다 — 다운스트림을 아무리 봐도 원인이 없다.
          mapped shouldBe cause
        }
      }
    }
  })
