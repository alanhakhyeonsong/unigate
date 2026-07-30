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
  // 계정 열거 관점에서는 논쟁적이지만, 알려주지 않으면 가입 UX 가 막힌다.
  // 실질 방어는 게이트웨이의 rate limit 이다(RegisterController KDoc).
  email_already_registered: '이미 가입된 이메일입니다.',
  order_not_found: '주문을 찾을 수 없습니다. (남의 테넌트 자원도 같은 응답입니다)',
}

export function ProblemAlert({ error }: { error: unknown }) {
  if (!error) return null
  if (!(error instanceof ApiError)) {
    return <div className="alert">알 수 없는 오류: {String(error)}</div>
  }
  const known = error.reasonCode ? MESSAGES[error.reasonCode] : undefined
  const retry = error.retryAfterSeconds
  return (
    <div className="alert">
      <strong>
        {error.status} {error.problem.title ?? ''}
      </strong>
      <div>{known ?? error.problem.detail ?? '(서버가 사유를 주지 않았습니다)'}</div>
      {/*
        429 는 본문이 비어 있어 위 줄이 "본문 없음" 밖에 못 말한다. 사용자에게 쓸모 있는
        유일한 정보가 이 헤더값이다 — 서버가 이미 계산해 준 것을 버리지 않는다.
      */}
      {retry !== undefined && (
        <div>
          {retry > 0 ? `${retry}초 후 다시 시도해 주세요.` : '지금 다시 시도할 수 있습니다.'}
        </div>
      )}
      <small>
        reasonCode: {error.reasonCode ?? '(없음)'}
        {error.problem.traceId ? ` · traceId: ${error.problem.traceId}` : ''}
        {retry !== undefined ? ` · Retry-After: ${retry}s` : ''}
      </small>
    </div>
  )
}
