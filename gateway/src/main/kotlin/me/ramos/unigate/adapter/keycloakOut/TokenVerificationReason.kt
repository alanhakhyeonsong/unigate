package me.ramos.unigate.adapter.keycloakOut

/**
 * 토큰 검증 실패 원인 코드.
 *
 * 실패마다 **구분된 코드**를 남겨야 "왜 401 인가"를 로그·응답(RFC 7807)에서 바로 읽을 수 있다.
 * 서명 실패와 aud 불일치는 대응이 다르다 — 전자는 키/발급자 문제, 후자는 이 토큰이 우리 것이 아님.
 * 여기 값은 커스텀 검증기(JwtClaimValidators)의 `OAuth2Error.errorCode` 와 그대로 이어진다.
 */
object TokenVerificationReason {
  /** 서명 검증 실패 — JWKS 공개키로 검증되지 않음(키 불일치·변조). */
  const val INVALID_SIGNATURE = "invalid_signature"

  /** exp 만료 또는 nbf 미도래. */
  const val TOKEN_EXPIRED = "token_expired"

  /** iss 불일치 — 우리가 신뢰하는 발급자가 아님. */
  const val INVALID_ISSUER = "invalid_issuer"

  /** aud 불일치 — 이 토큰의 대상이 우리가 아님. */
  const val INVALID_AUDIENCE = "invalid_audience"

  /** JWT 형식이 아님 — 디코딩 자체 실패(3-segment 아님 등). */
  const val MALFORMED_TOKEN = "malformed_token"

  /** 위 어디에도 해당하지 않는 검증 실패(fallback). */
  const val INVALID_TOKEN = "invalid_token"
}
