package me.ramos.unigate.iam.application.audit.port.outbound

import me.ramos.unigate.iam.domain.audit.model.AuditEvent

/**
 * 감사 이벤트 저장 포트.
 *
 * ## InPort 가 없는 것이 의도다
 * 게이트웨이에는 `RecordAuditEventInPort` 가 있다. 거기서는 **어댑터(인증 핸들러)가** 감사를
 * 기록하므로 안쪽으로 들어오는 입구가 필요했다.
 *
 * IAM 은 반대다. 감사를 남기는 주체가 **유스케이스 자신**이라 바깥에서 들어올 일이 없다.
 * 소비자 없는 InPort 를 만드는 것은 추상화를 위한 추상화다(`PHASE_ROADMAP` Phase 5 재정의).
 *
 * ## 왜 outbox 가 아닌가
 * outbox 는 **이중 시스템 쓰기**(IAM DB + Keycloak)의 원자성을 만들려고 도입한 장치다. 감사 기록은
 * 업무 데이터와 **같은 PostgreSQL** 로의 단일 쓰기라, 이미 같은 `@Transactional` 안에 있으면 원자성이
 * 보장된다. 여기에 outbox 를 얹으면 얻는 것 없이 지연과 실패 모드만 늘어난다.
 *
 * (감사를 SIEM 같은 **외부 시스템**으로 내보내야 할 때는 이야기가 달라진다. 그때 다시 판단한다.)
 *
 * ## 실패하면 업무도 롤백된다 — fail-closed
 * 호출자가 예외를 삼키지 않으므로, 감사 저장이 실패하면 트랜잭션 전체가 롤백된다.
 * **"감사 없는 도메인 변경" 을 만들지 않겠다**는 선택이다.
 *
 * 대가는 가용성이다 — 감사 테이블 문제가 가입을 막는다. 게이트웨이는 정반대로 택했다(감사 실패가
 * 로그인을 막지 않게 호출부에서 삼킨다). 인증 흐름은 막히면 사용자가 아무것도 못 하지만,
 * IAM 의 도메인 변경은 **기록 없이 일어나는 편이 더 나쁘다**고 판단했다.
 */
interface RecordAuditEventOutPort {
  /**
   * 감사 이벤트를 저장한다.
   *
   * `traceId` 는 **구현체가 채운다** — 호출자는 넘기지 않는다([AuditEvent] KDoc 참조).
   *
   * @param event 저장할 도메인 이벤트
   */
  fun record(event: AuditEvent)
}
