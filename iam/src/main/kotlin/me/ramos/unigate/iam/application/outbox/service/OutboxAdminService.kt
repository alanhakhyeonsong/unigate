package me.ramos.unigate.iam.application.outbox.service

import me.ramos.unigate.iam.application.audit.port.outbound.RecordAuditEventOutPort
import me.ramos.unigate.iam.application.outbox.model.OutboxRecord
import me.ramos.unigate.iam.application.outbox.model.OutboxStatus
import me.ramos.unigate.iam.application.outbox.port.outbound.OutboxPort
import me.ramos.unigate.iam.domain.audit.enums.AuditEventType
import me.ramos.unigate.iam.domain.audit.model.AuditEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * DLQ 운영 — 죽은 outbox 레코드의 조회와 재처리 (Phase 9c).
 *
 * ## 이것이 "DLQ" 의 실체다
 * Phase 9b 에서 저장소를 DB 그대로 두기로 하면서, DLQ 를 **새 저장소가 아니라 새 운영 인터페이스**로
 * 만들기로 했다. 메트릭이 "쌓였다"를 알려주고, 여기가 "무엇이 왜 죽었고 다시 시도한다"를 담당한다.
 * 둘이 갖춰져야 "관리자가 수동 처리" 라는 방침이 실제로 성립한다.
 *
 * ## ⚠️ 여기부터는 **남의 자원**을 다룬다
 * 프로필 API 는 대상을 토큰 `sub` 로만 정해 인가 검사가 필요 없었다(`docs/learning/20`).
 * 관리 기능은 그 전략이 통하지 않으므로 인가가 **명시적으로** 있어야 한다 —
 * `IamSecurityConfig` 가 `/iam/admin` 하위 전체에 `unigate-admin` 권한을 요구한다.
 *
 * 그리고 행위자를 감사에 반드시 남긴다. 지금까지의 감사는 `actor == target` 이거나 워커 사건이라
 * 행위자가 없었지만, **여기서 처음으로 "누가 남의 것을 건드렸나" 가 의미를 갖는다.**
 */
@Service
class OutboxAdminService(
  private val outboxPort: OutboxPort,
  private val recordAuditEventOutPort: RecordAuditEventOutPort,
  private val clock: Clock,
) {
  private val log = LoggerFactory.getLogger(javaClass)

  /**
   * 죽은 레코드를 최근 순으로 조회한다.
   *
   * ⚠️ **조회는 감사에 남기지 않는다.** 남기면 액세스 로그가 되어 정작 중요한 변경이 묻힌다
   * (P8g 에서 정한 원칙 그대로).
   */
  @Transactional(readOnly = true)
  fun listDead(limit: Int): List<OutboxRecord> = outboxPort.findDead(limit.coerceIn(1, MAX_LIMIT))

  /**
   * 죽은 레코드를 다시 처리 대상으로 되돌린다.
   *
   * 실제 처리는 여기서 하지 않는다 — 상태만 되돌리고 **워커가 정상 클레임 경로로** 가져간다.
   * 재처리 경로를 따로 만들면 워커와 두 벌이 되어, 한쪽만 고치는 사고가 난다.
   *
   * @param actorRef 재처리를 지시한 관리자의 `sub`. 감사에 남는다.
   * @throws OutboxRecordNotFoundException 대상이 없다
   * @throws OutboxNotDeadException 죽지 않은 레코드다(이미 처리 중이거나 완료됐다)
   */
  @Transactional
  fun requeue(
    id: Long,
    actorRef: String,
  ): OutboxRecord {
    val record = outboxPort.findById(id) ?: throw OutboxRecordNotFoundException(id)

    // ⚠️ 상태를 확인하지 않으면 **이미 PENDING 인 것을 또 되돌려** attempts 를 0 으로 지운다.
    // 그 순간 진행 중이던 백오프가 사라져 외부 시스템을 즉시 다시 두드린다.
    if (record.status != OutboxStatus.DEAD) {
      throw OutboxNotDeadException(id, record.status)
    }

    val now = Instant.now(clock)
    val requeued = outboxPort.update(record.requeued(now))

    recordAuditEventOutPort.record(
      AuditEvent(
        type = AuditEventType.OUTBOX_REQUEUED,
        // 지금까지의 감사와 달리 actor 와 target 이 **다르다.** 이 컬럼을 P8g 에서 미리 나눠둔
        // 이유가 여기서 현실이 된다 — 그때 합쳐 뒀다면 이 사건을 기록할 방법이 없었다.
        actorRef = actorRef,
        reasonCode = record.lastError,
        detail =
          mapOf(
            "outboxRecordId" to id,
            "eventType" to record.eventType.name,
            "previousAttempts" to record.attempts,
            "lastExceptionClass" to record.lastExceptionClass,
          ),
      ),
    )

    log.info("outbox 재처리 지시 id={} actor={} 이전시도={}", id, actorRef, record.attempts)
    return requeued
  }

  private companion object {
    /**
     * 한 번에 조회할 최대 건수.
     *
     * 상한을 두지 않으면 DEAD 가 수만 건 쌓인 상황 — **정확히 이 API 가 가장 필요한 상황** — 에서
     * 응답이 거대해져 조회 자체가 실패한다.
     */
    const val MAX_LIMIT = 200
  }
}

/** 재처리 대상 outbox 레코드가 없다. */
class OutboxRecordNotFoundException(
  val id: Long,
) : RuntimeException("outbox 레코드를 찾을 수 없습니다: id=$id")

/**
 * 죽지 않은 레코드를 재처리하려 했다.
 *
 * 실수 방지용이다 — 이미 PENDING 인 것을 되돌리면 백오프가 사라지고, COMPLETED 를 되돌리면
 * **이미 끝난 작업을 다시 수행**한다(외부 호출이 멱등이라 결과는 같지만 무의미한 호출이다).
 */
class OutboxNotDeadException(
  val id: Long,
  val status: OutboxStatus,
) : RuntimeException("DEAD 상태가 아닌 레코드는 재처리할 수 없습니다: id=$id status=$status")
