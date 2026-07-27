package me.ramos.unigate.iam.config

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.binder.MeterBinder
import me.ramos.unigate.iam.application.outbox.model.OutboxStatus
import me.ramos.unigate.iam.application.outbox.port.outbound.OutboxPort
import me.ramos.unigate.iam.application.user.policy.ConsentPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

/**
 * IAM 공통 설정.
 *
 * `@EnableScheduling` 이 없으면 [me.ramos.unigate.iam.adapter.schedulerIn.OutboxPollingScheduler] 의
 * `@Scheduled` 가 **조용히 무시된다** — 빈은 등록되고 앱도 정상 기동하는데 outbox 만 영원히 쌓인다.
 * 증상이 "가입은 되는데 Keycloak 에 안 생긴다" 라 원인을 찾기 어렵다.
 */
@Configuration
@EnableScheduling
class IamConfig {
  /**
   * [Clock] 을 빈으로 노출해 시간을 **주입 가능한 의존성**으로 만든다.
   *
   * UseCase 나 백오프 계산이 `Instant.now()` 를 직접 부르면 테스트에서 시간을 통제할 수 없다.
   * outbox 는 `nextAttemptAt` 비교가 핵심이라 특히 그렇다 — 재시도 시각을 검증하려면
   * 시계를 앞으로 돌릴 수 있어야 한다.
   */
  @Bean
  fun clock(): Clock = Clock.systemUTC()

  /**
   * 현재 약관 버전을 설정에서 읽어 정책 값으로 만든다 (Phase 8e).
   *
   * [ConsentPolicy] 자체는 순수 Kotlin 이라 Spring 을 모른다 — `application` 레이어가
   * `org.springframework.beans..`(`@Value`)를 import 하는 것을 ArchUnit 이 막기 때문이다.
   * [clock] 과 똑같은 구도다: **값은 application 이 정의하고, 값을 조달하는 일은 config 가 한다.**
   */
  @Bean
  fun consentPolicy(
    @Value("\${unigate.iam.consent.current-tos-version}") currentTosVersion: String,
  ): ConsentPolicy = ConsentPolicy(currentTosVersion)

  /**
   * outbox 상태별 건수를 메트릭으로 노출한다 (Phase 9b).
   *
   * ## 왜 필요한가 — 지금은 **DEAD 가 쌓여도 아무도 모른다**
   * 레코드가 죽으면 로그 한 줄이 남지만, 로그는 지나가고 나면 "지금 몇 건이 밀려 있나" 를
   * 알려주지 못한다. `unigate_iam_outbox_records{status="DEAD"}` 가 0 보다 크면 사람이 봐야
   * 한다는 신호이며, 이것이 "관리자가 수동 처리" 를 성립시키는 최소 조건이다.
   *
   * PENDING 도 함께 낸다 — DEAD 는 0 인데 PENDING 이 계속 쌓이면 **워커가 멈춘 것**이고,
   * 그건 다른 종류의 사고다(회로가 열린 채 복구되지 않는 경우 등).
   *
   * ## Counter 가 아니라 Gauge 인 이유
   * Counter 는 "이번 인스턴스가 몇 건을 죽였나" 라 재시작하면 0 이 되고, 다중 인스턴스에서는
   * 합산이 필요하다. 우리가 알고 싶은 것은 **지금 남아 있는 잔량**이므로 조회형 Gauge 가 맞다.
   *
   * 대가는 스크랩마다 `COUNT` 쿼리가 나간다는 것인데, `idx_outbox_status` 를 타고 상태 종류도
   * 셋뿐이라 부담이 작다.
   *
   * ⚠️ 이 빈이 `application` 이 아니라 `config` 에 있는 이유는 [clock]·[consentPolicy] 와 같다 —
   * ArchUnit 이 `application` 의 micrometer 의존을 막는다. **관측은 기술 관심사**이지 업무 규칙이
   * 아니므로 경계 밖에 둔다.
   *
   * ## ⚠️ `/actuator/prometheus` 는 **인증이 필요하다** — 확인할 때 헷갈리는 지점
   * `IamSecurityConfig.PUBLIC_PATHS` 는 health·info 만 열고 prometheus 는 **의도적으로 뺀다**(P8f).
   * 메트릭에는 내부 구조가 드러나므로 공개하지 않는다.
   *
   * 그래서 토큰 없이 그 엔드포인트를 긁으면 **401 Problem Detail** 이 돌아온다. 이때 메트릭 이름으로
   * grep 하면 아무것도 안 나와 **"게이지가 등록되지 않았다" 로 오진하기 쉽다.** 게이지 함수가
   * 호출되지 않으니 `count` 쿼리도 로그에 안 찍혀 그 오진을 뒷받침하는 것처럼 보인다.
   * 등록 여부는 `MeterRegistry` 를 직접 조회해 확인한다(`OutboxConcurrencyIntegrationTest`).
   */
  @Bean
  fun outboxStatusGauges(outboxPort: OutboxPort): MeterBinder =
    MeterBinder { registry ->
      OutboxStatus.entries.forEach { status ->
        Gauge
          .builder("unigate.iam.outbox.records") { outboxPort.countByStatus(status).toDouble() }
          .tag("status", status.name)
          .description("상태별 outbox 레코드 수. DEAD > 0 이면 수동 개입이 필요하다.")
          .register(registry)
      }
    }
}
