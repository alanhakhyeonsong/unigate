package me.ramos.unigate.iam.adapter.jpaOut

import me.ramos.unigate.iam.adapter.jpaOut.entity.OutboxRecordEntity
import me.ramos.unigate.iam.adapter.jpaOut.repository.OutboxRecordJpaRepository
import me.ramos.unigate.iam.application.outbox.model.OutboxRecord
import me.ramos.unigate.iam.application.outbox.port.outbound.OutboxPort
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * [OutboxPort] 의 JPA 구현.
 *
 * ## 트랜잭션을 여기서 열지 않는다
 * `@Transactional` 이 없는 것은 의도적이다. [enqueue] 는 **호출자(UseCase)의 트랜잭션에 참여**해야
 * 도메인 저장과 같은 커밋에 묶인다. 여기서 새 트랜잭션을 열면 프로필만 저장되고 outbox 지시는
 * 유실되는(또는 그 반대) 경우가 생겨 outbox 패턴 자체가 무의미해진다.
 *
 * [claimNext] 도 마찬가지다 — 행 잠금이 워커의 트랜잭션 동안 유지되어야 한다.
 */
@Component
class JpaOutboxAdapter(
  private val repository: OutboxRecordJpaRepository,
) : OutboxPort {
  override fun enqueue(record: OutboxRecord): OutboxRecord = repository.save(record.toEntity()).toModel()

  override fun claimNext(now: Instant): OutboxRecord? = repository.claimNext(now)?.toModel()

  override fun update(record: OutboxRecord): OutboxRecord {
    val id = requireNotNull(record.id) { "저장된 적 없는 outbox 레코드는 갱신할 수 없습니다" }
    val entity =
      repository.findById(id).orElseThrow {
        IllegalStateException("outbox 레코드를 찾지 못했습니다: id=$id")
      }
    entity.status = record.status
    entity.attempts = record.attempts
    entity.nextAttemptAt = record.nextAttemptAt
    entity.lastError = record.lastError
    entity.updatedAt = Instant.now()
    return repository.save(entity).toModel()
  }

  // ── 매핑 — 이 파일 밖으로 엔티티가 나가지 않는다 ──────────────────────────

  private fun OutboxRecord.toEntity(): OutboxRecordEntity =
    OutboxRecordEntity(
      id = id,
      eventType = eventType,
      payload = payload,
      status = status,
      attempts = attempts,
      nextAttemptAt = nextAttemptAt,
      lastError = lastError,
    )

  private fun OutboxRecordEntity.toModel(): OutboxRecord =
    OutboxRecord(
      id = id,
      eventType = eventType,
      payload = payload,
      status = status,
      attempts = attempts,
      nextAttemptAt = nextAttemptAt,
      lastError = lastError,
    )
}
