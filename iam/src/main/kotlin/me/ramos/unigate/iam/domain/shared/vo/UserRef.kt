package me.ramos.unigate.iam.domain.shared.vo

/**
 * Keycloak 사용자에 대한 **불투명 참조** — IAM 도메인의 anti-corruption seam.
 *
 * ## 이 VO가 존재하는 이유
 * IAM은 신원(자격증명·MFA·페더레이션)을 **재구현하지 않는다.** Keycloak이 소유하고 IAM은 참조만 든다.
 * 그 참조를 원시 `String`으로 들고 다니면 도메인 곳곳에 "이 문자열이 뭐였더라"가 퍼지고, 언젠가
 * Keycloak 고유 타입이나 응답 DTO가 도메인에 스며든다.
 *
 * `UserRef`는 그 경계를 한 지점으로 모은다. **도메인은 이 값의 내부 형식을 해석하지 않는다** —
 * Keycloak이 UUID를 쓰든 다른 것을 쓰든 도메인은 영향받지 않는다.
 * (게이트웨이의 `TokenVerifierPort`가 OIDC 표준에만 의존하는 것과 같은 봉인 원리다.)
 *
 * ## 왜 형식 검증(UUID 등)을 하지 않는가
 * 검증하는 순간 "Keycloak은 UUID를 쓴다"는 사실이 도메인 규칙이 된다. 그건 봉인의 반대다.
 * 비어 있는지만 본다 — 그건 형식이 아니라 **참조가 실재하는가**의 문제라 도메인 관심사다.
 */
@JvmInline
value class UserRef(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "UserRef는 비어 있을 수 없습니다" }
  }
}
