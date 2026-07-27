// 백엔드 DTO 의 수동 사본. **각 타입에 근거 파일을 남긴다** — springdoc 이 없어 자동 생성 경로가
// 없으므로, 필드명이 바뀌면 컴파일러가 아니라 화면의 `undefined` 가 알려준다.

// 출처: iam/.../iamIn/CallerProbeController.kt
export interface WhoAmI {
  subject: string
  preferredUsername: string | null
  email: string | null
  issuer: string | null
  audience: string[]
  authorizedParty: string | null
  groups: string[] | null
  tenants: string[] | null
  expiresAt: string | null
  receivedTenantHeader: string | null
  receivedRequestedTenantHeader: string | null
}

// 출처: iam/.../iamIn/ProfileController.kt (ProfileResponse)
export interface Profile {
  email: string
  pendingEmail: string | null
  displayName: string
  locale: string
  onboardingState: string
  userRef: string
  consent: { tosVersion: string; acceptedAt: string; valid: boolean } | null
}

// 출처: iam/.../iamIn/ProfileController.kt (EmailChangeResponse)
export interface EmailChange {
  email: string
  pendingEmail: string | null
}

// 출처: samples/downstream-demo/.../OrderRepository.kt (Order)
export interface Order {
  id: string
  tenantId: string
  item: string
}

// 출처: samples/downstream-demo/.../EchoController.kt (EchoResponse)
export interface Echo {
  method: string
  path: string
  query: string | null
  headers: Record<string, string | null>
  authorization: { present: boolean; jwt?: boolean; payload?: string; rawValue?: string }
  principal: string | null
}

// 출처: iam/.../tenant/service/MembershipService.kt (MyMembershipResult)
export interface MyMembership {
  tenantId: string
  tenantDisplayName: string | null
  tenantStatus: string | null
  role: string
  status: 'INVITED' | 'ACTIVE' | 'REVOKED' | (string & {})
  joinedAt: string | null
}

// 출처: iam/.../tenant/service/MembershipService.kt (MembershipResult)
export interface Membership {
  tenantId: string
  userRef: string
  role: string
  status: string
  joinedAt: string | null
}

// 출처: iam/.../iamIn/TenantAdminController.kt (TenantResponse 계열)
export interface TenantSummary {
  tenantId: string
  displayName: string
  status: string
  maxUsers?: number | null
}
