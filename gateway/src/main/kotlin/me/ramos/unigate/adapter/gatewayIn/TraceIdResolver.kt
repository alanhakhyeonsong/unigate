package me.ramos.unigate.adapter.gatewayIn

import io.micrometer.tracing.Tracer
import org.springframework.stereotype.Component

/**
 * 현재 요청의 traceId 를 꺼내는 어댑터 내부 헬퍼.
 *
 * ## 왜 application/domain 이 아니라 adapter 에 두는가
 * traceId 는 도메인 개념이 아니라 **관측 인프라의 산물**이다. `AuditEvent.traceId` 는 문자열일 뿐이고,
 * 그 값이 Micrometer 에서 오는지 다른 곳에서 오는지는 도메인이 알 필요가 없다. 그래서 Micrometer
 * 의존은 경계(adapter)에서 끊고, 안쪽으로는 평범한 `String?` 만 넘긴다.
 *
 * > Phase 5(헥사고날 완성) 재검토 지점: 감사 기록 시 traceId 를 호출부가 매번 채우는 대신
 * > `TraceContextPort` 같은 outbound 포트로 뽑아 UseCase 가 자동으로 채우게 할 수 있다.
 * > 지금은 호출부가 3곳뿐이라 포트를 늘리는 비용이 이득보다 커서 미룬다.
 *
 * ## null 이 정상값이다
 * 다음 경우 traceId 가 없다. 호출부는 반드시 null 을 견뎌야 한다.
 * - 샘플링에서 제외된 요청 (`management.tracing.sampling.probability` < 1.0)
 * - 트레이싱이 꺼진 환경 (`management.tracing.enabled=false`)
 * - 요청 스코프 밖에서 호출된 경우
 */
@Component
class TraceIdResolver(
  private val tracer: Tracer,
) {
  /**
   * `spring.reactor.context-propagation=auto` 덕분에 reactive 체인 중간에서 불러도 현재 span 이 잡힌다.
   * 그 설정이 없으면 스레드가 바뀌는 순간 null 이 되거나 **다른 요청의 span** 을 볼 수 있다.
   */
  fun currentTraceId(): String? = tracer.currentSpan()?.context()?.traceId()
}
