import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { admin, iam } from '../api/iam'
import { orders } from '../api/orders'
import { ApiError } from '../api/problem'
import { queryKeys } from '../api/queryKeys'

export function useWhoami() {
  return useQuery({ queryKey: queryKeys.whoami, queryFn: iam.whoami })
}

/** 개요 화면이 표시하는 세션 상태. **응답 코드에서 유도한 것**이고 추측이 아니다. */
export type SessionState =
  | 'loading'
  /** 401 — 게이트웨이가 라우팅 전에 끊었다. */
  | 'anonymous'
  /** 200 — 인증됐고 토큰 상세까지 있다. */
  | 'authenticated'
  /** 404 — 인증은 됐다. 이 환경에 프로브가 없을 뿐이다(alpha). */
  | 'authenticated-no-probe'
  /** 그 밖(5xx·네트워크). 상태를 말할 수 없다 — 모른다고 말해야 한다. */
  | 'unknown'

/**
 * 세션 상태 프로브 — **미인증에서도 안전하게 부를 수 있는 유일한 조회.**
 *
 * 다른 조회는 401 을 만나면 로그인으로 튕기지만 이건 튕기지 않는다(`iam.whoamiQuiet`).
 * 그래서 개요 화면이 로그인 전에도 열린다.
 */
export function useSessionProbe() {
  const query = useQuery({
    queryKey: queryKeys.sessionProbe,
    queryFn: iam.whoamiQuiet,
    retry: false,
  })

  const error = query.error
  const state: SessionState = query.isPending
    ? 'loading'
    : query.data
      ? 'authenticated'
      : error instanceof ApiError && error.status === 404
        ? 'authenticated-no-probe'
        : error instanceof ApiError && error.status === 401
          ? 'anonymous'
          : 'unknown'

  // 401 본문이 알려준 로그인 주소. 하드코딩하지 않는 이유는 `api/client.ts` 와 같다.
  const loginUrl =
    error instanceof ApiError && typeof error.problem.loginUrl === 'string'
      ? error.problem.loginUrl
      : undefined

  return { ...query, state, loginUrl }
}

/**
 * 프로필 — **반영 대기 중이면 폴링한다.**
 *
 * 이메일 변경은 202 로 끝나고 실제 반영은 워커가 한다. 폴링을 안 하면 사용자는 새로고침을
 * 눌러야 결과를 안다.
 *
 * ⚠️ 성공과 실패(보상)를 **응답으로 구분할 수 없다** — 둘 다 `pendingEmail: null` 이 된다.
 * 그래서 요청 시점의 값을 기억해 두고 `email` 과 비교하는 수밖에 없다(화면에서 처리).
 * 새로고침하면 그 기억도 사라진다. 서버가 실패를 알려줄 수단이 없는 것이 원인이다.
 */
export function useProfile() {
  return useQuery({
    queryKey: queryKeys.profile,
    queryFn: iam.profile,
    refetchInterval: (query) => (query.state.data?.pendingEmail ? 2000 : false),
  })
}

export function useOrders(tenantId: string) {
  return useQuery({
    queryKey: queryKeys.orders(tenantId),
    queryFn: () => orders.list(tenantId),
  })
}

export function useOrder(tenantId: string, id: string) {
  return useQuery({
    queryKey: queryKeys.order(tenantId, id),
    queryFn: () => orders.get(tenantId, id),
  })
}

export function useCreateOrder(tenantId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (item: string) => orders.create(tenantId, item),
    // 생성 성공 시 **그 테넌트의 목록만** 무효화한다. 전체를 날리면 다른 탭의 다른 테넌트까지
    // 재조회돼, 격리를 관찰하려는 목적에 노이즈가 된다.
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.orders(tenantId) }),
  })
}

export function useChangeEmail() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (newEmail: string) => iam.changeEmail(newEmail),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.profile }),
  })
}

export function useUpdateProfile() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { displayName?: string; locale?: string }) => iam.updateProfile(body),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.profile }),
  })
}

/** 4xx 는 재시도해도 결과가 같다. 특히 429 는 재시도가 토큰버킷을 더 소진시켜 상황을 악화시킨다. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false
  return failureCount < 2
}

/**
 * 내 멤버십 — **초대가 보인다.**
 *
 * 토큰 claim 만 보면 초대는 존재하지 않는 것과 같다. 이 목록이 있어야 "초대가 와 있다" 를
 * 화면이 말할 수 있다.
 */
export function useMyMemberships() {
  return useQuery({ queryKey: queryKeys.myMemberships, queryFn: iam.myMemberships })
}

export function useAcceptInvite() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (tenantId: string) => iam.acceptInvite(tenantId),
    // ⚠️ 목록은 갱신되지만 **토큰은 그대로다.** 수락 직후 그 테넌트로 API 를 부르면 403 이다
    // (group 투영이 outbox 를 거치고, 이미 발급된 토큰은 만료 전까지 옛 소속이다).
    // 그래서 화면이 재로그인을 안내한다 — 캐시 무효화로 해결되는 문제가 아니다.
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.myMemberships }),
  })
}

export function useAdminMembers(tenantId: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.adminMembers(tenantId),
    queryFn: () => admin.members(tenantId),
    enabled,
  })
}

export function useAdminMutations(tenantId: string) {
  const qc = useQueryClient()
  const invalidate = () => qc.invalidateQueries({ queryKey: queryKeys.adminMembers(tenantId) })
  return {
    createTenant: useMutation({
      mutationFn: (body: { tenantId: string; displayName: string; maxUsers?: number }) =>
        admin.createTenant(body),
    }),
    invite: useMutation({
      mutationFn: (body: { userRef: string; role: string }) => admin.invite(tenantId, body),
      onSuccess: invalidate,
    }),
    changeRole: useMutation({
      mutationFn: (v: { userRef: string; role: string }) =>
        admin.changeRole(tenantId, v.userRef, v.role),
      onSuccess: invalidate,
    }),
    revoke: useMutation({
      mutationFn: (userRef: string) => admin.revoke(tenantId, userRef),
      onSuccess: invalidate,
    }),
  }
}
