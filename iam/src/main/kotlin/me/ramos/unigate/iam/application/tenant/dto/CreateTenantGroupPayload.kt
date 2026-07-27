package me.ramos.unigate.iam.application.tenant.dto

/**
 * `CREATE_KEYCLOAK_GROUP` outbox 지시의 payload (Phase 9c-2).
 *
 * 테넌트 **id 만** 담는다. displayName 이나 쿼터는 Keycloak 이 알 필요가 없고,
 * payload 에 넣으면 그 값이 바뀌었을 때 outbox 안의 사본이 낡는다.
 * 워커가 처리 시점에 DB 에서 최신 상태를 읽는 편이 언제나 정확하다.
 */
data class CreateTenantGroupPayload(
  val tenantId: String,
)
