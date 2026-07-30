package me.ramos.unigate.iam.adapter.jpaOut.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import me.ramos.unigate.iam.domain.user.enums.OnboardingState
import java.time.Instant

/**
 * `user_profile` JPA 엔티티.
 *
 * `UserProfile`(domain)과 분리한다 — 도메인에 `@Entity` 를 붙이면 영속성 사정이 도메인 설계를
 * 지배한다(ArchUnit `domain 에 JPA 어노테이션을 붙이지 않는다` 규칙이 강제).
 *
 * ## `userRef` 가 nullable 인 것은 **버그가 아니라 outbox 의 결과**다
 * 가입 요청 시점에는 Keycloak 사용자가 아직 없다(`PENDING_IDENTITY`). 워커가 생성에 성공해야
 * 채워진다. DB 에도 `UNIQUE` 를 걸되 NULL 을 허용하는 이유다 — PostgreSQL 은 NULL 을 중복으로
 * 보지 않으므로 여러 건이 동시에 신원 대기 상태일 수 있다.
 *
 * ## ConsentRecord 를 평탄화한 이유
 * VO 를 `@Embedded` 로 넣을 수도 있지만, 그러면 도메인 VO 에 JPA 어노테이션이 필요해진다.
 * 두 컬럼으로 펼치고 매퍼가 조립한다.
 *
 * ## `version` 이 **도메인이 아니라 여기에만** 있는 이유
 * 낙관적 락은 저장 방식의 문제다. 도메인 [me.ramos.unigate.iam.domain.user.model.UserProfile] 은
 * DB id 도 갖지 않는데(`저장 방식을 모른다`), version 만 예외로 둘 이유가 없다.
 *
 * 대신 **전제가 하나 생긴다** — 같은 트랜잭션에서 조회한 엔티티를 그대로 갱신해야 한다.
 * 영속성 컨텍스트가 로드 시점의 version 을 들고 있어야 `UPDATE ... WHERE version = ?` 이
 * 의미를 갖기 때문이다. 그 전제는 [me.ramos.unigate.iam.adapter.jpaOut.JpaUserProfileAdapter]
 * 가 지키고, 통합 테스트가 실제 경합으로 확인한다.
 */
@Entity
@Table(name = "user_profile")
class UserProfileEntity(
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  val id: Long? = null,
  @Column(name = "email", nullable = false, unique = true, length = 255)
  var email: String,
  /** 반영 대기 중인 이메일. UNIQUE 를 걸지 않은 이유는 `V6` 마이그레이션 주석 참조. */
  @Column(name = "pending_email", length = 255)
  var pendingEmail: String? = null,
  @Column(name = "user_ref", unique = true, length = 64)
  var userRef: String? = null,
  @Enumerated(EnumType.STRING)
  @Column(name = "onboarding_state", nullable = false, length = 32)
  var onboardingState: OnboardingState,
  @Column(name = "display_name", nullable = false, length = 255)
  var displayName: String,
  @Column(name = "locale", nullable = false, length = 16)
  var locale: String,
  @Column(name = "consent_tos_version", length = 32)
  var consentTosVersion: String? = null,
  @Column(name = "consent_accepted_at")
  var consentAcceptedAt: Instant? = null,
  @Column(name = "created_at", nullable = false, updatable = false)
  val createdAt: Instant = Instant.now(),
  @Column(name = "updated_at", nullable = false)
  var updatedAt: Instant = Instant.now(),
  /**
   * 낙관적 락 버전. **Hibernate 가 관리한다** — 코드에서 직접 증가시키지 않는다.
   *
   * `var` 이고 `private set` 도 아닌 것은 JPA 가 리플렉션으로 써야 하기 때문이다.
   */
  @Version
  @Column(name = "version", nullable = false)
  var version: Long = 0,
)
