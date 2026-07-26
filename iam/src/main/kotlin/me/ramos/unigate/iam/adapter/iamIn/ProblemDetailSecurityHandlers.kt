package me.ramos.unigate.iam.adapter.iamIn

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import java.net.URI

/**
 * IAM 의 인증·인가 실패 응답을 **RFC 9457 Problem Detail** 로 통일한다 (Phase 8e).
 *
 * ## 왜 필요했나 — P8f 가 남긴 숙제
 * Resource Server 의 기본 동작은 **빈 본문 + `WWW-Authenticate` 헤더**다. OAuth2 표준으로는 맞지만,
 * 게이트웨이는 Phase 4 에서 모든 에러를 `application/problem+json` 으로 통일했다. 한 시스템의 두
 * 구간이 서로 다른 형식으로 실패하면 클라이언트가 **분기 처리**를 해야 한다.
 *
 * 더 실질적인 문제는 진단이다. 빈 401 본문은 "왜 거부됐는지" 를 헤더에만 남기고, 브라우저
 * 개발자도구에서 헤더를 펼치지 않으면 보이지 않는다(`docs/learning/19` §5 함정 6에서 겪었다).
 *
 * ## 표준 구현에 **위임한 뒤** 본문만 얹는다 (직접 겪은 함정)
 * 처음에는 상태코드와 헤더를 직접 세팅했다. 그러자 `WWW-Authenticate` 헤더가 **통째로 사라졌다** —
 * 그 헤더를 넣던 주체가 바로 내가 갈아끼운 [BearerTokenAuthenticationEntryPoint] 였기 때문이다.
 * "Spring Security 가 이미 넣어줬겠지" 라고 가정했는데, 교체한 것이 그 넣어주던 구현이었다.
 *
 * 그래서 지금은 **표준 구현을 먼저 호출**해 상태코드와 헤더를 그대로 받고, 본문만 추가한다.
 * 상태코드를 위임에 맡기는 것도 중요하다 — Bearer 규약에서 실패는 401 만이 아니다
 * (`invalid_request` → 400, `insufficient_scope` → 403). 401 로 못 박으면 그 구분이 사라진다.
 *
 * ## 게이트웨이와 다른 점: 302 가 없다
 * GW 의 핸들러는 요청 성격(`Sec-Fetch-Mode`)을 보고 302/401 을 가른다. IAM 은 **로그인시키는 주체가
 * 아니므로** 보낼 곳이 없다 — 리다이렉트는 BFF 인 게이트웨이의 일이다.
 */
@Component
class ProblemDetailAuthenticationEntryPoint(
  private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {
  private val delegate = BearerTokenAuthenticationEntryPoint()

  override fun commence(
    request: HttpServletRequest,
    response: HttpServletResponse,
    authException: AuthenticationException,
  ) {
    // ① 표준 처리: 상태코드 + WWW-Authenticate(거부 사유 포함). 본문은 쓰지 않는다.
    delegate.commence(request, response, authException)
    // ② 그 위에 Problem Detail 본문만 얹는다.
    response.writeProblem(objectMapper, request)
  }
}

/**
 * 인증은 됐으나 권한이 없을 때 → 403.
 *
 * **401 로 내리지 않는 것이 중요하다.** 403 을 401 로 바꾸면 클라이언트가 재로그인 → 성공 →
 * 다시 403… 을 반복하는 무한 루프가 된다. 게이트웨이도 같은 이유로 403 에는 리다이렉트를 주지 않는다.
 *
 * 지금은 라우트 단위 권한이 "인증됐는가" 뿐이라 도달 경로가 드물지만, Phase 9 에서 역할·테넌트
 * 게이트가 붙으면 주 경로가 된다.
 */
@Component
class ProblemDetailAccessDeniedHandler(
  private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {
  private val delegate = BearerTokenAccessDeniedHandler()

  override fun handle(
    request: HttpServletRequest,
    response: HttpServletResponse,
    accessDeniedException: AccessDeniedException,
  ) {
    delegate.handle(request, response, accessDeniedException)
    response.writeProblem(objectMapper, request)
  }
}

/**
 * 위임이 정한 상태코드를 **읽어서** 그에 맞는 Problem Detail 본문을 쓴다.
 *
 * 보안 필터 단계에서는 `@RestController` 의 메시지 컨버터를 쓸 수 없다 — 아직 디스패처에 닿지
 * 않았기 때문이다. 그래서 응답을 직접 쓴다.
 *
 * ⚠️ 응답 본문에 거부 사유를 **자세히 적지 않는다.** "토큰 서명이 틀렸다" vs "aud 가 다르다" 를
 * 본문으로 구분해 주면 공격자에게 탐색 신호가 된다. 진단에 필요한 상세는 표준
 * `WWW-Authenticate` 헤더에 이미 담겨 있고, 그건 정상적인 클라이언트가 읽는 자리다.
 */
private fun HttpServletResponse.writeProblem(
  objectMapper: ObjectMapper,
  request: HttpServletRequest,
) {
  val resolved = HttpStatus.resolve(status) ?: HttpStatus.UNAUTHORIZED
  val problem =
    ProblemDetail.forStatusAndDetail(resolved, resolved.detailMessage()).apply {
      title = resolved.reasonPhrase
      instance = URI.create(request.requestURI)
      setProperty("reasonCode", resolved.reasonCode())
    }

  contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
  characterEncoding = Charsets.UTF_8.name()
  setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
  objectMapper.writeValue(writer, problem)
}

/** 게이트웨이의 `reasonCode` 어휘와 맞춘다 — 클라이언트가 두 벌을 외우지 않도록. */
private fun HttpStatus.reasonCode(): String =
  when (this) {
    HttpStatus.UNAUTHORIZED -> "authentication_required"
    HttpStatus.FORBIDDEN -> "access_denied"
    else -> "invalid_request"
  }

private fun HttpStatus.detailMessage(): String =
  when (this) {
    HttpStatus.UNAUTHORIZED -> "유효한 액세스 토큰이 필요합니다."
    HttpStatus.FORBIDDEN -> "이 작업을 수행할 권한이 없습니다."
    else -> "요청을 처리할 수 없습니다."
  }
