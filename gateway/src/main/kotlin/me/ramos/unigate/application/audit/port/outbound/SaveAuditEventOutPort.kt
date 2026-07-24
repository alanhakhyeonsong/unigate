package me.ramos.unigate.application.audit.port.outbound

import me.ramos.unigate.domain.audit.model.AuditEvent

/**
 * 감사 이벤트 저장 아웃바운드 포트.
 *
 * 저장 기술(R2DBC/Postgres)은 어댑터(r2dbcOut)에 봉인한다. UseCase 는 "어디에 어떻게" 저장되는지 모른다.
 */
interface SaveAuditEventOutPort {
  suspend fun save(event: AuditEvent)
}
