package me.ramos.unigate.application.audit.service

import me.ramos.unigate.application.audit.dto.RecordAuditEventCommand
import me.ramos.unigate.application.audit.port.inbound.RecordAuditEventInPort
import me.ramos.unigate.application.audit.port.outbound.SaveAuditEventOutPort
import me.ramos.unigate.domain.audit.model.AuditEvent
import org.springframework.stereotype.Service

/**
 * 감사 이벤트 기록 UseCase — Command 를 도메인 이벤트로 바꿔 OutPort 로 저장한다.
 *
 * 오케스트레이션만 담당한다(변환 + 위임). 저장 실패를 여기서 삼키지 않는다 — "감사가 로그인 흐름을
 * 막지 않아야 한다"는 판단은 **호출부(인증 핸들러)**의 정책이므로 거기서 처리한다(경계 책임 분리).
 */
@Service
class RecordAuditEventUseCase(
  private val saveAuditEventOutPort: SaveAuditEventOutPort,
) : RecordAuditEventInPort {
  override suspend fun record(command: RecordAuditEventCommand) {
    saveAuditEventOutPort.save(command.toEvent())
  }

  private fun RecordAuditEventCommand.toEvent(): AuditEvent =
    AuditEvent(
      type = type,
      subject = subject,
      clientId = clientId,
      audience = audience,
      reasonCode = reasonCode,
      traceId = traceId,
      detail = detail,
    )
}
