package me.ramos.unigate.domain.audit.enums

/**
 * 감사 이벤트 종류. audit_log.event_type 컬럼에 이름 그대로 저장된다.
 *
 * 순수 도메인 enum — Spring/DB 의존 없음. "무슨 일이 일어났는가"만 표현한다.
 */
enum class AuditEventType {
  LOGIN_SUCCESS,
  LOGIN_FAILURE,
  LOGOUT,
}
