package me.ramos.unigate.iam.adapter.jpaOut.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import me.ramos.unigate.iam.domain.tenant.enums.TenantStatus
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable
import java.time.Instant

/**
 * `tenant` JPA 엔티티 (Phase 9c-2).
 *
 * ## `@GeneratedValue` 가 없다 — 자연키다
 * [id] 는 사용자가 정하는 slug 이고 Keycloak group 경로 `/tenants/{id}` 와 1:1 이다.
 * 대리키를 두면 "DB 의 id" 와 "group 경로의 id" 가 갈라져 둘을 잇는 코드가 계속 따라다닌다.
 *
 * ## 그래서 [Persistable] 을 구현한다
 * 자연키를 쓰면 Spring Data 의 `save()` 가 **새 엔티티인지 판단할 수 없다.** id 가 이미 채워져
 * 있으니 "기존 것" 으로 보고 `merge` 를 시도하는데, 그러면 INSERT 전에 **불필요한 SELECT** 가
 * 한 번 더 나간다(없으면 그제야 persist).
 *
 * [isNew] 로 그 판단을 명시하면 SELECT 없이 곧장 INSERT 한다. [newTenant] 로 만든 것만 새 것이고,
 * 저장소에서 읽어온 것은 Hibernate 가 [markNotNew] 를 부르지 않아도 이미 영속 상태다.
 *
 * ## `featureFlags` 를 JSONB 로 두는 이유
 * 별도 테이블로 정규화할 수도 있지만, 플래그는 **함께 읽고 함께 쓴다** — 부분 조회할 일이 없다.
 * 조인 하나를 줄이는 편이 낫고, 개수도 적다.
 */
@Entity
@Table(name = "tenant")
class TenantEntity(
  @Id
  @Column(name = "id", length = 64)
  private val tenantId: String,
  @Column(name = "display_name", nullable = false, length = 255)
  var displayName: String,
  @Column(name = "status", nullable = false, length = 16)
  @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
  var status: TenantStatus,
  /** `null` 은 **무제한**이다(0 이 아니다 — 0 은 "아무도 못 들어옴" 이라는 다른 뜻). */
  @Column(name = "max_users")
  var maxUsers: Int? = null,
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "feature_flags", nullable = false, columnDefinition = "jsonb")
  var featureFlags: String = "[]",
  @Column(name = "created_at", nullable = false, updatable = false)
  val createdAt: Instant = Instant.now(),
  @Column(name = "updated_at", nullable = false)
  var updatedAt: Instant = Instant.now(),
) : Persistable<String> {
  @org.springframework.data.annotation.Transient
  @jakarta.persistence.Transient
  private var isNewEntity: Boolean = false

  // Kotlin 에서 `val id` 로 두면 자동 생성되는 getter 가 Persistable.getId() 와 JVM 시그니처가
  // 충돌한다("Platform declaration clash"). 필드명을 분리하고 여기서 명시적으로 구현한다.
  override fun getId(): String = tenantId

  override fun isNew(): Boolean = isNewEntity

  /** 저장 후 호출해 "이제 영속 상태" 로 표시한다. */
  fun markNotNew() {
    isNewEntity = false
  }

  companion object {
    /** 신규 생성용 — `save()` 가 SELECT 없이 INSERT 하도록 표시한다. */
    fun newTenant(
      id: String,
      displayName: String,
      status: TenantStatus,
      maxUsers: Int?,
      featureFlags: String,
    ): TenantEntity =
      TenantEntity(
        tenantId = id,
        displayName = displayName,
        status = status,
        maxUsers = maxUsers,
        featureFlags = featureFlags,
      ).apply { isNewEntity = true }
  }
}
