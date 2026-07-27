import { request } from './client'
import type { EmailChange, Membership, MyMembership, Profile, TenantSummary, WhoAmI } from './types'

export const iam = {
  whoami: () => request<WhoAmI>('/iam/debug/whoami'),
  profile: () => request<Profile>('/iam/profile'),
  updateProfile: (body: { displayName?: string; locale?: string }) =>
    request<Profile>('/iam/profile', { method: 'PATCH', body }),
  changeEmail: (newEmail: string) =>
    request<EmailChange>('/iam/profile/email-change', { method: 'POST', body: { newEmail } }),
  acceptConsent: (tosVersion: string) =>
    request<Profile>('/iam/profile/consent', { method: 'POST', body: { tosVersion } }),

  /**
   * 내 멤버십 목록 — **토큰 claim 과 다른 것을 보여준다.**
   *
   * claim 에는 수락 대기 중인 초대가 없고, 방금 수락한 것도 재로그인 전까지 안 보인다.
   * 그 차이를 화면이 설명하려면 이 목록이 필요하다. 단 **인가의 근거는 여전히 claim** 이다.
   */
  myMemberships: () => request<MyMembership[]>('/iam/memberships'),
  acceptInvite: (tenantId: string) =>
    request<Membership>(`/iam/memberships/${tenantId}/accept`, { method: 'POST' }),
}

/** 관리 API — `unigate-admin` realm 역할이 없으면 전부 403 이다(P9c). 숨기지 않고 그대로 보여준다. */
export const admin = {
  createTenant: (body: { tenantId: string; displayName: string; maxUsers?: number }) =>
    request<TenantSummary>('/iam/admin/tenants', { method: 'POST', body }),
  members: (tenantId: string) =>
    request<{ members: Membership[] }>(`/iam/admin/tenants/${tenantId}/members`),
  invite: (tenantId: string, body: { userRef: string; role: string }) =>
    request<Membership>(`/iam/admin/tenants/${tenantId}/members`, { method: 'POST', body }),
  changeRole: (tenantId: string, userRef: string, role: string) =>
    request<Membership>(`/iam/admin/tenants/${tenantId}/members/${userRef}`, {
      method: 'PATCH',
      body: { role },
    }),
  revoke: (tenantId: string, userRef: string) =>
    request<void>(`/iam/admin/tenants/${tenantId}/members/${userRef}`, { method: 'DELETE' }),
}
