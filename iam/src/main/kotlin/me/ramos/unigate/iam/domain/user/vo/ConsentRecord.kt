package me.ramos.unigate.iam.domain.user.vo

import java.time.Instant

/**
 * 약관 동의 기록 — 복합 값 객체.
 *
 * **버전과 시각을 함께 보관하는 것이 핵심**이다. "동의했다"는 boolean 하나로는 법적으로 무의미하다.
 * 약관이 개정되면 이전 동의는 그 개정본에 대한 동의가 아니기 때문이다.
 *
 * Keycloak의 user attribute로는 이런 구조화·검증된 데이터를 다룰 수 없다(평면 key-value, 검증 없음).
 * 그래서 IAM이 소유한다(`IAM_PLATFORM_DECISION.md` §6.1).
 */
data class ConsentRecord(
  val tosVersion: String,
  val acceptedAt: Instant,
) {
  init {
    require(tosVersion.isNotBlank()) { "약관 버전은 비어 있을 수 없습니다" }
  }

  /** [currentVersion]에 대한 동의인가. 다르면 재동의를 받아야 한다. */
  fun matches(currentVersion: String): Boolean = tosVersion == currentVersion
}
