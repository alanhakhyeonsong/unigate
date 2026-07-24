package me.ramos.unigate.application.audit.port.inbound

import me.ramos.unigate.application.audit.dto.RecordAuditEventCommand

/**
 * 감사 이벤트 기록 인바운드 포트(UseCase 계약).
 *
 * 경계 어댑터(gatewayIn)는 이 포트만 호출하고, 실제 저장은 UseCase → OutPort 로 흐른다.
 */
interface RecordAuditEventInPort {
  suspend fun record(command: RecordAuditEventCommand)
}
