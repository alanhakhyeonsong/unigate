package me.ramos.unigate.iam.application.user.port.outbound

import me.ramos.unigate.iam.domain.user.model.UserProfile

/**
 * 사용자 프로필 저장소 포트.
 *
 * 도메인 모델([UserProfile])만 주고받는다 — JPA 엔티티는 어댑터 안에 갇힌다.
 * ArchUnit 이 `application` 의 `jakarta.persistence` 의존을 금지하므로 이 경계는 빌드가 강제한다.
 *
 * `email` 을 식별자로 쓴다. 도메인 모델에 DB id 를 두지 않았기 때문인데, 그 편이
 * "도메인은 저장 방식을 모른다" 는 원칙에 맞는다. 대신 어댑터가 email → row 매핑을 책임진다.
 */
interface UserProfileRepositoryPort {
  /** 신규 저장 또는 갱신(email 기준). */
  fun save(profile: UserProfile): UserProfile

  fun findByEmail(email: String): UserProfile?
}
