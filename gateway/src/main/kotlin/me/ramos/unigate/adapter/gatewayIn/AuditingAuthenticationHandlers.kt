package me.ramos.unigate.adapter.gatewayIn

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.reactor.mono
import me.ramos.unigate.application.audit.dto.RecordAuditEventCommand
import me.ramos.unigate.application.audit.port.inbound.RecordAuditEventInPort
import me.ramos.unigate.domain.audit.enums.AuditEventType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.server.WebFilterExchange
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler
import org.springframework.security.web.server.authentication.logout.ServerLogoutHandler
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.net.URI

/**
 * 인증 결과를 감사로그 + 메트릭으로 남기는 driving 어댑터.
 *
 * ## 왜 이벤트 리스너가 아니라 핸들러인가
 * WebFlux 는 Servlet 과 달리 로그인 성공/실패에 대한 `AuthenticationEventPublisher` 를 기본 발행하지
 * 않는다. 그래서 oauth2Login 의 success/failure 핸들러를 직접 갈아끼워 그 지점에서 감사·메트릭을 남긴다.
 *
 * ## 감사가 로그인을 막지 않게 한다(경계 정책)
 * 감사 저장 실패가 로그인 흐름을 깨선 안 된다. 그래서 여기서 `runCatching` 으로 감싸 실패를 **로그로만**
 * 남기고 리다이렉트는 그대로 진행한다. (UseCase 는 예외를 삼키지 않으며, 삼킬지 말지는 이 호출부가 정한다.)
 *
 * ## traceId (Phase 4) — 반드시 `mono { }` **밖**에서 읽는다
 * 각 이벤트에 현재 요청의 traceId 를 붙여, 감사로그 한 줄에서 애플리케이션 로그·다운스트림 span 까지
 * 곧장 이어지게 한다.
 *
 * ⚠️ 겪은 실패: `mono { }` 코루틴 **안**에서 `traceIdResolver.currentTraceId()` 를 부르면 항상 null 이라
 * `audit_log.trace_id` 가 전부 비었다. 컴파일도 되고 예외도 없어서 **DB 를 열어보기 전까지 모른다.**
 *
 * 원인은 `spring.reactor.context-propagation=auto` 의 적용 범위다. 이 설정은 **Reactor 연산자 경계**에서
 * ThreadLocal 을 복원하는데, `mono { }` 의 본문은 Reactor 연산자가 아니라 코루틴 실행 컨텍스트다.
 * Reactor Context 는 코루틴으로 넘어가지만 **ThreadLocal(= Tracer 가 보는 곳)은 복원되지 않는다.**
 *
 * 그래서 `Mono.defer { }`(= Reactor 연산자, 구독 시점 실행) 안에서 traceId 를 먼저 읽어 값으로 넘긴다.
 * 조립 시점이 아니라 **구독 시점**이어야 하므로 `defer` 가 필요하다.
 */

private const val METRIC_LOGIN = "unigate.auth.login"
private const val METRIC_LOGOUT = "unigate.auth.logout"

/**
 * 로그인 후 착지 URI. 비어 있으면 **null** 을 돌려 기본값(`/`)을 그대로 두게 한다.
 *
 * 빈 값에 `URI.create("")` 를 넣으면 예외 없이 통과하지만 `Location:` 이 빈 문자열이 되어
 * 브라우저가 현재 URL 로 되돌아온다 — 로그인 루프처럼 보인다. 그래서 "설정 없음" 과
 * "빈 문자열" 을 같게 취급하고 **아예 손대지 않는다.**
 */
internal fun postLoginLocation(frontendBaseUri: String): URI? =
  frontendBaseUri.trim().takeIf { it.isNotEmpty() }?.let(URI::create)

/**
 * 로그인 성공 → LOGIN_SUCCESS 감사 + `unigate.auth.login{result=success}` 증가 후 리다이렉트.
 *
 * ## 어디로 리다이렉트하는가 — FE 가 다른 호스트면 기본값이 틀린다
 * [RedirectServerAuthenticationSuccessHandler] 를 **인자 없이** 만들면 착지가 `/` 다. FE 를 다른
 * 호스트에 두는 배포에서 `/` 는 게이트웨이 루트이고 거기엔 FE 가 없다.
 *
 * ## ⚠️ `setLocation` 은 저장된 요청을 이기지 못한다 (2026-08-02 정정)
 * 이 KDoc 은 원래 *"진입점이 401 + `loginUrl` 을 주는 방식이라 원래 요청을 저장하지 않는다"* 고
 * 적혀 있었다. **틀린 문장이었다.** [ProblemDetailAuthenticationEntryPoint] 는 **분기형**이고,
 * 브라우저 top-level 이동 분기에서 `RedirectServerAuthenticationEntryPoint` 에 위임하는데 그
 * 구현이 `saveRequest()` 를 부른다. 401 분기만 보고 단정한 오독이었다.
 *
 * 그리고 이 핸들러의 위임체는
 * `requestCache.getRedirectUri(exchange).defaultIfEmpty(location)` 이라 **저장된 요청이 우선**이다.
 * 즉 아래 `setLocation` 은 저장분이 없을 때만 쓰인다.
 *
 * alpha 실측에서 실제로 터졌다 — 미인증 로그아웃이 남긴 `/login?logout` 이 저장돼 로그인 성공 후
 * 그리로 착지하고 404 가 났다.
 *
 * ## 그래서 세 곳에서 끈다 (한 곳만으로는 안 됐다)
 * | 위치 | 무엇을 막나 | 왜 필요한가 |
 * |---|---|---|
 * | [ProblemDetailAuthenticationEntryPoint] | **저장**을 막는다 | 실제로 저장하는 지점 |
 * | 아래 `setRequestCache` | **읽기**를 막는다 | 다른 진입점이 저장하게 돼도 착지가 안 흔들린다 |
 * | `SecurityConfig.requestCache` | Spring 이 만드는 컴포넌트 | 위 둘에는 **닿지 않는다**(아래 참조) |
 *
 * ⚠️ `http.requestCache { ... }` **하나만으로는 고쳐지지 않는다.** 두 핸들러 모두 우리가 `new` 로
 * 만든 객체라 각자 자기 소유의 `WebSessionServerRequestCache` 를 들고 있고, 빌더 설정이 그 인스턴스에
 * 주입되지 않는다. 실제로 그것만 넣고 테스트를 돌렸을 때 여전히 저장이 일어났다.
 *
 * same-origin 배포(로컬 Vite dev proxy)에서는 `/` 가 곧 FE 라 아무 문제가 없다.
 * **FE 를 다른 호스트에 두는 순간** `/` 는 게이트웨이 루트가 되고, 거기엔 FE 가 없어 401 이 뜬다.
 * 로그인은 성공했는데 화면이 안 뜨는 형태라 원인이 Keycloak 으로 오해되기 쉽다 —
 * 그러나 Keycloak 의 `redirectUris` 는 **인가 코드를 배달할 주소**일 뿐이고, 코드가 콜백에 도착한
 * 시점에 Keycloak 의 역할은 끝난다. 그 다음 착지를 정하는 것은 여기다.
 *
 * 그래서 `unigate.frontend.base-uri` 가 있으면 그리로 보낸다. 비어 있으면 종전대로 `/` 다 —
 * 로컬 same-origin 동작을 바꾸지 않기 위해서다. 실측 기록은
 * `docs/learning/26-bff-spa-integration.md` §5.
 */
@Component
class AuditingAuthenticationSuccessHandler(
  private val recordAuditEventInPort: RecordAuditEventInPort,
  private val meterRegistry: MeterRegistry,
  private val traceIdResolver: TraceIdResolver,
  @Value("\${unigate.frontend.base-uri:}") frontendBaseUri: String,
) : ServerAuthenticationSuccessHandler {
  private val log = LoggerFactory.getLogger(javaClass)
  private val delegate =
    RedirectServerAuthenticationSuccessHandler().apply {
      // 읽는 쪽도 끈다. 저장하는 쪽([ProblemDetailAuthenticationEntryPoint])을 이미 껐지만,
      // 저장 경로가 하나라는 보장은 없다 — Spring 이 다른 진입점을 쓰게 되는 순간 되살아난다.
      // **착지를 정하는 곳이 여기 하나뿐이어야 한다**는 것이 이 설정의 요점이다.
      setRequestCache(NoOpServerRequestCache.getInstance())
      postLoginLocation(frontendBaseUri)?.let { setLocation(it) }
    }

  override fun onAuthenticationSuccess(
    webFilterExchange: WebFilterExchange,
    authentication: Authentication,
  ): Mono<Void> =
    Mono
      .defer {
        // Reactor 연산자 경계 — 여기서는 ThreadLocal 이 복원돼 있어 traceId 가 잡힌다.
        val traceId = traceIdResolver.currentTraceId()
        mono { audit(authentication, traceId) }
      }.then(delegate.onAuthenticationSuccess(webFilterExchange, authentication))

  private suspend fun audit(
    authentication: Authentication,
    traceId: String?,
  ) {
    Counter
      .builder(METRIC_LOGIN)
      .tag("result", "success")
      .register(meterRegistry)
      .increment()
    val oidcUser = authentication.principal as? OidcUser
    runCatching {
      recordAuditEventInPort.record(
        RecordAuditEventCommand(
          type = AuditEventType.LOGIN_SUCCESS,
          subject = oidcUser?.subject ?: authentication.name,
          clientId = (authentication as? OAuth2AuthenticationToken)?.authorizedClientRegistrationId,
          traceId = traceId,
          detail = oidcUser?.preferredUsername?.let { mapOf("preferredUsername" to it) },
        ),
      )
    }.onFailure { log.warn("감사 기록 실패(LOGIN_SUCCESS) — 로그인은 계속 진행: {}", it.message) }
  }
}

/** 로그인 실패 → LOGIN_FAILURE 감사 + `unigate.auth.login{result=failure}` 증가 후 기본 실패 리다이렉트. */
@Component
class AuditingAuthenticationFailureHandler(
  private val recordAuditEventInPort: RecordAuditEventInPort,
  private val meterRegistry: MeterRegistry,
  private val traceIdResolver: TraceIdResolver,
) : ServerAuthenticationFailureHandler {
  private val log = LoggerFactory.getLogger(javaClass)
  private val delegate = RedirectServerAuthenticationFailureHandler("/login?error")

  override fun onAuthenticationFailure(
    webFilterExchange: WebFilterExchange,
    exception: AuthenticationException,
  ): Mono<Void> =
    Mono
      .defer {
        val traceId = traceIdResolver.currentTraceId()
        mono { audit(exception, traceId) }
      }.then(delegate.onAuthenticationFailure(webFilterExchange, exception))

  private suspend fun audit(
    exception: AuthenticationException,
    traceId: String?,
  ) {
    Counter
      .builder(METRIC_LOGIN)
      .tag("result", "failure")
      .register(meterRegistry)
      .increment()
    // OAuth2 실패는 표준 error code 가 있다(invalid_grant 등). 그 밖은 예외 클래스명으로 분류.
    val reasonCode =
      (exception as? OAuth2AuthenticationException)?.error?.errorCode ?: exception.javaClass.simpleName
    runCatching {
      recordAuditEventInPort.record(
        RecordAuditEventCommand(
          type = AuditEventType.LOGIN_FAILURE,
          reasonCode = reasonCode,
          traceId = traceId,
          detail = exception.message?.let { mapOf("message" to it) },
        ),
      )
    }.onFailure { log.warn("감사 기록 실패(LOGIN_FAILURE): {}", it.message) }
  }
}

/**
 * 로그아웃 → LOGOUT 감사 + `unigate.auth.logout` 증가.
 *
 * SecurityConfig 의 logout DelegatingServerLogoutHandler 체인에 끼운다(리다이렉트 이전에 실행).
 */
@Component
class AuditingLogoutHandler(
  private val recordAuditEventInPort: RecordAuditEventInPort,
  private val meterRegistry: MeterRegistry,
  private val traceIdResolver: TraceIdResolver,
) : ServerLogoutHandler {
  private val log = LoggerFactory.getLogger(javaClass)

  override fun logout(
    exchange: WebFilterExchange,
    authentication: Authentication?,
  ): Mono<Void> =
    Mono
      .defer {
        val traceId = traceIdResolver.currentTraceId()
        mono {
          meterRegistry.counter(METRIC_LOGOUT).increment()
          runCatching {
            recordAuditEventInPort.record(
              RecordAuditEventCommand(
                type = AuditEventType.LOGOUT,
                subject = authentication?.name,
                traceId = traceId,
              ),
            )
          }.onFailure { log.warn("감사 기록 실패(LOGOUT): {}", it.message) }
        }
      }.then()
}
