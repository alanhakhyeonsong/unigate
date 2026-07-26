package me.ramos.unigate.iam.application.user.port.outbound

/**
 * outbox payload 직렬화 포트.
 *
 * ## 왜 포트로 뽑았나 — Jackson 을 application 에 들이지 않기 위해
 * `ObjectMapper` 를 UseCase 가 직접 쓰면 application 이 특정 직렬화 라이브러리에 묶인다.
 * 저장 형식(JSON)은 **어댑터의 관심사**이지 유스케이스의 관심사가 아니다.
 *
 * 게이트웨이에서 `R2dbcAuditLogAdapter` 가 `detail` Map 을 JSON 문자열로 바꿔 저장하며
 * "도메인은 저장 형식을 모른다" 를 지킨 것과 같은 원리다.
 */
interface PayloadSerializerPort {
  fun serialize(payload: Any): String

  fun <T : Any> deserialize(
    json: String,
    type: Class<T>,
  ): T
}
