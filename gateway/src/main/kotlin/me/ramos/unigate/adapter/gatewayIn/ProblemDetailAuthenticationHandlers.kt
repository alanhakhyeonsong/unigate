package me.ramos.unigate.adapter.gatewayIn

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI

/*
 * 인증·인가 실패 응답을 RFC 9457(구 7807) Problem Detail 로 통일하는 어댑터.
 *
 * ## 핵심 문제: 302 는 브라우저 주소창에서만 옳다
 *
 * 지금까지 미인증 요청은 **무조건** 302 로 Keycloak 인가 엔드포인트로 나갔다. 브라우저가 주소창으로
 * 이동한 경우에는 정확한 동작이지만, SPA 가 `fetch()` 로 보호 리소스를 부르면 재앙이 된다.
 *
 * ```
 * fetch('/api/echo')            → 302 Location: https://keycloak/.../auth
 *   → fetch 가 리다이렉트를 자동으로 따라감
 *   → 크로스 오리진(Keycloak)에 CORS 헤더 없음
 *   → 브라우저 콘솔: "CORS policy 에 의해 차단됨"
 * ```
 *
 * 개발자가 보는 것은 CORS 에러뿐이고 **진짜 원인(미인증)은 완전히 가려진다.** 게다가 로그인 페이지는
 * 원래 주소창에 떠야 하는데 XHR 응답으로 받아봐야 쓸 수도 없다. (`CLAUDE.md` §6.1)
 *
 * 그래서 요청의 성격에 따라 갈라준다.
 *
 * | 요청 | 응답 | 이유 |
 * |---|---|---|
 * | 브라우저 top-level 이동 | 302 → Keycloak | 주소창이 이동해야 로그인 화면이 보인다 |
 * | XHR / fetch | 401 + problem+json (`loginUrl` 동봉) | FE 가 `window.location` 으로 **직접** 이동시킨다 |
 *
 * FE 는 401 을 받으면 본문의 `loginUrl` 로 `window.location.href` 를 바꾼다. 리다이렉트 판단을
 * fetch 가 아니라 **애플리케이션 코드**가 하게 되는 것이 이 설계의 요점이다.
 *
 * ## 왜 302 를 아예 없애지 않는가
 * 없애면 브라우저로 보호 URL 을 직접 열었을 때 로그인 화면 대신 JSON 이 뜬다. BFF 는 브라우저가
 * 1차 클라이언트이므로 그 경로는 그대로 둔다.
 */

// `SecurityConfig` 의 registration id 와 일치해야 한다. 어긋나면 loginUrl 이 404 가 된다.
private const val LOGIN_URL = "/oauth2/authorization/keycloak"

private const val REASON_AUTHENTICATION_REQUIRED = "authentication_required"
private const val REASON_ACCESS_DENIED = "access_denied"

// Fetch Metadata 요청 헤더. 브라우저가 붙이며 스크립트로 위조할 수 없다.
private const val SEC_FETCH_MODE_HEADER = "Sec-Fetch-Mode"
private const val SEC_FETCH_MODE_NAVIGATE = "navigate"

/**
 * 미인증(401) 처리기.
 *
 * @param redirectEntryPoint 브라우저 내비게이션에 쓰는 기존 동작(302). Spring 이 원래 쓰던 것을 그대로
 *   위임받아, 바꾸는 것이 **분기 하나뿐**이 되게 한다(회귀 위험 최소화).
 */
@Component
class ProblemDetailAuthenticationEntryPoint(
  private val objectMapper: ObjectMapper,
  private val traceIdResolver: TraceIdResolver,
) : ServerAuthenticationEntryPoint {
  private val redirectEntryPoint = RedirectServerAuthenticationEntryPoint(LOGIN_URL)

  override fun commence(
    exchange: ServerWebExchange,
    e: AuthenticationException,
  ): Mono<Void> =
    if (isTopLevelNavigation(exchange.request)) {
      redirectEntryPoint.commence(exchange, e)
    } else {
      val problem =
        ProblemDetail.forStatusAndDetail(
          HttpStatus.UNAUTHORIZED,
          "인증이 필요합니다. loginUrl 로 이동해 로그인하세요.",
        )
      problem.title = "Authentication Required"
      problem.setProperty("reasonCode", REASON_AUTHENTICATION_REQUIRED)
      // FE 가 top-level 이동에 쓸 주소. 이 필드가 이 응답의 존재 이유다.
      problem.setProperty("loginUrl", LOGIN_URL)
      exchange.writeProblem(objectMapper, problem, traceIdResolver.currentTraceId())
    }
}

/**
 * 인증은 됐으나 권한이 없을 때(403) 처리기.
 *
 * CSRF 토큰 누락도 이 경로로 온다(`CsrfWebFilter` → `AccessDeniedException`). 403 은 로그인해도
 * 해결되지 않으므로 **리다이렉트하지 않는다** — 여기서 302 를 주면 무한 로그인 루프가 된다.
 */
@Component
class ProblemDetailAccessDeniedHandler(
  private val objectMapper: ObjectMapper,
  private val traceIdResolver: TraceIdResolver,
) : ServerAccessDeniedHandler {
  override fun handle(
    exchange: ServerWebExchange,
    denied: AccessDeniedException,
  ): Mono<Void> {
    val problem =
      ProblemDetail.forStatusAndDetail(
        HttpStatus.FORBIDDEN,
        "이 리소스에 접근할 권한이 없습니다.",
      )
    problem.title = "Access Denied"
    problem.setProperty("reasonCode", REASON_ACCESS_DENIED)
    // denied.message 는 넣지 않는다 — 내부 정책·필터 구현이 드러날 수 있다(CLAUDE.md §8).
    return exchange.writeProblem(objectMapper, problem, traceIdResolver.currentTraceId())
  }
}

/**
 * 브라우저의 **top-level 이동**인지 판정한다.
 *
 * 1순위는 `Sec-Fetch-Mode` 다. 모던 브라우저가 자동으로 붙이고 스크립트가 **위조할 수 없는**
 * (forbidden header) 값이라, `X-Requested-With` 같은 관례적 헤더보다 신뢰도가 높다.
 * - 주소창 이동/링크 클릭 → `navigate`
 * - `fetch()` / XHR → `cors` · `same-origin` · `no-cors`
 *
 * 헤더가 없으면(구형 브라우저, curl, 서버간 호출) `Accept` 로 보수적으로 판정한다. 와일드카드
 * 전체 허용은 내비게이션으로 치지 않는다 — `fetch()` 의 기본 Accept 가 정확히 그 값이기 때문이다.
 * 즉 **애매하면 401** 을 준다. 잘못된 302 는 CORS 에러로 둔갑해 원인을 감추지만,
 * 잘못된 401 은 그 자체로 원인을 정확히 말해주기 때문이다.
 */
internal fun isTopLevelNavigation(request: ServerHttpRequest): Boolean {
  request.headers.getFirst(SEC_FETCH_MODE_HEADER)?.let { return it == SEC_FETCH_MODE_NAVIGATE }
  return request.headers.accept.any {
    !it.isWildcardType && !it.isWildcardSubtype && it.isCompatibleWith(MediaType.TEXT_HTML)
  }
}

/**
 * Problem Detail 을 응답 본문으로 쓴다.
 *
 * `traceId` 를 넣는 이유: 사용자가 에러 화면의 값을 그대로 알려주면 온콜이 로그·감사로그를
 * 곧장 그 요청으로 좁힐 수 있다. 이게 트레이싱을 켠 실질적인 이득이다.
 * (샘플링에서 빠진 요청은 null 이므로 그때는 필드를 아예 넣지 않는다.)
 */
private fun ServerWebExchange.writeProblem(
  objectMapper: ObjectMapper,
  problem: ProblemDetail,
  traceId: String?,
): Mono<Void> {
  problem.instance = URI.create(request.path.value())
  traceId?.let { problem.setProperty("traceId", it) }

  response.statusCode = HttpStatus.valueOf(problem.status)
  response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
  val buffer = response.bufferFactory().wrap(objectMapper.writeValueAsBytes(problem))
  return response.writeWith(Mono.just(buffer))
}
