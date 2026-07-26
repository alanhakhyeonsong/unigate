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
 * ## Phase 5 결론: 포트로 승격하지 않는다
 * "traceId 를 호출부가 매번 채우는 대신 `TraceContextPort` 로 뽑아 UseCase 가 자동으로 채우게 하자"를
 * 검토했고, **하지 않기로 했다.** 이유는 두 가지이며 둘 다 실측에 근거한다.
 *
 * 1. **그렇게 하면 오히려 깨진다.** `RecordAuditEventUseCase.record()` 는 `suspend` 함수다. 그 안에서
 *    포트를 통해 traceId 를 읽으면 코루틴 컨텍스트라 ThreadLocal 이 복원되지 않아 **다시 null 이 된다**
 *    — Phase 4 에서 겪은 그 함정을 구조로 고정하는 셈이다
 *    (`docs/learning/13-distributed-tracing-reactor-context.md` §5). traceId 는 **Reactor 연산자 경계에서
 *    읽어 값으로 넘겨야** 하고, 그 경계는 adapter 에 있다.
 * 2. **포트는 application 이 필요로 하는 것을 정의한다.** 지금 application 은 traceId 를 "필요"로 하지
 *    않는다 — Command 로 받아 그대로 저장할 뿐이다. 소비자가 전부 adapter 인데 포트로 올리는 것은
 *    추상화를 위한 추상화다.
 *
 * 필요해지는 시점: application 이 스스로 요청 맥락을 알아야 할 때(예: 테넌트 판별 — Phase 9).
 * 그때는 `suspend` 경계 문제를 함께 풀어야 한다.
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
