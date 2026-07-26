package me.ramos.unigate.adapter.loggingOut

import me.ramos.unigate.application.audit.port.outbound.SaveAuditEventOutPort
import me.ramos.unigate.domain.audit.model.AuditEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 감사 이벤트를 **구조화 로그**로 남기는 [SaveAuditEventOutPort] 의 두 번째 구현 (Phase 5).
 *
 * ## 왜 만들었나 — 교체가능성의 실증
 * Phase 4 까지 이 포트의 구현은 [me.ramos.unigate.adapter.r2dbcOut.R2dbcAuditLogAdapter] 하나뿐이었다.
 * 구현이 하나뿐인 인터페이스는 **추상화가 맞는지 증명되지 않은 상태**다. 포트가 특정 구현의 모양을
 * 그대로 베낀 것에 불과할 수 있기 때문이다(이른바 "새는 추상화").
 *
 * 두 번째 구현을 붙여 보면 그게 드러난다. 이 어댑터를 쓰는 데 `application` 이나 `domain` 코드가
 * **한 줄도 바뀌지 않았다면** 포트 경계가 제대로 그어진 것이다. 실제로 그랬다
 * (`AuditSinkSwappabilityTest` 로 회귀 고정).
 *
 * ## 실제 쓸모도 있다 (연습용이 아니다)
 * - DB 없이 로컬에서 감사 흐름만 확인할 때
 * - 감사를 로그 수집 파이프라인으로 보내는 조직(감사 저장소가 DB 가 아닌 경우)
 *
 * ## 선택 방법
 * `unigate.audit.sink=log` 일 때만 활성화된다. 기본값(미설정)은 R2DBC 다 — 감사는 조회·보존이
 * 필요하므로 DB 가 기본이어야 하고, 설정을 빠뜨렸을 때 **조용히 로그로 새는 일**이 없어야 한다.
 *
 * ## 로깅 주의
 * `subject` 는 Keycloak sub(UUID)이고 `detail` 에는 preferredUsername 정도가 들어간다.
 * 토큰·비밀번호는 애초에 [AuditEvent] 에 담기지 않는다(`CLAUDE.md` §8).
 */
@Component
@ConditionalOnProperty(name = ["unigate.audit.sink"], havingValue = "log")
class LoggingAuditLogAdapter : SaveAuditEventOutPort {
  private val log = LoggerFactory.getLogger(javaClass)

  /**
   * `suspend` 지만 실제로 중단하지 않는다. SLF4J 호출은 논블로킹(메모리 버퍼)이라 이벤트 루프를
   * 막지 않기 때문이다.
   *
   * ⚠️ 단, 파일 appender 를 동기(non-async)로 쓰면 디스크 I/O 가 이벤트 루프에서 일어난다.
   * 운영에서 이 어댑터를 쓴다면 `AsyncAppender` 를 반드시 확인해야 한다.
   */
  override suspend fun save(event: AuditEvent) {
    // 키-값 구조로 남긴다(전역 지침 §4). 파싱 가능해야 감사로서 쓸모가 있다.
    log.info(
      "audit event_type={} subject={} client_id={} audience={} reason_code={} trace_id={} detail={}",
      event.type.name,
      event.subject,
      event.clientId,
      event.audience,
      event.reasonCode,
      event.traceId,
      event.detail,
    )
  }
}
