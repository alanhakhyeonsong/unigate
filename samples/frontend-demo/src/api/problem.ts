/**
 * RFC 9457 Problem Detail — **이지만 모든 응답이 그렇지는 않다.**
 *
 * 게이트웨이·IAM 은 problem+json 을 주지만 샘플 다운스트림은 손으로 만든 `{status, reasonCode,
 * detail}` 맵을 주고, 429 는 **본문이 아예 없다.** 그래서 파서는 세 형태를 모두 견뎌야 한다.
 * `res.json()` 을 그냥 부르면 본문 없는 응답에서 throw 되어 진짜 상태코드가 가려진다.
 */
export type ReasonCode =
  | 'authentication_required'
  | 'access_denied'
  | 'profile_not_found'
  | 'consent_version_mismatch'
  | 'email_already_in_use'
  | 'email_change_in_progress'
  | 'email_unchanged'
  | 'identity_not_ready'
  | 'order_not_found'
  | (string & {})

export interface ProblemDetail {
  status: number
  title?: string
  detail?: string
  instance?: string
  reasonCode?: ReasonCode
  traceId?: string
  loginUrl?: string
  [key: string]: unknown
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: ProblemDetail,
  ) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`)
    this.name = 'ApiError'
  }

  get reasonCode(): ReasonCode | undefined {
    return this.problem.reasonCode
  }
}

/** 본문이 없거나 JSON 이 아니어도 죽지 않는다. */
export async function parseProblem(res: Response): Promise<ProblemDetail> {
  const text = await res.text().catch(() => '')
  if (!text) return { status: res.status, detail: `HTTP ${res.status} (본문 없음)` }
  try {
    const body = JSON.parse(text) as ProblemDetail
    return { ...body, status: body.status ?? res.status }
  } catch {
    return { status: res.status, detail: text.slice(0, 200) }
  }
}
