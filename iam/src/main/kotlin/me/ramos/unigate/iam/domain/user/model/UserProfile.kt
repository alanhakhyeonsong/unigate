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
 * ## 이메일이 둘인 이유 (`email` / `pendingEmail`)
 * email 의 SoT 는 Keycloak 이다. IAM 의 값은 사본이므로, 변경 요청을 받자마자 사본을 덮어쓰면
 * **반영 전 구간에서 사본이 거짓말을 한다** — 화면에는 새 주소가 보이는데 로그인은 옛 주소로만
 * 된다. 게다가 Keycloak 이 거절하면 값이 조용히 되돌아간다.
 *
 * 그래서 **확정된 값과 요청된 값을 따로** 둔다.
 * ```
 * email        Keycloak 과 일치한다고 믿는 값 (표시·조회에 쓴다)
 * pendingEmail 반영 대기 중인 요청값 (아직 아무 데도 쓰이지 않는다)
 * ```
 * 워커가 Keycloak 반영에 성공하면 [applyEmailChange] 로 승격되고, 영구 실패하면
 * [cancelEmailChange] 로 버려진다(보상). 확정 값은 그 사이 한 번도 흔들리지 않는다.
 *
 * @param email 가입 시 입력값이자 이후 Keycloak 과 동기화되는 사본.
 */
class UserProfile private constructor(
  email: String,
  userRef: UserRef?,
  onboardingState: OnboardingState,
  displayName: String,
  locale: String,
  consent: ConsentRecord?,
  pendingEmail: String? = null,
) {
  var email: String = email
    private set

  /** 반영 대기 중인 이메일. `null` 이면 진행 중인 변경이 없다. */
  var pendingEmail: String? = pendingEmail
    private set

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
    // 확정 값과 요청 값이 같으면 "진행 중" 이라는 표시가 거짓이 된다. 워커가 처리해도
    // 바뀌는 게 없는데 감사에는 변경으로 남는다.
    require(pendingEmail == null || pendingEmail != email) {
      "반영 대기 이메일이 현재 이메일과 같을 수 없습니다"
    }
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

  /**
   * 이메일 변경을 **요청**한다. 확정하지 않는다 — Keycloak 반영은 워커가 한다.
   *
   * ## 왜 `ACTIVE` 여야 하나
   * 아직 Keycloak 사용자가 없는 프로필(`PENDING_IDENTITY`·`IDENTITY_FAILED`)에는 바꿀 대상이
   * 없다. 그 상태의 이메일 정정은 **가입 재시도**([retryIdentity])의 몫이고, 그쪽은 아직
   * 생성되지 않은 신원을 다루므로 규칙이 다르다. 두 경로를 섞으면 "무엇을 바꾸는지" 가 흐려진다.
   *
   * ## 진행 중인 변경이 있으면 거절한다
   * 덮어쓰면 앞선 요청의 outbox 지시가 **남은 채로** 뒤 요청과 경쟁한다. 워커가 둘을 어떤
   * 순서로 처리할지 보장이 없어 최종 값이 요청 순서와 달라질 수 있다. 순서를 보장할 수 없으면
   * **동시에 두 개를 만들지 않는 것**이 확실하다.
   */
  fun requestEmailChange(newEmail: String) {
    require(newEmail.isNotBlank()) { "이메일은 비어 있을 수 없습니다" }
    if (onboardingState != OnboardingState.ACTIVE) {
      throw UserProfileDomainException.IdentityNotReady(onboardingState)
    }
    if (newEmail == email) {
      throw UserProfileDomainException.EmailUnchanged(newEmail)
    }
    pendingEmail?.let { throw UserProfileDomainException.EmailChangeInProgress(it) }
    pendingEmail = newEmail
  }

  /**
   * 워커가 Keycloak 반영에 성공했을 때. 요청값을 확정 값으로 **승격**한다.
   *
   * 진행 중인 변경이 없으면 아무것도 하지 않는다 — outbox 는 최소 1회 실행이라 같은 지시가
   * 두 번 올 수 있고, 그때 두 번째 호출이 예외를 던지면 **성공 처리 도중에 실패**하게 된다.
   */
  fun applyEmailChange() {
    val pending = pendingEmail ?: return
    email = pending
    pendingEmail = null
  }

  /**
   * 보상 — 요청을 버린다. Keycloak 반영이 **영구 실패**했을 때 워커가 호출한다.
   *
   * 되돌릴 것이 `pendingEmail` 하나뿐인 것이 이 설계의 이득이다. 확정 값을 먼저 덮어썼다면
   * 보상은 "이전 값이 무엇이었는지 어딘가에 남겨두고 복원하기" 가 되고, 그 이전 값의 보관
   * 장소가 또 하나의 진실 후보가 된다.
   *
   * 멱등하다 — 이미 비어 있으면 아무것도 하지 않는다([applyEmailChange] 와 같은 이유).
   */
  fun cancelEmailChange() {
    pendingEmail = null
  }

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
      pendingEmail: String? = null,
    ): UserProfile =
      UserProfile(
        email = email,
        userRef = userRef,
        onboardingState = onboardingState,
        displayName = displayName,
        locale = locale,
        consent = consent,
        pendingEmail = pendingEmail,
      )

    private const val DEFAULT_LOCALE = "ko-KR"
  }
}
