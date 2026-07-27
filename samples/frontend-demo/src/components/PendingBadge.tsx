/** 반영 대기 표시. "요청은 접수됐지만 아직 Keycloak 은 모른다" 를 화면에 드러낸다. */
export function PendingBadge({ value }: { value: string }) {
  return <span className="badge">반영 대기: {value}</span>
}
