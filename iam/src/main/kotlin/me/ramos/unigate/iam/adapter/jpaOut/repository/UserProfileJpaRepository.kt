package me.ramos.unigate.iam.adapter.jpaOut.repository

import me.ramos.unigate.iam.adapter.jpaOut.entity.UserProfileEntity
import org.springframework.data.jpa.repository.JpaRepository

interface UserProfileJpaRepository : JpaRepository<UserProfileEntity, Long> {
  fun findByEmail(email: String): UserProfileEntity?
}
