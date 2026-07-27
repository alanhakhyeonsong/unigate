package me.ramos.unigate.iam.application.tenant.dto

/**
 * `ADD_GROUP_MEMBER` / `REMOVE_GROUP_MEMBER` outbox 지시의 payload (Phase 9d).
 *
 * 역할(`TenantRole`)을 담지 않는다. P9d 시점의 Keycloak 투영은 **group 소속까지**이고,
 * 역할은 IAM DB 가 SoT 다. GW 의 coarse 게이트는 "이 테넌트에 소속인가" 만 보므로
 * (`IAM_PLATFORM_DECISION.md` §8.1) group 만으로 충분하다.
 *
 * 테넌트별 역할을 토큰 claim 으로 내보내는 것은 P9e 의 몫이다.
 */
data class GroupMembershipPayload(
  val tenantId: String,
  val userRef: String,
)
