package me.ramos.unigate.iam.application.user.dto

/**
 * 가입 요청 (application 경계).
 *
 * 비밀번호를 받지 않는다 — 초기 비밀번호 설정은 Keycloak 플로우에 맡긴다. 평문 비밀번호가
 * IAM DB·로그·**outbox payload** 에 남는 경로를 아예 만들지 않기 위해서다(`CLAUDE.md` §8).
 * outbox 는 실패 시 며칠씩 DB 에 남을 수 있어 특히 위험하다.
 */
data class RegisterUserCommand(
  val email: String,
  val displayName: String,
  val firstName: String,
  val lastName: String,
  val locale: String? = null,
  val tosVersion: String? = null,
)

/**
 * 가입 결과.
 *
 * `userRef` 가 **null 인 채로 성공**한다는 점이 중요하다. outbox 라 Keycloak 반영이 아직 안 됐고,
 * 그게 정상 경로다. FE 는 이 상태를 "처리 중" 으로 표현해야 한다.
 */
data class RegisterUserResult(
  val email: String,
  val onboardingState: String,
  val userRef: String?,
)

/**
 * outbox payload — 워커가 Keycloak 호출에 쓸 정보.
 *
 * ⚠️ 이 payload 는 DB 에 JSON 으로 **오래 남을 수 있다**(실패 시 재시도 대기). 민감정보를 넣지 않는다.
 */
data class CreateKeycloakUserPayload(
  val email: String,
  val firstName: String,
  val lastName: String,
)
