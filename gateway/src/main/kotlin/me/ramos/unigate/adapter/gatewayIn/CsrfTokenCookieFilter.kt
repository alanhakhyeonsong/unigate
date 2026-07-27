package me.ramos.unigate.adapter.gatewayIn

import org.springframework.security.web.server.csrf.CsrfToken
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * CSRF 토큰을 **실제로 발급시켜** 쿠키가 응답에 실리게 한다 (Phase 9c).
 *
 * ## 이 필터가 없으면 쿠키가 조용히 사라진다 — WebFlux 고유의 함정
 *
 * `CookieServerCsrfTokenRepository` 로 바꾸면 토큰이 `XSRF-TOKEN` 쿠키로 나갈 것 같지만,
 * **그것만으로는 나가지 않는다.**
 *
 * `CsrfWebFilter` 는 토큰을 `Mono<CsrfToken>` 형태로 exchange attribute 에 넣어둘 뿐이다.
 * Reactor 의 `Mono` 는 **구독해야 실행되는 lazy** 값이라, 아무도 구독하지 않으면 토큰 생성도
 * 쿠키 쓰기도 일어나지 않는다. Servlet 스택에서는 `CsrfToken` 이 즉시 값이라 이 문제가 없다 —
 * 같은 설정인데 스택 때문에 결과가 갈리는 지점이다.
 *
 * 증상이 특히 나쁘다:
 * - 응답은 **정상 200**, 오류도 경고도 없다
 * - 브라우저 개발자도구에도 `Set-Cookie` 가 없을 뿐 다른 이상이 없다
 * - 그래서 클라이언트가 POST 를 보내면 **403 만 반복**되고, 원인이 "토큰을 안 보냈다" 가 아니라
 *   **"토큰을 받을 수 없었다"** 라는 것을 알아채기 어렵다
 *
 * 그래서 `.then(...)` 으로 **구독을 강제**한다. 값 자체는 쓰지 않는다 — 구독이라는 부수효과
 * (토큰 생성 + 쿠키 쓰기)가 목적이다.
 *
 * ## 왜 이제야 필요해졌나
 * Phase 1 에서 CSRF 를 켤 때 이 문제를 알고 **미뤄뒀다**(`SecurityConfig` 의 TODO).
 * 당시 GW 를 경유하는 상태변경 요청은 `/iam/register` 하나뿐이었고 그건 CSRF 예외였다.
 * Phase 9c 의 관리 API(`POST .../requeue`)가 **인증된 첫 POST** 라 여기서 막혔다.
 *
 * ## 등록 위치가 중요하다
 * `SecurityWebFiltersOrder.CSRF` **뒤**에 붙여야 한다. `CsrfWebFilter` 가 attribute 를 넣기 전에
 * 돌면 읽을 것이 없어 아무 일도 하지 않는다 — 그리고 그 경우에도 **오류 없이 조용히** 실패한다.
 */
class CsrfTokenCookieFilter : WebFilter {
  override fun filter(
    exchange: ServerWebExchange,
    chain: WebFilterChain,
  ): Mono<Void> {
    val csrfToken =
      exchange.getAttribute<Mono<CsrfToken>>(CsrfToken::class.java.name)
        ?: return chain.filter(exchange)

    // `then` 이 구독을 일으킨다. 토큰 값을 쓰지 않는 것은 의도적이다 —
    // 필요한 것은 값이 아니라 **구독 시점에 일어나는 쿠키 쓰기**다.
    return csrfToken.then(chain.filter(exchange))
  }
}
