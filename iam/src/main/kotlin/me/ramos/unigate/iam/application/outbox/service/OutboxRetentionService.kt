package me.ramos.unigate.iam.application.outbox.service

import me.ramos.unigate.iam.application.outbox.port.outbound.OutboxPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 완료된 outbox 레코드 정리 — 테이블이 무한히 자라지 않게 한다 (Phase 9b).
 *
 * ## 감사 보존 정책과 **성격이 다르다**
 * `audit_log` 는 30일 보존 후 아카이빙한다(사용자 확정). 그건 "무슨 일이 있었나" 의 기록이라
 * 오래 남겨야 하기 때문이다.
 *
 * `COMPLETED` outbox 레코드는 다르다 — **이미 수행이 끝난 작업 지시서**다. 신원 생성이라는
 * 사건 자체는 `audit_log` 의 `IDENTITY_CREATED` 가 이미 갖고 있으므로, 지시서를 지워도
 * 잃는 정보가 없다. 그래서 보존 기간이 훨씬 짧다.
 *
 * ## `DEAD` 는 지우지 않는다
 * 사람이 봐야 할 것이 남아 있다는 뜻이고, 그것이 조용히 사라지면 outbox 의 **"잃어버리지
 * 않는다"** 는 약속이 깨진다. 정리 쿼리가 상태 조건을 갖는 이유가 이것이다
 * (`OutboxPort.deleteCompletedBefore`).
 *
 * ## 다중 인스턴스
 * 여러 인스턴스가 동시에 돌아도 안전하다. 삭제는 멱등이고(이미 지워진 행은 조건에 안 걸린다)
 * 결과가 건수 차이일 뿐이라, 폴링과 마찬가지로 분산 락이 필요 없다.
 */
@Service
class OutboxRetentionService(
  private val outboxPort: OutboxPort,
  private val clock: Clock,
) {
  private val log = LoggerFactory.getLogger(javaClass)

  /**
   * 보존 기간이 지난 `COMPLETED` 레코드를 지운다.
   *
   * @return 지운 건수
   */
  @Transactional
  fun purgeCompleted(): Int {
    val threshold = Instant.now(clock).minus(RETENTION)
    val deleted = outboxPort.deleteCompletedBefore(threshold)
    if (deleted > 0) {
      log.info("outbox 완료 레코드 정리 건수={} 기준시각={}", deleted, threshold)
    }
    return deleted
  }

  companion object {
    /**
     * 완료 레코드 보존 기간.
     *
     * 즉시 지우지 않는 이유는 **사후 확인 여지**를 남기기 위해서다 — "그 가입이 언제 반영됐나" 를
     * 며칠 안에는 outbox 에서 바로 볼 수 있는 편이 편하다. 그 이상은 `audit_log` 의 몫이다.
     */
    private val RETENTION: Duration = Duration.ofDays(7)
  }
}
