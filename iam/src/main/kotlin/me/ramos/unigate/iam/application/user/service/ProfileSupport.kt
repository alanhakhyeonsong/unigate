package me.ramos.unigate.iam.application.user.service

import me.ramos.unigate.iam.application.user.dto.ConsentResult
import me.ramos.unigate.iam.application.user.dto.MyProfileResult
import me.ramos.unigate.iam.application.user.policy.ConsentPolicy
import me.ramos.unigate.iam.application.user.port.outbound.UserProfileRepositoryPort
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import me.ramos.unigate.iam.domain.user.model.UserProfile

// 프로필 유스케이스 3종이 공유하는 조각.
//
// 세 유스케이스(조회·수정·동의)가 전부 "호출자 프로필을 찾아 없으면 거부하고, 끝에 Result 로
// 변환한다" 는 같은 골격을 갖는다. 그 두 조각만 여기 모은다 — 각 서비스에 복붙하면 한쪽만 고치는
// 사고가 나고, 특히 **없을 때의 처리가 서비스마다 달라지는 것**이 위험하다.
//
// (파일 머리말을 KDoc 으로 쓰면 ktlint 가 "a KDoc may not be preceded by a KDoc" 로 막는다 —
//  바로 아래 선언의 KDoc 과 붙어버리기 때문이다. 그래서 줄 주석으로 둔다.)

/**
 * 호출자 본인의 프로필을 가져온다. 없으면 [ProfileNotFoundException].
 *
 * 인자가 `String` 인 이유: Command/InPort 경계에서는 원시 값으로 다루고, 도메인 타입([UserRef])으로
 * 감싸는 지점을 여기 한 곳으로 모은다. `UserRef` 는 blank 를 거부하므로 빈 `sub` 는 여기서 걸린다.
 */
internal fun UserProfileRepositoryPort.loadCaller(userRef: String): UserProfile =
  findByUserRef(UserRef(userRef)) ?: throw ProfileNotFoundException(userRef)

/**
 * 도메인 → Result 변환. `valid` 계산에 현재 약관 버전이 필요해 정책을 함께 받는다.
 *
 * `userRef!!` 를 쓰는 근거: 이 함수는 [loadCaller] 로 **`userRef` 조회해 찾은** 프로필에만 쓰인다.
 * `userRef` 가 null 인 프로필(`PENDING_IDENTITY`)은 그 조회에 절대 걸리지 않는다.
 */
internal fun UserProfile.toMyProfileResult(policy: ConsentPolicy): MyProfileResult =
  MyProfileResult(
    email = email,
    displayName = displayName,
    locale = locale,
    onboardingState = onboardingState.name,
    userRef = requireNotNull(userRef) { "userRef 로 조회한 프로필인데 userRef 가 null 입니다" }.value,
    consent =
      consent?.let {
        ConsentResult(
          tosVersion = it.tosVersion,
          acceptedAt = it.acceptedAt,
          // 클라이언트가 버전을 비교하지 않도록 서버가 계산해 준다.
          valid = hasValidConsent(policy.currentTosVersion),
        )
      },
  )

/**
 * 토큰은 유효한데 IAM 에 프로필이 없다.
 *
 * ## 언제 실제로 발생하나
 * 가입을 IAM 을 통해 했다면 프로필이 반드시 있다. 없는 경우는 **Keycloak 에 직접 만들어진 사용자**
 * (관리자가 콘솔에서 생성, 페더레이션 IdP 로 첫 로그인 등)다. 인증은 되지만 IAM 도메인에는 존재하지
 * 않는 상태다.
 *
 * ## 왜 자동 생성(JIT provisioning)하지 않나
 * 조회 요청이 **쓰기를 유발**하게 되고, 그렇게 만들어진 프로필에는 약관 동의도 가입 경로 이력도 없다.
 * 즉 "동의 없이 가입된 사용자" 가 조용히 생긴다. 그건 정책 결정이지 조회 API 가 몰래 할 일이 아니다.
 * → 404 로 드러내고, 온보딩을 어떻게 이어붙일지는 별도 유스케이스로 다룬다.
 */
class ProfileNotFoundException(
  userRef: String,
) : RuntimeException("해당 사용자의 프로필이 없습니다: userRef=$userRef")

/**
 * 동의하려는 약관 버전이 현재 버전과 다르다.
 *
 * 구버전에 동의하려는 경우(오래된 화면을 열어둔 채 개정됨)와 존재하지 않는 버전을 보내는 경우
 * 모두 여기로 온다. 클라이언트는 최신 약관을 다시 받아 보여준 뒤 재요청해야 한다.
 */
class ConsentVersionMismatchException(
  val requested: String,
  val current: String,
) : RuntimeException("동의하려는 약관 버전이 현재 버전과 다릅니다: requested=$requested current=$current")
