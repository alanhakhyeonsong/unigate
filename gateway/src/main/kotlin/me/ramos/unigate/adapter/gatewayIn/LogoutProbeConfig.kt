package me.ramos.unigate.adapter.gatewayIn

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.security.web.server.csrf.CsrfToken
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import reactor.core.publisher.Mono

/**
 * 브라우저에서 **CSRF 보호된 POST /logout 을 눌러볼 수 있게** 하는 로컬 전용 프로브.
 *
 * 왜 필요한가: 로그아웃 엔드포인트(POST /logout)는 CSRF 로 보호된다. 아직 FE(SPA)가 없어
 * CSRF 토큰을 실어 POST 를 보낼 수단이 없다. 그래서 토큰이 박힌 폼을 서버가 직접 그려 준다.
 * (실서비스에선 SPA 가 세션의 CSRF 토큰을 받아 헤더로 실어 보낸다 — SecurityConfig 의 CSRF TODO 참조.)
 *
 * ⚠️ local 프로파일 전용. `/debug` 경로는 permitAll 이라 미인증으로도 페이지는 열린다
 * (로그아웃 버튼은 로그인 상태에서만 의미가 있다).
 */
@Configuration
@Profile("local")
class LogoutProbeConfig {
  @Bean
  fun logoutProbeRoutes(): RouterFunction<ServerResponse> =
    coRouter {
      GET("/debug/logout") { request ->
        val authentication = request.principal().awaitSingleOrNull() as? Authentication
        val loggedIn = authentication != null && authentication.isAuthenticated

        // WebFlux 의 CsrfToken 은 **지연 평가**된다. CsrfWebFilter 는 exchange 속성에
        // Mono<CsrfToken> 만 넣어두고, 누군가 구독할 때 비로소 토큰을 생성·세션에 저장한다.
        // 여기서 구독(awaitSingleOrNull)해야 토큰이 세션에 박히고, 그 값을 폼에 심어야
        // 이어지는 POST /logout 이 세션의 토큰과 일치해 CSRF 검증을 통과한다.
        @Suppress("UNCHECKED_CAST")
        val csrf =
          request
            .exchange()
            .getAttribute<Mono<CsrfToken>>(CsrfToken::class.java.name)
            ?.awaitSingleOrNull()

        ServerResponse
          .ok()
          .contentType(MediaType.TEXT_HTML)
          .bodyValueAndAwait(renderPage(loggedIn, authentication?.name, csrf))
      }
    }

  private fun renderPage(
    loggedIn: Boolean,
    principalName: String?,
    csrf: CsrfToken?,
  ): String {
    val status = if (loggedIn) "로그인됨 (principal=$principalName)" else "로그인 안 됨"
    val form =
      if (csrf != null) {
        """
        <form method="post" action="/logout">
          <input type="hidden" name="${csrf.parameterName}" value="${csrf.token}"/>
          <button type="submit">로그아웃 (RP-Initiated)</button>
        </form>
        """.trimIndent()
      } else {
        "<p>CSRF 토큰을 만들 수 없습니다.</p>"
      }
    return """
      <!doctype html>
      <html lang="ko">
      <head><meta charset="utf-8"><title>unigate 로그아웃 프로브</title></head>
      <body>
        <h1>unigate 로그아웃 프로브 (local 전용)</h1>
        <p>상태: $status</p>
        $form
        <p><a href="/api/echo">/api/echo</a> — 로그아웃 후 다시 누르면 재로그인으로 튕겨야 정상이다.</p>
      </body>
      </html>
      """.trimIndent()
  }
}
