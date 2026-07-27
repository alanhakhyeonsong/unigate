import { ApiError } from '../api/problem'

/** reasonCode 는 **없을 수 있다.** 테넌트 게이트 403·429·502 가 그렇다. */
const MESSAGES: Record<string, string> = {
  access_denied: '권한이 없습니다.',
  profile_not_found: 'IAM 에 프로필이 없습니다. 가입 절차가 필요합니다.',
  consent_version_mismatch: '약관이 개정되었습니다. 다시 동의해 주세요.',
  email_already_in_use: '이미 사용 중인 이메일입니다.',
  email_change_in_progress: '이미 처리 중인 이메일 변경이 있습니다.',
  email_unchanged: '현재 이메일과 동일합니다.',
  identity_not_ready: '가입이 아직 완료되지 않았습니다.',
  order_not_found: '주문을 찾을 수 없습니다. (남의 테넌트 자원도 같은 응답입니다)',
}

export function ProblemAlert({ error }: { error: unknown }) {
  if (!error) return null
  if (!(error instanceof ApiError)) {
    return <div className="alert">알 수 없는 오류: {String(error)}</div>
  }
  const known = error.reasonCode ? MESSAGES[error.reasonCode] : undefined
  return (
    <div className="alert">
      <strong>
        {error.status} {error.problem.title ?? ''}
      </strong>
      <div>{known ?? error.problem.detail ?? '(서버가 사유를 주지 않았습니다)'}</div>
      <small>
        reasonCode: {error.reasonCode ?? '(없음)'}
        {error.problem.traceId ? ` · traceId: ${error.problem.traceId}` : ''}
      </small>
    </div>
  )
}
