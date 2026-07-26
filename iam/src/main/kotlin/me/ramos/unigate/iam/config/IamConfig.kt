package me.ramos.unigate.iam.config

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
}
