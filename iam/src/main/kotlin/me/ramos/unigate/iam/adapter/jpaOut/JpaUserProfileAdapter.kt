package me.ramos.unigate.iam.adapter.jpaOut

import me.ramos.unigate.iam.adapter.jpaOut.entity.UserProfileEntity
import me.ramos.unigate.iam.adapter.jpaOut.repository.UserProfileJpaRepository
import me.ramos.unigate.iam.application.user.port.outbound.ProfileConcurrentlyModifiedException
import me.ramos.unigate.iam.application.user.port.outbound.UserProfileRepositoryPort
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.user.model.UserProfile
import me.ramos.unigate.iam.domain.user.vo.ConsentRecord
import org.springframework.dao.OptimisticLockingFailureException
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
 * ## `save` 가 upsert 인 이유, 그리고 **무엇으로 행을 찾는가**
 * 도메인 `UserProfile` 에는 DB id 가 없다(저장 방식을 모르는 것이 원칙). 그래서 저장 시 기존 행을
 * 찾아 갱신하거나 새로 만든다.
 *
 * ⚠️ 예전에는 **email 로만** 찾았고, KDoc 에 "email 은 변경되지 않는다는 전제" 라고 적혀 있었다.
 * 이메일 변경 유스케이스가 그 전제를 깬다 — 변경이 확정된 프로필을 저장하면 **새 email 로**
 * 행을 찾게 되고, 없으니 INSERT 가 나가 **같은 사람의 프로필이 두 줄**이 된다.
 * (email UNIQUE 덕에 폭발하지만, 그건 방어일 뿐 설계가 아니다.)
 *
 * 그래서 **`userRef` 가 있으면 그것으로 먼저 찾는다.** `userRef` 는 Keycloak `sub` 이고 불변이라
 * 식별자로 옳다. 없는 경우(가입 대기 중 프로필)에만 email 로 되돌아간다 — 그 상태에서는
 * email 이 유일한 단서다.
 *
 * ## `save` 가 아니라 `saveAndFlush` 인 이유 (낙관적 락의 실효성이 여기 달려 있다)
 * 이미 영속 상태인 엔티티에 `save` 를 부르면 Hibernate 는 **아무것도 즉시 하지 않는다.** 실제
 * `UPDATE` 는 트랜잭션 커밋 시점의 flush 에서 나가고, 낙관적 락 위반도 그때 터진다.
 *
 * 그 시점은 **유스케이스 밖**이다. 즉 아래 두 가지가 동시에 무너진다:
 *
 * | | 커밋 시점에 터질 때 | `saveAndFlush` 로 즉시 터질 때 |
 * |---|---|---|
 * | 워커의 예외 분류 | `attempt()` 의 `try` 를 이미 빠져나가 **분류되지 못한다** | `catch` 가 정상적으로 잡는다 |
 * | 유스케이스의 뒷부분 | 감사까지 다 실행된 뒤 롤백된다 | 변경이 없었으므로 감사도 남지 않는다 |
 *
 * 특히 첫 줄이 위험하다 — 분류되지 못한 예외는 `REQUIRES_NEW` 트랜잭션을 롤백시키고, 그
 * 롤백에는 **클레임과 attempts 증가도 포함**된다. P9b 가 고쳤던 무한 재시도 루프가 그대로
 * 되살아난다(`OutboxProcessor` KDoc 의 "남아 있는 한계" 가 말하는 경로다).
 *
 * flush 를 앞당기는 대가는 크지 않다. 어차피 커밋 때 나갈 `UPDATE` 를 조금 먼저 보낼 뿐이고,
 * 이 유스케이스들은 프로필 한 건만 다룬다.
 */
@Component
class JpaUserProfileAdapter(
  private val repository: UserProfileJpaRepository,
) : UserProfileRepositoryPort {
  override fun save(profile: UserProfile): UserProfile {
    val existing =
      profile.userRef?.let { repository.findByUserRef(it.value) }
        ?: repository.findByEmail(profile.email)
    val entity = existing?.apply { applyFrom(profile) } ?: profile.toNewEntity()
    return try {
      repository.saveAndFlush(entity).toModel()
    } catch (e: OptimisticLockingFailureException) {
      throw ProfileConcurrentlyModifiedException(e)
    }
  }

  override fun findByEmail(email: String): UserProfile? = repository.findByEmail(email)?.toModel()

  override fun findByUserRef(userRef: UserRef): UserProfile? = repository.findByUserRef(userRef.value)?.toModel()

  // ── 매핑 ──────────────────────────────────────────────────────────────────

  private fun UserProfileEntity.applyFrom(profile: UserProfile) {
    // 이메일 변경이 확정되면 여기서 실제로 값이 바뀐다(그 전까지는 pendingEmail 만 움직인다).
    email = profile.email
    pendingEmail = profile.pendingEmail
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
      pendingEmail = pendingEmail,
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
      pendingEmail = pendingEmail,
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
