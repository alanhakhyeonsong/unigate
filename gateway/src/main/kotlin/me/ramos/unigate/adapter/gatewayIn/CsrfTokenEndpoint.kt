package me.ramos.unigate.adapter.gatewayIn

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.security.web.server.csrf.CsrfToken
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import reactor.core.publisher.Mono

/**
 * CSRF 토큰을 **응답 본문으로** 돌려준다. cross-origin FE 를 위한 것이다.
 *
 * ## 왜 필요한가 — 쿠키만으로는 cross-origin 에서 double-submit 이 성립하지 않는다
 * 게이트웨이는 토큰을 `XSRF-TOKEN` 쿠키로 내려보내고(`SecurityConfig` 의 `withHttpOnlyFalse`),
 * 클라이언트가 그 값을 읽어 헤더로 돌려주는 것을 전제한다. 그런데 그 쿠키에는 **`Domain` 속성이
 * 없어 host-only** 다. FE 를 게이트웨이와 다른 호스트에 두면 FE 의 `document.cookie` 로는
 * 그 쿠키가 **보이지 않는다.**
 *
 * ```
 * set-cookie: XSRF-TOKEN=<value>; Path=/            ← Domain 없음 = 게이트웨이 호스트 전용
 * ```
 *
 * 그래서 alpha 에서 **모든 상태 변경 요청이 403** 이었다. 로그아웃도 프로필 수정도 마찬가지다.
 * 증상이 고약한 이유는 **GET 은 전부 정상**이라는 것이다 — 세션 쿠키는 SameSite 규칙상 잘 실린다
 * (SameSite 는 *site* 기준이라 같은 등록가능 도메인이면 통과한다). 즉 "읽기는 되는데 쓰기만
 * 안 되는" 형태가 되어 인증 문제로 보이지 않는다.
 *
 * ## 왜 쿠키에 `Domain` 을 붙이지 않고 엔드포인트를 두는가
 * `Domain=<상위도메인>` 을 주면 FE 도 쿠키를 읽을 수 있다. 한 줄이면 끝나지만 **그 상위 도메인의
 * 모든 호스트**에 토큰이 뿌려진다. 같은 도메인 아래 다른 서비스가 있으면 그쪽 JS 도 읽는다.
 * BFF 의 전제는 "브라우저에 최소한만 준다" 이므로 범위를 넓히는 방향은 맞지 않는다.
 *
 * 엔드포인트 방식은 **CORS 허용 목록이 그대로 접근 제어**가 된다. 브라우저는 허용된 origin 에만
 * 응답 본문을 읽게 하고, `allowCredentials = true` 인 이상 와일드카드 origin 은 규격상 불가능하다
 * (`CorsConfig` 가 정확한 origin 만 넣는 이유와 같은 근거다).
 *
 * ## 공개 경로인데 안전한가
 * 안전하다. 공격자가 이 엔드포인트를 직접 호출하면 **자기 쿠키에 묶인 새 토큰**을 받을 뿐이다.
 * CSRF 가 성립하려면 피해자의 세션 쿠키와 짝이 맞는 토큰이 필요한데, 피해자 브라우저에서
 * 이 응답을 읽으려면 CORS 를 통과해야 하고 그건 허용 origin 뿐이다.
 *
 * ⚠️ 따라서 이 엔드포인트의 안전성은 **`unigate.cors.allowed-origins` 가 정확한지에 전적으로
 * 달려 있다.** 거기에 넓은 값이 들어가는 순간 CSRF 방어가 사라진다.
 *
 * ## 응답을 캐시하면 안 된다
 * 토큰은 세션에 묶인다. 중간 캐시가 응답을 재사용하면 **남의 토큰**을 받게 되어 403 이 되거나,
 * 더 나쁘게는 세션이 바뀐 뒤에도 옛 토큰을 계속 쓰게 된다. `no-store` 를 명시한다.
 */
@Configuration
class CsrfTokenEndpoint {
  @Bean
  fun csrfTokenRoutes(): RouterFunction<ServerResponse> =
    coRouter {
      GET(CSRF_TOKEN_PATH) { request ->
        // WebFlux 의 `CsrfToken` 은 lazy `Mono` 다. 여기서 구독(await)해야 값이 생기고,
        // 같은 구독이 `CsrfTokenCookieFilter` 와 동일한 토큰을 쿠키에도 남긴다.
        val token =
          request
            .exchange()
            .getAttribute<Mono<CsrfToken>>(CsrfToken::class.java.name)
            ?.awaitSingleOrNull()

        if (token == null) {
          // CSRF 가 꺼져 있는 구성. 값을 지어내지 않고 "줄 것이 없다" 를 그대로 알린다.
          ServerResponse.noContent().buildAndAwait()
        } else {
          ServerResponse
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .bodyValueAndAwait(
              CsrfTokenResponse(
                token = token.token,
                headerName = token.headerName,
                parameterName = token.parameterName,
              ),
            )
        }
      }
    }

  /**
   * 헤더·파라미터 이름까지 함께 준다.
   *
   * 클라이언트가 `X-XSRF-TOKEN` 을 하드코딩하면 서버가 이름을 바꿨을 때 **403 하나로만** 드러난다.
   * 서버가 알려주면 그 종류의 어긋남이 성립할 자리가 없어진다.
   */
  data class CsrfTokenResponse(
    val token: String,
    val headerName: String,
    val parameterName: String,
  )

  companion object {
    /** `SecurityConfig.PUBLIC_PATHS` 와 반드시 같이 움직인다. */
    const val CSRF_TOKEN_PATH = "/csrf"
  }
}
