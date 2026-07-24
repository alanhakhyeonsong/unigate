package me.ramos.unigate.application.audit.dto

import me.ramos.unigate.domain.audit.enums.AuditEventType

/**
 * 감사 이벤트 기록 요청. 경계 어댑터(gatewayIn 인증 핸들러)가 채워 InPort 로 넘긴다.
 */
data class RecordAuditEventCommand(
  val type: AuditEventType,
  val subject: String? = null,
  val clientId: String? = null,
  val audience: String? = null,
  val reasonCode: String? = null,
  val traceId: String? = null,
  val detail: Map<String, Any?>? = null,
)
