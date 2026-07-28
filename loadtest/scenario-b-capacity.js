import http from 'k6/http'
import { check, sleep } from 'k6'
import { Trend } from 'k6/metrics'
import { login } from './lib/session.js'

/**
 * 시나리오 B — **용량 · HPA 측정** (rate limit 완화 프로파일)
 *
 * ## 이 시나리오가 답하는 질문
 * "보호 장치를 걷어냈을 때 게이트웨이와 IAM 이 실제로 얼마를 처리하고, HPA 가 제때 따라오는가."
 *
 * ## ⚠️ 전제 — 완화 프로파일로 배포돼 있어야 한다
 * 운영 기본값(초당 5 보충)으로 이 시나리오를 돌리면 **429 만 잔뜩 나오고 CPU 는 놀아서**
 * HPA 가 반응하지 않는다. 아래 오버레이로 배포한 뒤 실행한다:
 *
 *   deploy/deploy-alpha.sh --overlay deploy/helm/unigate-gateway/values-alpha-loadtest.yaml gateway
 *
 * 429 가 관측되면 완화가 반영되지 않은 것이므로, threshold 로 **테스트를 실패시킨다** —
 * 조용히 낮은 수치가 나오면 "용량이 이 정도구나" 로 잘못 결론 내리게 된다.
 *
 * ## 사용자 분산
 * rate limit 키가 `sub` 라, 완화 후에도 사용자 하나에 몰면 그 사용자 버킷이 먼저 닿는다.
 * `USERS` 에 여러 계정을 주면 VU 를 사용자별로 나눠 실제 트래픽에 가깝게 만든다.
 *
 * 실행:
 *   k6 run -e BASE_URL=https://<gw-host> -e USERS='alice:pw1,bob:pw2' \
 *          loadtest/scenario-b-capacity.js
 */

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/+$/, '')
const TARGET_PATH = __ENV.TARGET_PATH || '/iam/profile'
const MAX_VUS = Number(__ENV.MAX_VUS || 60)

// "user:pass,user2:pass2" → [{username, password}]
const USERS = (__ENV.USERS || '')
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean)
  .map((pair) => {
    const idx = pair.indexOf(':')
    if (idx < 0) throw new Error(`USERS 형식이 잘못됐습니다: "${pair}" (user:pass)`)
    return { username: pair.slice(0, idx), password: pair.slice(idx + 1) }
  })

if (!BASE_URL || USERS.length === 0) {
  throw new Error("BASE_URL 과 USERS(예: 'alice:pw,bob:pw') 환경변수가 필요합니다.")
}

const targetDuration = new Trend('unigate_target_duration', true)

export const options = {
  scenarios: {
    ramp_vus: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { target: 5, duration: '1m' },              // 웜업 — JIT·커넥션 풀이 자리잡는다
        { target: Math.ceil(MAX_VUS / 2), duration: '2m' },
        { target: MAX_VUS, duration: '3m' },        // HPA 가 반응할 시간을 준다(스케일업+기동)
        { target: MAX_VUS, duration: '3m' },        // 정상 상태 — 이 구간 수치가 결론이다
        { target: 0, duration: '2m' },              // 램프다운 — scaleDown 안정화 창 관찰
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    // 429 가 보이면 완화 프로파일이 아니다 → 측정 자체가 무의미하므로 실패시킨다.
    'http_req_failed{status:429}': [{ threshold: 'rate==0', abortOnFail: true, delayAbortEval: '30s' }],
    'unigate_target_duration': ['p(95)<1000', 'p(99)<2000'],
    'checks': ['rate>0.99'],
  },
}

export default function () {
  // VU 번호로 사용자를 나눈다. __VU 는 1부터 시작한다.
  const user = USERS[(__VU - 1) % USERS.length]

  if (!__ITER) {
    login(BASE_URL, user.username, user.password)
  }

  const res = http.get(`${BASE_URL}${TARGET_PATH}`, {
    tags: { name: 'target' },
    // 404 는 프로필 미생성일 뿐 인증·라우팅은 성공한 것이다(P8e 의 설계).
    // 이걸 실패로 세면 "가입 안 한 계정으로 테스트" 가 곧 테스트 실패가 된다.
    responseCallback: http.expectedStatuses(200, 204, 404),
  })

  targetDuration.add(res.timings.duration)

  check(res, {
    '인증이 유지된다 (401/302 아님)': (r) => r.status !== 401 && r.status !== 302,
    'rate limit 에 걸리지 않았다': (r) => r.status !== 429,
    '5xx 가 아니다': (r) => r.status < 500,
  })

  sleep(0.5)
}

export function handleSummary(data) {
  // 요약을 파일로 남긴다. HPA 관찰(kubectl)과 시각을 맞춰 봐야 하므로,
  // 콘솔에만 남기면 나중에 대조할 수 없다.
  return {
    stdout: JSON.stringify(
      {
        p95_ms: data.metrics.unigate_target_duration?.values['p(95)'],
        p99_ms: data.metrics.unigate_target_duration?.values['p(99)'],
        requests: data.metrics.http_reqs?.values.count,
        rps: data.metrics.http_reqs?.values.rate,
        failed_rate: data.metrics.http_req_failed?.values.rate,
      },
      null,
      2,
    ),
    'loadtest/results/scenario-b-summary.json': JSON.stringify(data, null, 2),
  }
}
