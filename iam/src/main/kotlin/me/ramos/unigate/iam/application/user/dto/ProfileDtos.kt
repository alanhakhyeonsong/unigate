package me.ramos.unigate.iam.application.user.dto

import java.time.Instant

/**
 * 프로필 유스케이스의 Command 는 **모두 `userRef` 를 첫 필드로 받는다.**
 *
 * 이것이 이 API 의 인가 모델 전부다 — 대상 자원을 요청 본문이나 경로가 아니라 **토큰에서만** 정한다.
 * "남의 프로필 id 를 넣어 본다" 는 공격이 성립할 자리가 아예 없다(IDOR 불가). 대신 관리자가 남의
 * 프로필을 다루는 유스케이스가 생기면 **여기가 아니라 `/iam/admin` 쪽에 따로** 만들어야 한다 —
 * 이 Command 에 "대상 사용자" 필드를 추가하는 순간 그 안전성이 사라진다.
 */
data class UpdateMyProfileCommand(
  val userRef: String,
  /**
   * `null` 은 **"변경하지 않음"** 이다. "빈 값으로 지움" 이 아니다.
   *
   * 두 필드 모두 도메인에서 non-blank 불변식을 가지므로 지우는 연산 자체가 없다. 그래서 이 구분이
   * 모호해지지 않는다. 만약 나중에 nullable 필드(예: 부서)가 생기면 JSON 의 "필드 누락" 과
   * "명시적 null" 을 구분해야 하고, Kotlin 의 `String?` 만으로는 그게 안 된다 —
   * `JsonNullable` 같은 래퍼가 필요해진다.
   */
  val displayName: String? = null,
  val locale: String? = null,
)

/**
 * 약관 동의.
 *
 * `tosVersion` 을 클라이언트가 보내지만 **서버가 현재 버전과 대조한다**([AcceptConsentInPort] 참조).
 * 대조하지 않으면 클라이언트가 임의 버전을 보내 "최신 약관에 동의한 것으로" 만들 수 있다.
 */
data class AcceptConsentCommand(
  val userRef: String,
  val tosVersion: String,
)

/**
 * 프로필 조회 결과.
 *
 * ⚠️ **호출자 본인에게만 반환된다.** 다른 사용자에게 노출할 필드 집합이 아니다 — email 은
 * 그 자체로 개인정보이고, `onboardingState` 는 내부 상태기계다. 목록·검색 API 가 생기면
 * 별도의 축소된 Result 를 만든다.
 */
data class MyProfileResult(
  val email: String,
  val displayName: String,
  val locale: String,
  val onboardingState: String,
  /** 프로필 흐름에서는 항상 non-null 이다 — `userRef` 로 찾아왔으므로. */
  val userRef: String,
  val consent: ConsentResult?,
)

/**
 * 약관 동의 현황.
 *
 * `valid` 를 **서버가 계산해서** 준다. 클라이언트가 `tosVersion` 을 현재 버전과 비교하게 두면
 * 그 비교 로직이 FE·앱마다 흩어지고, 약관이 개정될 때 한 곳만 고쳐서는 끝나지 않는다.
 */
data class ConsentResult(
  val tosVersion: String,
  val acceptedAt: Instant,
  val valid: Boolean,
)
