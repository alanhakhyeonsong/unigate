package me.ramos.unigate.iam.adapter.schedulerIn

import me.ramos.unigate.iam.application.outbox.service.OutboxRetentionService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 완료 레코드 정리 트리거 — driving 어댑터 (Phase 9b).
 *
 * ## 폴링 스케줄러와 **분리한** 이유
 * 주기가 완전히 다르다. 폴링은 초 단위로 반응해야 하고 정리는 하루 한 번이면 충분하다.
 * 한 컴포넌트에 묶으면 "폴링 N 번에 한 번 정리" 같은 카운터가 생기는데, 그건 인스턴스 수와
 * 부하에 따라 실제 주기가 흔들리는 값이라 운영에서 예측이 안 된다.
 *
 * ## `fixedDelay` 가 아니라 `cron` 인 이유
 * `fixedDelay` 는 **기동 시점 기준**이라 배포 때마다 정리 시각이 옮겨 다닌다. 그러면 "새벽에만
 * 도는 줄 알았는데 낮에 돌더라" 가 된다. 벌크 DELETE 는 부하가 있는 작업이라 시각을 고정한다.
 *
 * ⚠️ 여러 인스턴스가 **같은 시각에 함께** 실행된다. 분산 락을 걸지 않는 것은 의도적이다 —
 * 삭제는 멱등이고(이미 지워진 행은 조건에 걸리지 않는다) 늦게 도착한 인스턴스는 0건을 지운다.
 */
@Component
@ConditionalOnProperty(name = ["unigate.iam.outbox.retention.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxRetentionScheduler(
  private val retentionService: OutboxRetentionService,
) {
  private val log = LoggerFactory.getLogger(javaClass)

  @Scheduled(cron = "\${unigate.iam.outbox.retention.cron:0 30 4 * * *}")
  fun purge() {
    try {
      retentionService.purgeCompleted()
    } catch (e: Exception) {
      // 스케줄러 메서드에서 예외가 새어나가면 다음 실행이 취소될 수 있다. 정리는 실패해도
      // 업무에 지장이 없으므로 로그만 남기고 다음 주기를 기다린다.
      log.error("outbox 완료 레코드 정리 실패 — 다음 주기에 재시도한다", e)
    }
  }
}
