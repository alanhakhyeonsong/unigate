package me.ramos.unigate.iam.adapter.jpaOut

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.tracing.Tracer
import me.ramos.unigate.iam.adapter.jpaOut.entity.AuditLogEntity
import me.ramos.unigate.iam.adapter.jpaOut.repository.AuditLogJpaRepository
import me.ramos.unigate.iam.application.audit.port.outbound.RecordAuditEventOutPort
import me.ramos.unigate.iam.domain.audit.model.AuditEvent
import org.springframework.stereotype.Component

/**
 * 감사 이벤트를 `audit_log` 에 저장하는 JPA 어댑터 — [RecordAuditEventOutPort] 구현.
 *
 * ## 이 어댑터가 하는 일은 저장만이 아니다 — **traceId 를 찍는다**
 * 도메인 [AuditEvent] 에는 traceId 가 없다. 유스케이스 안쪽에서 만들어지는 값이라, 거기까지
 * traceId 를 나르려면 모든 업무 Command 에 필드를 끼워야 하기 때문이다([AuditEvent] KDoc).
 *
 * 그래서 저장 직전에 현재 span 에서 읽어 채운다. **이 지점이 안전한 이유는 Servlet 스택이기
 * 때문**이다 — 요청 하나가 한 스레드에서 처리되므로 ThreadLocal 기반 span 조회가 그대로 동작한다.
 *
 * 게이트웨이에서는 이 방식이 **불가능했다.** reactive 라 연산자마다 스레드가 바뀌고, 특히
 * `mono { }` 안에서 읽은 traceId 는 **항상 null** 이었다(`docs/learning/13` §5). 그래서 GW 는
 * 경계에서 읽어 값으로 넘긴다. 같은 목적, 스택 때문에 정반대 배치가 된 사례다.
 *
 * ## traceId 가 null 일 수 있다 — 정상이다
 * 샘플링에서 빠졌거나, 트레이싱이 꺼졌거나, 요청 스코프 밖에서 호출된 경우.
 *
 * ## ⚠️ 워커의 traceId 는 **없는 게 아니라 다르다** (실측으로 정정한 가정)
 * "스케줄러에서 부르니 traceId 가 null 이겠지" 라고 예상했는데, 실제로 띄워 보니 **값이 있었다** —
 * Spring 의 `@Scheduled` 실행이 Micrometer observation 으로 감싸져 **자기 span** 을 만들기 때문이다.
 *
 * 결론은 같지만 이유가 다르다: 가입 요청과 신원 생성은 **서로 다른 trace** 라, traceId 로 이을 수 없다.
 * 그 둘을 잇는 것은 `target_email` 이다. "null 이니까 못 잇는다" 로 이해하면 나중에 값이 찍히는 걸
 * 보고 "이제 이을 수 있다" 고 잘못 판단하게 된다.
 *
 * ```
 * USER_REGISTERED   trace_id=83684b6c12bc265d43fd7cd6467fb41d   ← HTTP 요청
 * IDENTITY_CREATED  trace_id=c4e154a7e981ecfeb7a6fad78e822cb3   ← 스케줄러 (다른 trace)
 * ```
 *
 * ## JSONB 직렬화는 어댑터의 일
 * 도메인의 `detail` 은 `Map` 이다. JSON 문자열로 바꾸는 것은 **저장 형식**의 문제라 여기서 한다
 * (ArchUnit 이 `application` 의 `com.fasterxml` 의존을 막는 이유이기도 하다).
 */
@Component
class JpaAuditLogAdapter(
  private val repository: AuditLogJpaRepository,
  private val objectMapper: ObjectMapper,
  private val tracer: Tracer,
) : RecordAuditEventOutPort {
  override fun record(event: AuditEvent) {
    repository.save(
      AuditLogEntity(
        eventType = event.type,
        actorRef = event.actorRef,
        targetRef = event.targetRef,
        targetEmail = event.targetEmail,
        reasonCode = event.reasonCode,
        traceId = currentTraceId(),
        detail = event.detail?.let { objectMapper.writeValueAsString(it) },
      ),
    )
  }

  private fun currentTraceId(): String? = tracer.currentSpan()?.context()?.traceId()
}
