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
  /**
   * `Retry-After` 에서 온다 — **본문이 아니라 헤더다.**
   *
   * 429 는 본문이 아예 없어서(위 주석) 본문만 보는 파서는 이 값을 영영 못 본다.
   * cross-origin 배포에서는 서버의 CORS `exposedHeaders` 에 `Retry-After` 가 있어야
   * 읽힌다(`CorsConfig.EXPOSED_HEADERS`). 없으면 여기가 조용히 undefined 다.
   */
  retryAfterSeconds?: number
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

  /** 서버가 알려준 대기 초. 없으면 undefined — **0 으로 바꾸지 않는다**(즉시 재시도가 된다). */
  get retryAfterSeconds(): number | undefined {
    return this.problem.retryAfterSeconds
  }
}

/**
 * `Retry-After` 를 초로 바꾼다.
 *
 * RFC 9110 은 **두 형식**을 허용한다 — delta-seconds(`120`)와 HTTP-date
 * (`Wed, 21 Oct 2026 07:28:00 GMT`). 게이트웨이는 초를 쓰지만(`RetryAfterFilter`),
 * 다운스트림이 자기 판단으로 넣은 값은 덮지 않으므로 날짜 형식이 올라올 수 있다.
 * 숫자만 받으면 그때 조용히 무시된다.
 */
export function parseRetryAfter(raw: string | null): number | undefined {
  if (!raw) return undefined
  const trimmed = raw.trim()

  const seconds = Number(trimmed)
  if (Number.isFinite(seconds)) return seconds >= 0 ? Math.ceil(seconds) : undefined

  const at = Date.parse(trimmed)
  if (Number.isNaN(at)) return undefined
  // 이미 지난 시각이면 기다릴 것이 없다. 음수를 그대로 넘기면 화면이 "-3초 후" 를 띄운다.
  return Math.max(0, Math.ceil((at - Date.now()) / 1000))
}

/** 본문이 없거나 JSON 이 아니어도 죽지 않는다. */
export async function parseProblem(res: Response): Promise<ProblemDetail> {
  // 헤더는 본문과 **독립적으로** 읽는다. 429 처럼 본문 없는 응답에서 값이 있는 쪽이 헤더다.
  const retryAfterSeconds = parseRetryAfter(res.headers.get('Retry-After'))
  const withRetry = (p: ProblemDetail): ProblemDetail =>
    retryAfterSeconds === undefined ? p : { ...p, retryAfterSeconds }

  const text = await res.text().catch(() => '')
  if (!text) return withRetry({ status: res.status, detail: `HTTP ${res.status} (본문 없음)` })
  try {
    const body = JSON.parse(text) as ProblemDetail
    return withRetry({ ...body, status: body.status ?? res.status })
  } catch {
    return withRetry({ status: res.status, detail: text.slice(0, 200) })
  }
}
