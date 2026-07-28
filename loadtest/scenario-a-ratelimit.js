import http from 'k6/http'
import { check, sleep } from 'k6'
import { Counter, Rate, Trend } from 'k6/metrics'
import { ensureSession } from './lib/session.js'

/**
 * 시나리오 A — **rate limit 경계 측정** (운영 설정 그대로)
 *
 * ## 이 시나리오가 답하는 질문
 * "게이트웨이의 토큰버킷이 실제로 어디서 끊는가, 그리고 끊는 데 드는 비용은 얼마인가."
 *
 * ## ⚠️ 여기서 HPA 는 뜨지 않는다 — 그게 정상이다
 * 게이트웨이의 rate limit 키는 **인증 시 `sub`**(미인증이면 IP)다. 즉 같은 사용자로 VU 를
 * 아무리 늘려도 **버킷 하나를 공유**한다. 기본값이 초당 5 보충 · 버스트 10 이므로,
 * 초당 5 요청을 넘는 순간 나머지는 429 로 튕기고 **애플리케이션까지 도달하지 않는다.**
 * 그래서 CPU 가 오르지 않고 HPA 도 반응하지 않는다.
 *
 * 용량과 HPA 를 보려면 시나리오 B(완화 프로파일)를 쓴다. 두 시나리오는 서로 다른 질문에
 * 답하며, 하나로 합치면 **무엇이 병목인지 흐려진다.**
 *
 * 실행:
 *   k6 run -e BASE_URL=https://<gw-host> -e USERNAME=<user> -e PASSWORD=<pass> \
 *          loadtest/scenario-a-ratelimit.js
 */

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/+$/, '')
const USERNAME = __ENV.USERNAME
const PASSWORD = __ENV.PASSWORD
const TARGET_PATH = __ENV.TARGET_PATH || '/iam/profile'

if (!BASE_URL || !USERNAME || !PASSWORD) {
  throw new Error('BASE_URL · USERNAME · PASSWORD 환경변수가 필요합니다.')
}

const throttled = new Counter('unigate_throttled_429')
const accepted = new Counter('unigate_accepted_2xx')
const throttleRate = new Rate('unigate_throttle_rate')
const remainingTokens = new Trend('unigate_ratelimit_remaining')

export const options = {
  scenarios: {
    // 고정 도착률로 올린다. VU 기반이면 429 가 빨리 돌아오는 만큼 요청이 더 나가
    // "얼마를 보냈는가" 가 부하 수준이 아니라 응답 속도에 좌우된다.
    ramp_arrival: {
      executor: 'ramping-arrival-rate',
      startRate: 2,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 100,
      stages: [
        { target: 2, duration: '30s' },   // 보충률(5/s) 아래 — 전부 통과해야 한다
        { target: 5, duration: '30s' },   // 보충률과 같음 — 경계
        { target: 20, duration: '1m' },   // 초과 — 429 가 지배적이어야 한다
        { target: 2, duration: '30s' },   // 회복 — 버킷이 다시 차는지
      ],
    },
  },
  thresholds: {
    // ⚠️ 429 를 실패로 세지 않는다. 이 시나리오에서 429 는 **측정 대상이자 정상 동작**이다.
    //    http_req_failed 를 기본값으로 두면 "정책이 잘 동작할수록 테스트가 실패" 하는
    //    거꾸로 된 판정이 된다.
    'http_req_failed{expected_response:true}': ['rate<0.01'],
    // 거절은 빨라야 한다. 거절에 시간이 걸리면 보호 장치 자체가 부하가 된다.
    'http_req_duration{status:429}': ['p(95)<200'],
  },
}

export default function () {
  // ⚠️ VU 당 한 번 로그인하고, 매 iteration 마다 세션을 다시 심는다.
  //    k6 쿠키 jar 는 iteration 단위로 초기화되므로 이게 없으면 두 번째 반복부터 전부 401 이고,
  //    그런데도 요청·응답은 계속 오가서 "부하를 주고 있다" 고 착각하게 된다.
  ensureSession(BASE_URL, USERNAME, PASSWORD)

  const res = http.get(`${BASE_URL}${TARGET_PATH}`, {
    tags: { name: 'target' },
    // 429 를 예상 응답에 포함시킨다 — 그래야 위 threshold 의 expected_response 가 성립한다.
    responseCallback: http.expectedStatuses(200, 204, 404, 429),
  })

  const limited = res.status === 429
  throttled.add(limited ? 1 : 0)
  accepted.add(res.status >= 200 && res.status < 300 ? 1 : 0)
  throttleRate.add(limited)

  // SCG 의 RequestRateLimiter 가 남은 토큰을 헤더로 알려준다.
  // 이 값이 5씩(요청당 토큰 수) 줄어드는지 보면 버킷이 라우트별로 분리됐는지도 함께 확인된다.
  const remaining = res.headers['X-Ratelimit-Remaining']
  if (remaining !== undefined) remainingTokens.add(Number(remaining))

  check(res, {
    // ⚠️ **이 검사가 가장 중요하다.** 세션이 끊기면 요청은 계속 나가고 응답도 오지만
    //    백엔드에는 도달하지 않는다. 아래 두 검사는 401 에도 통과하므로 이것 없이는
    //    "전부 통과했는데 실은 아무것도 측정하지 않은" 상태를 구분할 수 없다.
    '인증이 유지된다 (401/302 아님)': (r) => r.status !== 401 && r.status !== 302,
    '429 는 본문 대신 헤더로 사유를 준다': (r) => r.status !== 429 || r.headers['X-Ratelimit-Remaining'] !== undefined,
    '5xx 가 아니다': (r) => r.status < 500,
  })

  sleep(0.1)
}
