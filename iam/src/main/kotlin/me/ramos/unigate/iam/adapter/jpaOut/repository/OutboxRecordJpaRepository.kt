package me.ramos.unigate.iam.adapter.jpaOut.repository

import me.ramos.unigate.iam.adapter.jpaOut.entity.OutboxRecordEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface OutboxRecordJpaRepository : JpaRepository<OutboxRecordEntity, Long> {
  /**
   * 처리 대상 하나를 **배타적으로** 집는다 — 다중 인스턴스 안전의 핵심.
   *
   * ## 왜 native 쿼리인가
   * Hibernate 힌트(`jakarta.persistence.lock.timeout = -2`)로도 SKIP LOCKED 를 걸 수 있지만,
   * 그 해석이 방언·버전에 달려 있어 **조용히 일반 `FOR UPDATE` 로 동작할 위험**이 있다.
   * 그러면 워커들이 락을 기다리며 직렬화되는데, 성능이 나빠질 뿐 기능은 되므로 알아채기 어렵다.
   * SQL 로 직접 쓰면 무엇이 실행되는지 의심할 여지가 없다.
   *
   * ## `FOR UPDATE SKIP LOCKED` 가 하는 일
   * - `FOR UPDATE`: 선택한 행을 트랜잭션이 끝날 때까지 잠근다.
   * - `SKIP LOCKED`: **이미 잠긴 행은 기다리지 않고 건너뛴다.**
   *
   * 둘의 조합으로 워커 N 개가 서로 다른 행을 동시에 처리한다. `SKIP LOCKED` 가 없으면 두 번째
   * 워커는 첫 번째가 끝날 때까지 대기하고, 결국 인스턴스를 늘려도 처리량이 늘지 않는다.
   *
   * ⚠️ **호출자가 트랜잭션을 열어야 한다.** 행 잠금은 트랜잭션 종료 시 풀린다. 트랜잭션 밖에서
   * 부르면 잠금이 즉시 해제되어 다른 워커가 같은 레코드를 집는다.
   *
   * `ORDER BY next_attempt_at` — 가장 오래 기다린 것부터. 재시도로 미뤄진 레코드가 굶지 않는다.
   */
  @Query(
    value = """
            SELECT * FROM outbox_record
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
        """,
    nativeQuery = true,
  )
  fun claimNext(
    @Param("now") now: Instant,
  ): OutboxRecordEntity?
}
