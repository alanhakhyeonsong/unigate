package me.ramos.unigate.iam.adapter.jpaOut.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import me.ramos.unigate.iam.application.outbox.model.OutboxEventType
import me.ramos.unigate.iam.application.outbox.model.OutboxStatus
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * `outbox_record` JPA 엔티티.
 *
 * ## 도메인/application 모델과 **분리**되어 있다
 * `OutboxRecord`(application)와 필드가 거의 같은데도 따로 둔다. ArchUnit 이 `application` 의
 * `jakarta.persistence` 의존을 금지하기 때문이고, 그 금지에는 이유가 있다 — 엔티티에는 영속성
 * 사정(식별자 전략, 지연로딩, 기본 생성자, `open`)이 따라붙고 그게 모델 설계를 지배하게 된다.
 *
 * 대가는 **매핑 코드**다. 지금은 필드가 적어 부담이 작지만, 모델이 커지면 이 비용을 다시 저울질해야 한다.
 *
 * ## `@Enumerated(STRING)` 인 이유
 * `ORDINAL` 은 enum 순서가 바뀌면 기존 데이터의 의미가 통째로 어긋난다. 상태 값은 DB 에서 눈으로
 * 읽혀야 운영이 가능하기도 하다.
 */
@Entity
@Table(name = "outbox_record")
class OutboxRecordEntity(
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  val id: Long? = null,
  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 64)
  val eventType: OutboxEventType,
  // jsonb 컬럼. Hibernate 6 의 SqlTypes.JSON 으로 매핑한다.
  // payload 자체는 문자열로 다룬다 — 워커는 통째로 역직렬화할 뿐 부분 조회를 하지 않는다.
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  val payload: String,
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  var status: OutboxStatus,
  @Column(name = "attempts", nullable = false)
  var attempts: Int,
  @Column(name = "next_attempt_at", nullable = false)
  var nextAttemptAt: Instant,
  @Column(name = "last_error")
  var lastError: String? = null,
  @Column(name = "dead_at")
  var deadAt: Instant? = null,
  @Column(name = "last_exception_class", length = 255)
  var lastExceptionClass: String? = null,
  @Column(name = "created_at", nullable = false, updatable = false)
  val createdAt: Instant = Instant.now(),
  @Column(name = "updated_at", nullable = false)
  var updatedAt: Instant = Instant.now(),
)
