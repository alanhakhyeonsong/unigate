package me.ramos.unigate.iam.adapter.jpaOut

import me.ramos.unigate.iam.adapter.jpaOut.entity.UserProfileEntity
import me.ramos.unigate.iam.adapter.jpaOut.repository.UserProfileJpaRepository
import me.ramos.unigate.iam.application.user.port.outbound.UserProfileRepositoryPort
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.user.model.UserProfile
import me.ramos.unigate.iam.domain.user.vo.ConsentRecord
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * [UserProfileRepositoryPort] 의 JPA 구현.
 *
 * ## 도메인 ↔ 엔티티 매핑 비용이 여기 다 모여 있다
 * ArchUnit 으로 도메인과 엔티티를 갈라놓은 대가다. 대신 도메인 모델은 `@Entity` 없이 순수하게 남고,
 * 상태 전이 규칙을 프레임워크 없이 테스트할 수 있다.
 *
 * 지금은 필드가 적어 매핑이 단순하다. 모델이 커지면 이 비용을 다시 저울질해야 한다 —
 * "분리해야 한다" 가 원칙이 아니라 **비용 대비 이득**의 문제이기 때문이다.
 *
 * ## `save` 가 upsert 인 이유
 * 도메인 `UserProfile` 에는 DB id 가 없다(저장 방식을 모르는 것이 원칙). 그래서 email 로 기존 행을
 * 찾아 갱신하거나 새로 만든다. **email 은 변경되지 않는다**는 전제가 깔려 있다 — 이메일 변경
 * 유스케이스가 생기면 이 매핑을 다시 봐야 한다.
 */
@Component
class JpaUserProfileAdapter(
  private val repository: UserProfileJpaRepository,
) : UserProfileRepositoryPort {
  override fun save(profile: UserProfile): UserProfile {
    val entity = repository.findByEmail(profile.email)?.apply { applyFrom(profile) } ?: profile.toNewEntity()
    return repository.save(entity).toModel()
  }

  override fun findByEmail(email: String): UserProfile? = repository.findByEmail(email)?.toModel()

  override fun findByUserRef(userRef: UserRef): UserProfile? = repository.findByUserRef(userRef.value)?.toModel()

  // ── 매핑 ──────────────────────────────────────────────────────────────────

  private fun UserProfileEntity.applyFrom(profile: UserProfile) {
    userRef = profile.userRef?.value
    onboardingState = profile.onboardingState
    displayName = profile.displayName
    locale = profile.locale
    consentTosVersion = profile.consent?.tosVersion
    consentAcceptedAt = profile.consent?.acceptedAt
    updatedAt = Instant.now()
  }

  private fun UserProfile.toNewEntity(): UserProfileEntity =
    UserProfileEntity(
      email = email,
      userRef = userRef?.value,
      onboardingState = onboardingState,
      displayName = displayName,
      locale = locale,
      consentTosVersion = consent?.tosVersion,
      consentAcceptedAt = consent?.acceptedAt,
    )

  /**
   * 저장된 상태를 **그대로** 되살린다 — `restore` 를 쓰는 이유다.
   *
   * `register` 같은 팩토리는 전이 규칙(초기 상태 강제)을 적용하므로 복원에 쓰면 DB 의 실제 상태가
   * 덮여버린다.
   */
  private fun UserProfileEntity.toModel(): UserProfile =
    UserProfile.restore(
      email = email,
      userRef = userRef?.let { UserRef(it) },
      onboardingState = onboardingState,
      displayName = displayName,
      locale = locale,
      consent =
        consentTosVersion?.let { version ->
          consentAcceptedAt?.let { at -> ConsentRecord(tosVersion = version, acceptedAt = at) }
        },
    )
}
