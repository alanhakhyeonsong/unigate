import { useParams } from 'react-router-dom'

/**
 * 현재 테넌트의 SoT 는 **URL** 이다.
 *
 * 전역 상태로 두면 탭 두 개로 acme·globex 를 동시에 열어 격리를 검증할 수 없다 —
 * 한쪽에서 바꾸면 다른 쪽도 바뀐다. 이 앱의 목적이 관찰이므로 URL 이 맞다.
 */
export function useCurrentTenant(): string | null {
  const { tenantId } = useParams<{ tenantId: string }>()
  return tenantId ?? null
}
