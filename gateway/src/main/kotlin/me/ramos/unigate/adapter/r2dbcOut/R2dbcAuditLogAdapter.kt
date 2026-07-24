package me.ramos.unigate.adapter.r2dbcOut

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import me.ramos.unigate.application.audit.port.outbound.SaveAuditEventOutPort
import me.ramos.unigate.domain.audit.model.AuditEvent
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component

/**
 * 감사 이벤트를 PostgreSQL `audit_log` 에 저장하는 R2DBC(reactive) 어댑터 — [SaveAuditEventOutPort] 구현.
 *
 * ## JPA 습관과의 대조 (처음 쓰는 R2DBC)
 * JPA 라면 `@Entity` + `save()` 로 끝나고 영속성 컨텍스트가 flush 를 알아서 했다. R2DBC 엔 그런 게
 * **없다** — 영속성 컨텍스트·더티체킹·지연 flush 가 전부 없으니 저장은 **명시적 INSERT** 로 직접 쓴다.
 * 여기서는 `DatabaseClient` 로 SQL 을 그대로 실행해 그 사실을 드러낸다(엔티티 매핑을 숨기지 않는다).
 *
 * ## 블로킹 금지
 * `rowsUpdated()` 는 `Mono<Long>` 이고 `awaitSingle()` 로 **논블로킹 대기**한다. JDBC(블로킹)를 이벤트
 * 루프에서 부르면 고부하에서 전체가 멈춘다(§4 함정) — R2DBC 는 그 경로를 논블로킹으로 유지한다.
 *
 * ## JSONB
 * `detail` 은 도메인에선 Map 이지만 DB 컬럼은 jsonb 다. 드라이버 고유 Json 타입에 컴파일 의존하지
 * 않으려고 문자열로 직렬화한 뒤 SQL 에서 `CAST(:detail AS jsonb)` 로 캐스팅한다(어댑터가 저장형식 봉인).
 */
@Component
class R2dbcAuditLogAdapter(
  private val databaseClient: DatabaseClient,
  private val objectMapper: ObjectMapper,
) : SaveAuditEventOutPort {
  override suspend fun save(event: AuditEvent) {
    databaseClient
      .sql(INSERT_SQL)
      .bind("eventType", event.type.name)
      .bindNullable("subject", event.subject)
      .bindNullable("clientId", event.clientId)
      .bindNullable("audience", event.audience)
      .bindNullable("reasonCode", event.reasonCode)
      .bindNullable("traceId", event.traceId)
      .bindNullable("detail", event.detail?.let { objectMapper.writeValueAsString(it) })
      .fetch()
      .rowsUpdated()
      .awaitSingle()
  }

  /** R2DBC 바인딩은 null 을 `bind` 로 넣을 수 없어 타입을 명시한 `bindNull` 로 구분한다. */
  private fun DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: String?,
  ): DatabaseClient.GenericExecuteSpec = if (value != null) bind(name, value) else bindNull(name, String::class.java)

  companion object {
    // id(BIGSERIAL)·created_at(DEFAULT now()) 은 DB 가 채운다. detail 은 text→jsonb 캐스팅.
    private val INSERT_SQL =
      """
      INSERT INTO audit_log (event_type, subject, client_id, audience, reason_code, trace_id, detail)
      VALUES (:eventType, :subject, :clientId, :audience, :reasonCode, :traceId, CAST(:detail AS jsonb))
      """.trimIndent()
  }
}
