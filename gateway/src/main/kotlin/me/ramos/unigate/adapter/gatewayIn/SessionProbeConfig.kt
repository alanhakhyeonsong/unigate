package me.ramos.unigate.adapter.gatewayIn

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitSession
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

/**
 * 세션 저장이 실제로 Valkey 까지 도달하는지 확인하는 로컬 전용 프로브.
 *
 * OAuth2 로그인보다 **먼저** 세션을 독립적으로 검증하기 위해 존재한다.
 * 세션이 깨진 상태로 로그인을 붙이면 증상이 "로그인 무한 리다이렉트"로 나타나
 * 원인이 Keycloak 설정인지·세션인지·필터 순서인지 구분할 수 없다.
 *
 * `@Profile("local")` 이므로 alpha 에는 이 엔드포인트가 존재하지 않는다.
 *
 * MVC 의 `HttpSession` 대신 WebFlux 의 `WebSession` 을 쓴다. 조회가 논블로킹이라
 * 반환형이 `Mono<WebSession>` 이고, coroutine 확장(`awaitSession`)으로 suspend 함수처럼 다룬다.
 */
@Configuration
@Profile("local")
class SessionProbeConfig {
  @Bean
  fun sessionProbeRoutes(): RouterFunction<ServerResponse> =
    coRouter {
      GET("/debug/session") { request ->
        val session = request.awaitSession()

        // 속성을 넣는 순간 세션이 "시작"되고 저장소에 기록된다.
        // 아무것도 넣지 않으면 세션 ID 는 생기지만 저장되지 않는다.
        val visitCount = (session.attributes["visitCount"] as? Int ?: 0) + 1
        session.attributes["visitCount"] = visitCount

        ServerResponse.ok().bodyValueAndAwait(
          mapOf(
            "sessionId" to session.id,
            "visitCount" to visitCount,
            "createdAt" to session.creationTime.toString(),
            "maxIdleTime" to session.maxIdleTime.toString(),
            "thread" to Thread.currentThread().name,
          ),
        )
      }
    }
}
