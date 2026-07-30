import { useState } from 'react'
import { publicApi } from '../api/iam'
import { ApiError } from '../api/problem'
import type { Registration } from '../api/types'
import { ProblemAlert } from '../components/ProblemAlert'

/**
 * 가입 — **이 콘솔에서 유일하게 로그인 없이 쓰는 화면.**
 *
 * 다른 화면은 전부 세션이 있어야 하지만 가입은 토큰이 아직 없는 상태다. 그래서 이 화면 하나가
 * 서버의 공개 경로 설정(`permitAll` + CSRF 예외 + 전용 rate limit)을 통째로 검증한다.
 *
 * ## 이 화면이 굳이 드러내려는 것
 * 가입은 **끝나도 끝난 게 아니다.** 201 이 오지만 그 순간 존재하는 것은 IAM 의 프로필뿐이고,
 * Keycloak 사용자는 outbox 워커가 나중에 만든다(`onboardingState: PENDING_IDENTITY`).
 * 게다가 **비밀번호를 받지 않으므로 이대로는 로그인할 수 없다.** 화면이 이 두 가지를 말하지
 * 않으면 "가입했는데 로그인이 안 된다" 는 오해가 그대로 남는다.
 */
export function Register() {
  const [form, setForm] = useState({
    email: '',
    displayName: '',
    firstName: '',
    lastName: '',
  })
  const [result, setResult] = useState<Registration | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [pending, setPending] = useState(false)

  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }))

  const filled = form.email && form.displayName && form.firstName && form.lastName

  const submit = async () => {
    setError(null)
    setResult(null)
    setPending(true)
    try {
      setResult(await publicApi.register(form))
    } catch (e) {
      setError(e instanceof ApiError ? e : e)
    } finally {
      setPending(false)
    }
  }

  return (
    <section>
      <h2>가입 — 로그인 없이 부르는 유일한 API</h2>
      <p className="note">
        <code>POST /iam/register</code> 는 <strong>공개 경로</strong>다. 사용자 토큰이 아직 없는
        유일한 IAM 유스케이스라 인증을 요구할 수 없고, 같은 이유로 <strong>CSRF 도 예외</strong>다.
        방어는 게이트웨이의 <strong>전용 rate limit</strong> 이 맡는다.
      </p>

      <div className="row">
        <label>
          이메일
          <input value={form.email} onChange={set('email')} placeholder="user@example.local" />
        </label>
        <label>
          표시 이름
          <input value={form.displayName} onChange={set('displayName')} placeholder="검증 계정" />
        </label>
        <label>
          이름(first)
          <input value={form.firstName} onChange={set('firstName')} placeholder="Gil-dong" />
        </label>
        <label>
          성(last)
          <input value={form.lastName} onChange={set('lastName')} placeholder="Hong" />
        </label>
        <button disabled={!filled || pending} onClick={submit}>
          {pending ? '가입 중…' : '가입'}
        </button>
      </div>

      <ProblemAlert error={error} />

      {result && (
        <>
          <h3>201 — 그런데 아직 끝이 아니다</h3>
          <dl className="kv">
            <dt>email</dt>
            <dd>{result.email}</dd>
            <dt>onboardingState</dt>
            <dd>
              <strong>{result.onboardingState}</strong>
              <small> ← outbox 워커가 Keycloak 사용자를 만들면 ACTIVE 로 간다(최대 10s)</small>
            </dd>
            <dt>userRef</dt>
            <dd>
              {result.userRef ?? '(null)'}
              <small> ← 가입 직후엔 항상 null 이다. 신원 연결이 끝나야 채워진다</small>
            </dd>
          </dl>
          <p className="note">
            ⚠️ <strong>이 계정으로는 아직 로그인할 수 없다.</strong> 가입 요청에 비밀번호가 없고,
            IAM 이 만드는 Keycloak 사용자는 credential 없이 <code>emailVerified=false</code> 다.
            자격증명 설정은 <strong>Keycloak 쪽 일</strong>이며 이 콘솔의 기능이 아니다.
          </p>
          <p className="note">
            202 가 아니라 <strong>201</strong> 인 이유: outbox 때문에 Keycloak 반영은 아직이지만
            IAM 입장에서 <strong>프로필 리소스는 실제로 생성됐다.</strong> 202 를 주면 "아무것도
            안 만들어졌다" 는 오해를 준다.
          </p>
        </>
      )}
    </section>
  )
}
