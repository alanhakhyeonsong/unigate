package me.ramos.unigate.adapter.gatewayIn

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.reactor.mono
import me.ramos.unigate.application.audit.dto.RecordAuditEventCommand
import me.ramos.unigate.application.audit.port.inbound.RecordAuditEventInPort
import me.ramos.unigate.domain.audit.enums.AuditEventType
import org.slf4j.LoggerFactory
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
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

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

/** 로그인 성공 → LOGIN_SUCCESS 감사 + `unigate.auth.login{result=success}` 증가 후 기본 리다이렉트. */
@Component
class AuditingAuthenticationSuccessHandler(
  private val recordAuditEventInPort: RecordAuditEventInPort,
  private val meterRegistry: MeterRegistry,
  private val traceIdResolver: TraceIdResolver,
) : ServerAuthenticationSuccessHandler {
  private val log = LoggerFactory.getLogger(javaClass)
  private val delegate = RedirectServerAuthenticationSuccessHandler()

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
