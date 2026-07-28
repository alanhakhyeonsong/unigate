# k6 부하테스트 가이드

> 게이트웨이(BFF)와 IAM 을 대상으로 한 부하 시나리오.
> **시나리오 정의와 측정 결과는 [`SCENARIOS.md`](./SCENARIOS.md)** 에 있다. 이 문서는 실행 방법이다.

## 목적(Goal)

| 시나리오 | 답하는 질문 | rate limit |
|---|---|---|
| **A** `scenario-a-ratelimit.js` | 보호 장치가 **어디서 끊는가**, 끊는 비용은 얼마인가 | 운영 기본값 |
| **B** `scenario-b-capacity.js` | 앱이 **얼마를 처리하는가**, HPA 가 제때 따라오는가 | 완화 오버레이 |

## 배경(Context) — 왜 시나리오를 나누는가

게이트웨이의 rate limit 키는 **인증 시 `sub`**(미인증이면 IP)다. 같은 사용자로 VU 를 아무리
늘려도 **버킷 하나를 공유**하므로, 운영 기본값(초당 5 보충 · 버스트 10)에서는 초당 5 요청을
넘는 순간 나머지가 429 로 튕겨 **애플리케이션에 도달하지 않는다.**

그 상태로 측정하면:

- CPU 가 오르지 않아 **HPA 가 반응하지 않는다**
- 측정되는 것은 앱 용량이 아니라 **Valkey 토큰버킷의 처리량**이다

두 질문은 서로 다르고, 답을 얻는 조건도 다르다. 그래서 나눈다.

## 설계(Design) — Bearer 토큰이 통하지 않는다

```mermaid
flowchart LR
    K6["k6 VU"] -->|"① GET /oauth2/authorization/keycloak"| GW["gateway (BFF)"]
    GW -->|"302"| KC["Keycloak 로그인 폼"]
    K6 -->|"② POST username/password"| KC
    KC -->|"302 code"| GW
    GW -->|"③ Set-Cookie SESSION"| K6
    K6 -->|"④ 이후 요청 (세션 쿠키)"| GW
    GW -->|"토큰 릴레이"| IAM["iam"]
```

게이트웨이는 **OAuth2 Client(BFF)** 이고 Resource Server 가 아니다. `Authorization: Bearer` 를
붙여도 인증으로 취급하지 않는다 — 미인증으로 보고 401(XHR) 또는 302 를 낸다.

따라서 Keycloak Direct Access Grants 로 토큰만 받아 오는 흔한 방식은 **여기서 통하지 않고**,
`lib/session.js` 가 Authorization Code 플로우를 실제로 통과해 세션 쿠키를 얻는다.
부하테스트 전용 client 를 따로 만들 필요가 없다는 뜻이기도 하다.

## ⚠️ 세션 취급 — 이 스크립트에서 가장 틀리기 쉬운 곳

**k6 의 쿠키 jar 는 VU 가 아니라 iteration 단위로 초기화된다.**
그래서 "첫 반복에서만 로그인" 하는 흔한 패턴이 여기서는 조용히 무너진다 —
두 번째 반복부터 쿠키가 사라져 모든 요청이 401 을 받는데, **요청·응답은 계속 오가므로
처리량 그래프는 정상으로 보인다.**

`lib/session.js` 의 `ensureSession()` 이 이걸 처리한다:

```js
export default function () {
  const user = USERS[(__VU - 1) % USERS.length]
  ensureSession(BASE_URL, user.username, user.password)  // VU 당 1회 로그인 + 매 iteration 세션 주입
  ...
}
```

두 가지를 함께 해결한다:

| 문제 | 해결 |
|---|---|
| iteration 마다 쿠키가 사라진다 | 세션 값을 **VU 스코프 변수**(모듈 최상위)에 캐시해 매번 jar 에 심는다 |
| `setup()` 에서 여러 사용자를 순차 로그인하면 두 번째부터 실패 | VU 별로 로그인한다. VU 마다 jar 가 독립이라 **다른 사용자의 Keycloak SSO 세션이 섞일 자리가 없다** |

> 두 문제 모두 실제로 겪었다. 경위와 증상은 `SCENARIOS.md` 참조.

## 사전 준비

1. **k6 설치** — `brew install k6`
2. **테스트 계정** — alpha realm 은 테스트 사용자를 만들지 않는다(`CREATE_TEST_USERS=false`).
   가입 API 로 만드는 것이 가장 현실적이다. 프로필까지 생겨 `/iam/profile` 이 200 을 준다:

   ```bash
   curl -X POST "https://<gw-host>/iam/register" -H "Content-Type: application/json" \
     -d '{"email":"loadtest-01@example.com","displayName":"Load","firstName":"Load","lastName":"Test"}'
   ```

   ⚠️ 가입은 **IP 기준 rate limit**(분당 12회 · 버스트 3)이 걸려 있다. 여러 개를 만들 때는
   요청 사이에 간격을 둔다.

3. **비밀번호 설정** — 가입 API 는 비밀번호를 받지 않는다. Keycloak Admin API 로 건다:

   ```bash
   curl -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     "$KEYCLOAK_URL/admin/realms/unigate/users/$USER_ID/reset-password" \
     -d '{"type":"password","value":"<pw>","temporary":false}'
   ```

   ⚠️ **`temporary:false` 가 핵심이다.** true 면 로그인 직후 비밀번호 변경 화면으로 넘어가
   k6 가 폼에서 멈춘다 — 증상은 "로그인 실패" 로만 보인다.

4. **결과 디렉토리** — `loadtest/results/` 는 저장소에 있다. k6 의 `handleSummary` 는
   없는 디렉토리를 만들지 않고 `could not open ...` 로 실패한다.

## 실행(Flow)

### 시나리오 A — rate limit 경계

```bash
k6 run \
  -e BASE_URL=https://<gw-host> \
  -e USERNAME=<user> -e PASSWORD=<pass> \
  loadtest/scenario-a-ratelimit.js
```

보는 것:

| 지표 | 의미 |
|---|---|
| `unigate_throttle_rate` | 429 비율. 도착률이 보충률을 넘는 구간에서 올라야 한다 |
| `unigate_ratelimit_remaining` | 남은 토큰. **중앙값이 0** 이면 버킷이 실제로 바닥까지 갔다는 뜻 |
| `http_req_duration{status:429}` | **거절 비용.** 거절이 느리면 보호 장치가 곧 부하가 된다 |

### 시나리오 B — 용량 · HPA

먼저 완화 프로파일로 배포한다:

```bash
deploy/deploy-alpha.sh --yes --skip-build --tag <기존태그> \
  --overlay deploy/helm/unigate-gateway/values-alpha-loadtest.yaml gateway

# 완화가 실제로 반영됐는지 확인 — 안 되어 있으면 측정 자체가 무의미하다
kubectl -n <ns> get deploy unigate-gateway-deploy \
  -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}' \
  | grep RATELIMIT
```

```bash
# HPA 를 별도 창에서 관찰한다 — k6 요약만으로는 언제 늘었는지 알 수 없다
kubectl -n <ns> get hpa -w

k6 run \
  -e BASE_URL=https://<gw-host> \
  -e USERS='u1@example.com:pw,u2@example.com:pw' \
  -e MAX_VUS=36 \
  loadtest/scenario-b-capacity.js
```

**테스트가 끝나면 오버레이 없이 다시 배포해 원래 값으로 되돌린다.**
완화된 동안 게이트웨이는 사실상 무방비다.

## 예외/에러 처리(Error Handling)

| 증상 | 원인 | 확인 |
|---|---|---|
| `로그인 폼을 찾지 못했습니다 (status=404)` | 앞선 로그인의 **Keycloak SSO 세션**이 jar 에 남아 폼을 건너뛴다 | 사용자별 로그인을 VU 안에서 하는지(= `ensureSession` 사용) 확인 |
| `로그인 폼을 찾지 못했습니다` (그 외 status) | Keycloak 응답이 로그인 페이지가 아니다 | redirect_uri 등록, realm 이름, 게이트웨이 issuer 설정 |
| `로그인 실패 (자격증명 불일치)` | 계정/비밀번호 | Keycloak 콘솔에서 계정 상태 |
| `로그인 실패 (리다이렉트 설정…)` | client 의 redirect URI 에 게이트웨이 주소가 없다 | `setup-realm.sh --env alpha --alpha-host <gw-host>` |
| 비밀번호 변경 화면에서 멈춤 | 계정의 `Temporary password` 가 켜져 있다 | reset-password 를 `temporary:false` 로 다시 |
| **429 는 0인데 성공도 거의 없다** | **세션이 끊겨 전부 401** — 가장 헷갈리는 경우 | `인증이 유지된다` 검사가 실패하는지 본다. HPA CPU 가 안 오르면 백엔드 미도달 |
| 시나리오 B 가 429 로 즉시 중단 | 완화 오버레이가 반영되지 않았다 | 위 `grep RATELIMIT` |
| `could not open 'loadtest/results/...'` | 결과 디렉토리 없음 | `mkdir -p loadtest/results` |

## 운영 고려사항(Operations)

- **성공만 검사하면 실패가 침묵한다.** `5xx가 아니다` 같은 검사는 401 에도 통과한다.
  `인증이 유지된다 (401/302 아님)` 를 반드시 넣는다 — 이 한 줄이 "성공적으로 아무것도
  측정하지 않은" 회차를 걸러낸다.
- **트레이싱 샘플링**: 운영 기본이 100% 라 부하 중에는 그 자체가 오버헤드이자 왜곡이다.
  완화 오버레이는 5% 로 낮춘다. 대신 traceId 상관관계 검증은 이 프로파일로 하지 않는다.
- **부하 발생 위치**: 로컬에서 ingress 를 통해 쏘면 인터넷 구간이 병목일 수 있다.
  수치가 낮게 나오면 클러스터 안에서(Job 으로) 한 번 더 재본다.
- **결과 파일**: `loadtest/results/` 는 커밋하지 않는다(`.gitkeep` 만 추적). 실제 호스트가 담긴다.

## 남은 것

- 클러스터 내부에서 k6 를 Job 으로 돌리는 매니페스트 (네트워크 구간 제거)
- IAM 직격 시나리오(Bearer) — GW 우회 경로라 운영 경로는 아니지만 IAM 단독 용량 측정에 쓸 수 있다
- 다운스트림 경로(`/api/**`) 시나리오 — 토큰 릴레이 + audience 검증까지 포함한 전 구간
