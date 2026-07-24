package me.ramos.unigate.domain.audit.model

import me.ramos.unigate.domain.audit.enums.AuditEventType

/**
 * 감사 이벤트 — "누가·무엇을·왜"를 남기는 도메인 기록.
 *
 * 순수 도메인 모델(Spring/DB/Jackson 의존 없음). detail 은 이벤트별 부가 맥락을 담는 자유 형태이며,
 * JSON(JSONB)으로의 직렬화는 adapter(r2dbcOut)가 담당한다 — 도메인은 저장 형식을 모른다.
 */
data class AuditEvent(
  val type: AuditEventType,
  val subject: String? = null,
  val clientId: String? = null,
  val audience: String? = null,
  val reasonCode: String? = null,
  val traceId: String? = null,
  val detail: Map<String, Any?>? = null,
)
