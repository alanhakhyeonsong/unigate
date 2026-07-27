package me.ramos.unigate.iam.adapter.jpaOut.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import me.ramos.unigate.iam.domain.membership.enums.MembershipStatus
import java.time.Instant

/**
 * `membership` JPA 엔티티 — user ↔ tenant 다대다 (Phase 9c-2).
 *
 * ## `@ManyToOne` 을 쓰지 않는다
 * [tenantId] 를 연관관계가 아니라 **값**으로 들고 있다. JPA 연관을 걸면 편해 보이지만:
 *
 * - 도메인 `Membership` 은 `TenantId`(VO)만 알지 `Tenant` 애그리거트를 참조하지 않는다.
 *   엔티티에 연관을 걸면 매핑할 때 애그리거트를 통째로 로드해야 한다.
 * - **애그리거트 경계를 넘는 참조는 id 로** 하는 것이 DDD 의 기본이다. 두 애그리거트가
 *   객체 그래프로 이어지면 트랜잭션 경계가 흐려진다.
 *
 * ## `userRef` 에 FK 가 없는 이유
 * `user_profile` 을 가리키지 않는다. **신원 연결 전 사용자도 초대할 수 있어야** 하는데,
 * FK 를 걸면 그 경우가 막힌다(가입 직후에는 `user_ref` 가 아직 없다).
 */
@Entity
@Table(name = "membership")
class MembershipEntity(
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  val id: Long? = null,
  @Column(name = "tenant_id", nullable = false, length = 64)
  val tenantId: String,
  @Column(name = "user_ref", nullable = false, length = 64)
  val userRef: String,
  @Column(name = "role", nullable = false, length = 32)
  var role: String,
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  var status: MembershipStatus,
  @Column(name = "invited_by", length = 64)
  val invitedBy: String? = null,
  @Column(name = "invited_at", nullable = false)
  val invitedAt: Instant,
  @Column(name = "joined_at")
  var joinedAt: Instant? = null,
  @Column(name = "created_at", nullable = false, updatable = false)
  val createdAt: Instant = Instant.now(),
  @Column(name = "updated_at", nullable = false)
  var updatedAt: Instant = Instant.now(),
)
