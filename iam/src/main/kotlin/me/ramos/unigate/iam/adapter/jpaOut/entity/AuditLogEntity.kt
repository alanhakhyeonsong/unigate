package me.ramos.unigate.iam.adapter.jpaOut.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import me.ramos.unigate.iam.domain.audit.enums.AuditEventType
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * `audit_log` JPA 엔티티.
 *
 * ## `detail` 을 JSONB 로 매핑하는 법
 * `@JdbcTypeCode(SqlTypes.JSON)` 없이 `String` 을 넣으면 PostgreSQL 이
 * `column "detail" is of type jsonb but expression is of type character varying` 로 거절한다.
 * Hibernate 6 은 이 애노테이션 하나로 text↔jsonb 캐스팅을 처리한다.
 *
 * (게이트웨이는 R2DBC 라 이 문제를 SQL 의 `CAST(:detail AS jsonb)` 로 풀었다. 같은 문제, 다른 층위의 해법.)
 *
 * ## 전부 `val` 인 이유
 * 감사 기록은 **불변 이력**이다. 수정할 수 있는 필드를 두면 언젠가 수정된다 —
 * 그 순간 감사로서의 의미가 사라진다. 엔티티 수준에서 그 가능성을 없앤다.
 */
@Entity
@Table(name = "audit_log")
class AuditLogEntity(
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  val id: Long? = null,
  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 64)
  val eventType: AuditEventType,
  @Column(name = "actor_ref", length = 64)
  val actorRef: String? = null,
  @Column(name = "target_ref", length = 64)
  val targetRef: String? = null,
  @Column(name = "target_email", length = 255)
  val targetEmail: String? = null,
  @Column(name = "tenant_ref", length = 64)
  val tenantRef: String? = null,
  @Column(name = "reason_code", length = 64)
  val reasonCode: String? = null,
  @Column(name = "trace_id", length = 64)
  val traceId: String? = null,
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "detail")
  val detail: String? = null,
  @Column(name = "created_at", nullable = false, updatable = false)
  val createdAt: Instant = Instant.now(),
)
