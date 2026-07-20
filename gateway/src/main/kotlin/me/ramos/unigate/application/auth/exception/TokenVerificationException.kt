package me.ramos.unigate.application.auth.exception

/**
 * 토큰 검증 실패 예외.
 *
 * 경계 어댑터(adapter/gatewayIn)에서 RFC 7807 Problem Detail(401 + 원인 코드)로 변환한다.
 */
class TokenVerificationException(
  val reasonCode: String,
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
