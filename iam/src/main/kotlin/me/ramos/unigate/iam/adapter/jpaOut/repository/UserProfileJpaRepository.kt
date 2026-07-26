package me.ramos.unigate.iam.adapter.jpaOut.repository

import me.ramos.unigate.iam.adapter.jpaOut.entity.UserProfileEntity
import org.springframework.data.jpa.repository.JpaRepository

interface UserProfileJpaRepository : JpaRepository<UserProfileEntity, Long> {
  fun findByEmail(email: String): UserProfileEntity?

  /**
   * `user_ref` 는 nullable + UNIQUE 다(outbox 라 신원 대기 중엔 null).
   * 인자로 null 이 오면 `IS NULL` 로 번역되어 **아무 대기 중 프로필이나** 잡힐 수 있으므로,
   * 호출부(어댑터)는 non-null `UserRef` 만 넘긴다.
   */
  fun findByUserRef(userRef: String): UserProfileEntity?
}
