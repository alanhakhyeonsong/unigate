package me.ramos.unigate.iam.application.outbox.port.outbound

import me.ramos.unigate.iam.application.outbox.model.OutboxRecord
import me.ramos.unigate.iam.application.outbox.model.OutboxStatus
import java.time.Instant

/**
 * outbox 저장소 포트.
 *
 * ## [claimNext] 의 계약이 이 포트의 핵심이다
 * **다중 인스턴스가 동시에 폴링해도 같은 레코드를 두 번 집으면 안 된다.** 그 배타성을 어떻게
 * 보장할지는 구현체(어댑터)의 몫이지만, 계약으로 못 박아 둔다.
 *
 * PostgreSQL 구현은 `SELECT ... FOR UPDATE SKIP LOCKED` 를 쓴다. 이름 그대로 **이미 잠긴 행은
 * 건너뛰므로**, 여러 워커가 락을 기다리지 않고 서로 다른 행을 병렬로 처리한다.
 *
 * ⚠️ **반드시 트랜잭션 안에서 호출해야 한다.** 행 잠금은 트랜잭션 종료 시 풀리므로, 트랜잭션 밖에서
 * 부르면 잠금이 즉시 해제되어 다른 워커가 같은 레코드를 집는다.
 */
interface OutboxPort {
  /** 새 지시를 넣는다. **호출자의 트랜잭션에 참여해야 한다**(도메인 저장과 같은 커밋이어야 함). */
  fun enqueue(record: OutboxRecord): OutboxRecord

  /**
   * 처리 대상 하나를 **배타적으로** 집는다. 없으면 `null`.
   *
   * `status = PENDING` 이고 `nextAttemptAt <= now` 인 것 중 가장 오래 기다린 것을 준다.
   */
  fun claimNext(now: Instant): OutboxRecord?

  /** 처리 결과를 반영한다(COMPLETED / PENDING 재시도 / DEAD). */
  fun update(record: OutboxRecord): OutboxRecord

  /**
   * `id` 로 한 건을 읽는다. 없으면 `null`. 운영자 재처리(Phase 9c)에서 쓴다.
   *
   * [claimNext] 와 달리 **잠그지 않는다.** 재처리는 상태를 되돌릴 뿐 외부 호출을 하지 않아
   * 오래 잡고 있을 이유가 없고, 실제 처리는 워커가 정상 클레임 경로로 가져간다.
   */
  fun findById(id: Long): OutboxRecord?

  /**
   * `DEAD` 레코드를 최근에 죽은 순으로 조회한다 — 운영자 조회용(Phase 9c).
   *
   * 정렬 기준이 `deadAt` 인 것은 "방금 무슨 일이 났나" 가 가장 흔한 질문이기 때문이다.
   * `V4` 의 부분 인덱스 `idx_outbox_dead_at` 가 이 조회를 받친다.
   */
  fun findDead(limit: Int): List<OutboxRecord>

  /**
   * 상태별 레코드 수. **메트릭 전용**이다(Phase 9b).
   *
   * DEAD 가 쌓이는 것을 아무도 모르는 상태를 없애기 위해 둔다. 업무 로직이 이 값으로 분기하지
   * 않는다 — 그러면 다중 인스턴스에서 경합이 생기고, 애초에 outbox 는 건별로 판단하는 구조다.
   */
  fun countByStatus(status: OutboxStatus): Long

  /**
   * 지정 시각 **이전에 완료된** 레코드를 지운다. 지운 건수를 돌려준다.
   *
   * ## 감사 보존 정책과 성격이 다르다
   * `audit_log` 는 "무슨 일이 있었나" 의 기록이라 보존이 목적이지만, `COMPLETED` outbox 레코드는
   * **이미 끝난 작업 지시서**다. 신원 생성 이력은 `audit_log` 의 `IDENTITY_CREATED` 가 이미 갖고
   * 있으므로, 여기서 지워도 잃는 정보가 없다.
   *
   * ⚠️ `DEAD` 는 지우지 않는다. 사람이 봐야 할 것이 남아 있다는 뜻이고, 그것이 조용히 사라지면
   * 이 패턴의 "잃어버리지 않는다" 라는 약속이 깨진다.
   */
  fun deleteCompletedBefore(threshold: Instant): Int
}
