package me.ramos.unigate.iam.application.outbox.port.outbound

import me.ramos.unigate.iam.application.outbox.model.OutboxRecord
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
}
