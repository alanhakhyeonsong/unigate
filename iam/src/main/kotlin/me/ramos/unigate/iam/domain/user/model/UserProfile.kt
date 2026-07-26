package me.ramos.unigate.iam.domain.user.model

import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.user.enums.OnboardingState
import me.ramos.unigate.iam.domain.user.exception.UserProfileDomainException
import me.ramos.unigate.iam.domain.user.vo.ConsentRecord

/**
 * 앱 고유 사용자 프로필 애그리거트.
 *
 * ## Keycloak의 user attribute와 무엇이 다른가
 * Keycloak은 인증에 필요한 평면 key-value를 갖지만 검증도 버전도 없다. 여기 있는 것들 —
 * 온보딩 상태기계, 약관 동의 버전, locale — 은 **토큰에 실으면 안 되는 구조화·검증된 데이터**이고
 * 앱의 관심사다(`IAM_PLATFORM_DECISION.md` §6.1).
 *
 * ## outbox가 이 애그리거트의 모양을 결정했다
 * **`userRef`가 `null`일 수 있다.** 보통은 "사용자 프로필에 사용자 참조가 없다"는 게 이상하지만,
 * outbox 패턴에서는 IAM DB를 먼저 커밋하고 Keycloak 사용자 생성을 워커가 나중에 하므로
 * **신원이 아직 없는 프로필이 정상적으로 실재한다**(`OnboardingState.PENDING_IDENTITY`).
 *
 * 대신 불변식을 하나 강제한다: **`ACTIVE`이면 반드시 `userRef`가 있다.** 이게 깨지면 로그인은
 * 가능한데 프로필을 못 찾거나 그 반대가 되므로, 생성·복원 시점에 [init]에서 막는다.
 *
 * @param email 가입 시 입력값. **SoT는 Keycloak이다** — 여기 값은 워커가 Keycloak에 만들 때 쓰는
 *   입력이자 표시용 사본이다. 사용자가 Keycloak에서 이메일을 바꾸면 어긋날 수 있고, 그 동기화는
 *   아직 미결이다(`IAM_PLATFORM_DECISION.md` §16).
 */
class UserProfile private constructor(
  val email: String,
  userRef: UserRef?,
  onboardingState: OnboardingState,
  displayName: String,
  locale: String,
  consent: ConsentRecord?,
) {
  var userRef: UserRef? = userRef
    private set

  var onboardingState: OnboardingState = onboardingState
    private set

  var displayName: String = displayName
    private set

  var locale: String = locale
    private set

  var consent: ConsentRecord? = consent
    private set

  init {
    require(email.isNotBlank()) { "이메일은 비어 있을 수 없습니다" }
    require(displayName.isNotBlank()) { "표시 이름은 비어 있을 수 없습니다" }
    ensureIdentityConsistent()
  }

  /**
   * 워커가 Keycloak 사용자 생성에 성공했을 때 호출한다. 신원을 채우고 `ACTIVE`로 전이한다.
   *
   * 이 두 가지는 **반드시 함께** 일어나야 한다. 따로 두면 "ACTIVE인데 userRef가 없는" 상태를
   * 만들 수 있는 API가 열린다.
   */
  fun completeIdentity(ref: UserRef) {
    transitionTo(OnboardingState.ACTIVE)
    userRef = ref
    ensureIdentityConsistent()
  }

  /**
   * 워커가 Keycloak 생성에 실패했을 때(이메일 중복 등). 사용자에게 알리고 정정을 받아야 한다.
   */
  fun failIdentity() = transitionTo(OnboardingState.IDENTITY_FAILED)

  /**
   * 정정 후 재시도. 이메일이 바뀌었을 수 있으므로 **새 프로필을 만드는 대신** 이 애그리거트를
   * 되돌린다 — 가입 시각·동의 기록 같은 이력을 잃지 않기 위해서다.
   */
  fun retryIdentity() = transitionTo(OnboardingState.PENDING_IDENTITY)

  fun changeDisplayName(newName: String) {
    require(newName.isNotBlank()) { "표시 이름은 비어 있을 수 없습니다" }
    displayName = newName
  }

  fun changeLocale(newLocale: String) {
    require(newLocale.isNotBlank()) { "locale은 비어 있을 수 없습니다" }
    locale = newLocale
  }

  /** 약관에 동의한다. 재동의(개정판)도 같은 메서드로 덮어쓴다. */
  fun acceptConsent(record: ConsentRecord) {
    consent = record
  }

  /** [currentVersion] 약관에 대한 동의가 유효한가. 미동의·구버전이면 false. */
  fun hasValidConsent(currentVersion: String): Boolean = consent?.matches(currentVersion) == true

  private fun transitionTo(target: OnboardingState) {
    if (!onboardingState.canTransitionTo(target)) {
      throw UserProfileDomainException.InvalidStateTransition(onboardingState, target)
    }
    onboardingState = target
  }

  /** 상태와 신원의 정합성 불변식. `ACTIVE`이면 반드시 `userRef`가 있어야 한다. */
  private fun ensureIdentityConsistent() {
    if (onboardingState == OnboardingState.ACTIVE && userRef == null) {
      throw UserProfileDomainException.InconsistentIdentity("ACTIVE 상태인데 userRef가 없습니다")
    }
  }

  companion object {
    /**
     * 가입 요청 시점의 프로필 생성 — **신원 없이** `PENDING_IDENTITY`로 시작한다.
     *
     * outbox 트랜잭션 안에서 `OutboxRecord(CREATE_KEYCLOAK_USER)`와 **함께** 저장된다.
     * 그래야 "프로필은 저장됐는데 Keycloak 생성 지시는 유실"되는 경우가 없다.
     */
    fun register(
      email: String,
      displayName: String,
      locale: String = DEFAULT_LOCALE,
      consent: ConsentRecord? = null,
    ): UserProfile =
      UserProfile(
        email = email,
        userRef = null,
        onboardingState = OnboardingState.PENDING_IDENTITY,
        displayName = displayName,
        locale = locale,
        consent = consent,
      )

    fun restore(
      email: String,
      userRef: UserRef?,
      onboardingState: OnboardingState,
      displayName: String,
      locale: String,
      consent: ConsentRecord?,
    ): UserProfile =
      UserProfile(
        email = email,
        userRef = userRef,
        onboardingState = onboardingState,
        displayName = displayName,
        locale = locale,
        consent = consent,
      )

    private const val DEFAULT_LOCALE = "ko-KR"
  }
}
