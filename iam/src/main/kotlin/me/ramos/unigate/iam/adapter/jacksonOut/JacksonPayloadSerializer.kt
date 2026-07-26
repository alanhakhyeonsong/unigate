package me.ramos.unigate.iam.adapter.jacksonOut

import com.fasterxml.jackson.databind.ObjectMapper
import me.ramos.unigate.iam.application.user.port.outbound.PayloadSerializerPort
import org.springframework.stereotype.Component

/** [PayloadSerializerPort] 의 Jackson 구현. Jackson 의존은 이 파일에서 끝난다. */
@Component
class JacksonPayloadSerializer(
  private val objectMapper: ObjectMapper,
) : PayloadSerializerPort {
  override fun serialize(payload: Any): String = objectMapper.writeValueAsString(payload)

  override fun <T : Any> deserialize(
    json: String,
    type: Class<T>,
  ): T = objectMapper.readValue(json, type)
}
