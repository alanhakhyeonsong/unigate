# 41. 무엇이 먼저 무너지는가 — 포화의 위치를 찾는 법

> 부하테스트의 결론은 "얼마나 처리하는가" 가 아니라 **"어디가 먼저 밀리는가"** 다.
> 게이트웨이를 8개로 늘려도 상한을 정한 것은 replica 1개짜리 IAM 이었고,
> 그 포화는 장애가 아니라 **3~18ms 짜리 거절**로 나타났다.
> 관련: Phase 6+ · 브랜치 `test/loadtest-capacity-ceiling` ·
> 코드 `loadtest/scenario-b-capacity.js` · `gateway/src/main/resources/application.yml` (resilience4j)

## 1. 왜 필요했나

2026-07-28 회차(→ [28](28-k6-loadtest-silent-failures.md))는 측정을 마치고 이렇게 적어 두었다.

> 최대 처리량을 알려면 노드 여유를 확보해 상한을 올리고, `request` 를 실사용에 맞게 되돌려
> HPA 기준을 정상화해야 한다.

그때는 노드 세 대의 CPU 예약이 94~99% 라 파드가 **한 노드에만** 들어갔다. `maxReplicas 4` 는
설계값이 아니라 "그 노드의 메모리 여유 ÷ 512Mi" 였고, `podAntiAffinity` 를 걸면 아예 스케줄되지
않았다. 즉 **측정 장치가 클러스터 사정에 갇혀 있었다.**

2026-08-02 에 노드가 한 대 늘고 예약이 풀려 그 조건이 사라졌다. 미뤄 둔 세 가지 —
request 정상화 · 상한 상향 · 노드 분산 — 를 한꺼번에 풀고 다시 쟀다.

그리고 그 과정에서 **예상하지 못한 것 두 가지**를 만났다. 이 문서는 그쪽이 본론이다.

- 부하를 올렸더니 무너진 곳이 게이트웨이가 아니었다
- 본 실행에 들어가기도 전에 **부하 스크립트가 먼저 죽었다** — 앱은 멀쩡한데

## 2. 익숙한 방식과의 대조

| | 익숙한 방식 (단일 앱 · 모놀리스) | 여기서의 방식 (게이트웨이 + IAM) | 왜 다른가 |
|---|---|---|---|
| "용량" 의 주체 | 앱 하나. CPU·스레드풀이 곧 상한 | **경로 위의 가장 약한 고리.** 게이트웨이가 여유로워도 뒤가 밀리면 거기가 상한 | 요청 하나가 두 앱을 지난다 |
| 포화의 증상 | 스레드풀 고갈 → 지연이 계속 늘다가 타임아웃 | **지연이 늘다가 갑자기 짧아진다.** 회로가 열리면 즉시 거절 | Circuit Breaker 가 느린 실패를 빠른 실패로 바꾼다 |
| 실패한 요청의 흔적 | 백엔드 로그에 남는다 | **백엔드 로그가 깨끗하다** — 도달조차 안 했으니 | 게이트웨이가 대신 끊었다 |
| 오토스케일 기준 | CPU 사용률(절대량) | **실사용 ÷ request** — request 를 낮추면 지표가 과민해진다 | 스케줄링과 오토스케일링이 같은 값을 공유한다 |
| 부하 스크립트 수명 | 앱 API 가 그대로면 계속 유효 | **배포 구성이 바뀌면 낡는다** (착지 URL 등) | BFF 는 로그인 흐름 자체가 배포 값에 얽혀 있다 |

## 3. 동작 원리

### 3.1 포화가 거절로 바뀌는 연쇄

`/iam/**` 라우트에는 Circuit Breaker(`name=iam`)와 TimeLimiter(5s)가 걸려 있다.
IAM 이 밀리면 다음 순서로 **포화가 거절로 번역된다.**

```mermaid
flowchart TD
    A["IAM replica 1 · CPU 877m (limit 1000m 의 88%)"] --> B["응답 지연 상승"]
    B --> C["timelimiter(iam) 5s 초과 → 실패로 기록"]
    C --> D["sliding window 10건 중 실패율 50% 초과"]
    D --> E["회로 OPEN (10s 유지)"]
    E --> F["IAM 호출 없이 forward:/fallback/iam"]
    F --> G["503 + reasonCode=iam_unavailable (3~18ms)"]
    G --> H["IAM 로그는 깨끗 · 게이트웨이는 매달리지 않음"]
```

핵심은 **E 이후로 IAM 을 부르지 않는다**는 점이다. 그래서:

- 게이트웨이 응답이 **빨라진다**(수천 ms → 한 자릿수 ms). 지연 그래프만 보면 회복처럼 보인다
- **IAM 로그에는 아무 흔적이 없다.** "백엔드가 멀쩡한데 5xx 가 난다" 로 보인다

### 3.2 HPA utilization 이 request 에 매인다는 것

```mermaid
flowchart LR
    R["request (스케줄링 기준)"] --> U["utilization = 실사용 / request"]
    U --> H["HPA 판단"]
    R --> S["스케줄러 배치 결정"]
    S -.->|"포화 클러스터에서는 낮춰야 통과"| R
    H -.->|"정확하려면 실사용에 맞춰야"| R
```

같은 값 하나(`request`)를 **스케줄러와 HPA 가 공유하는데 요구 방향이 반대**다.
2026-07-28 회차는 스케줄을 통과시키려 `50m` 까지 낮췄고, 그 대가로 실사용 200m 이
**400%** 로 보였다. 앱이 아니라 지표가 먼저 포화한 것이다.

### 3.3 `topologySpreadConstraints` 의 조용한 실패

노드 분산에는 `podAntiAffinity` 대신 `topologySpreadConstraints` 를 썼다.

| | `podAntiAffinity` (required) | `podAntiAffinity` (preferred) | `topologySpreadConstraints` |
|---|---|---|---|
| replica > 노드 수 | **Pending** (스케줄 불가) | 배치는 되나 균등 보장 없음 | `maxSkew` 로 편차 제한 |
| 강제력 | 절대 | 가중치 힌트 | `whenUnsatisfiable` 로 선택 |

`ScheduleAnyway` 를 골랐다. `DoNotSchedule` 은 균등을 강제하지만 노드 여유가 어긋나면
Pending 이 쌓이고, **그 상태는 "용량 한계" 와 구분되지 않는다** — 2026-07-28 에
`podAntiAffinity(required)` 로 겪은 것과 같은 함정이다.

> ⚠️ **`labelSelector` 를 빠뜨린 제약은 에러가 아니라 무효다.** 셀렉터가 없으면 세는 대상이
> 0개라 skew 가 항상 0 이고, 제약은 걸려 있는데 스케줄러는 아무 제한도 받지 않는다.
> 매니페스트만 보면 분산이 켜져 있는 것처럼 보인다. 그래서 라이브러리 차트가
> 셀렉터를 **자동 주입**하게 만들었다(`unigate-common/templates/_deployment.tpl`).

## 4. 직접 확인한 것

> ⚠️ 이 절은 실제로 실행한 명령과 실제 출력만 담는다. 실제 호스트·계정·비밀번호는 마스킹했다.

### 확인 1 — 노드 여유가 실제로 회복됐다

```bash
# allocatable - (모든 파드의 requests 합) 을 노드별로 계산
kubectl get nodes -o json / kubectl get pods -A -o json  → python3 집계
```

```
node                                 cpu free     mem free   pods 512Mi/200m
common-default-worker-node-0           3245m       6.57Gi                13
common-default-worker-node-1           2685m       5.02Gi                10
common-default-worker-node-2            445m       1.02Gi                 2
common-default-worker-node-3           3845m       8.30Gi                16
```

관찰: 2026-07-28 에는 **총 4개**(node-1 에만)였다. 지금은 41개. `worker-node-3` 은
그때 없던 노드다.

### 확인 2 — 라이브러리가 `labelSelector` 를 자동 주입한다

values 에는 셀렉터를 쓰지 않았다.

```bash
helm template unigate-gateway deploy/helm/unigate-gateway \
  -f values-alpha.yaml -f values-alpha-loadtest.yaml
```

```yaml
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              app.kubernetes.io/name: unigate-gateway
              app.kubernetes.io/instance: unigate-gateway
```

관찰: 값에 없던 `labelSelector` 가 채워졌다. 명시로 준 경우에는 그 값이 그대로 유지되는 것도
같은 렌더로 확인했다(별도 항목에 `custom: explicit` 를 주고 확인).

### 확인 3 — 스모크 테스트가 본 실행을 살렸다 (예상 못 한 실패)

11분짜리 본 실행 전에 2초짜리 스모크를 돌렸더니 6개 계정 전부 죽었다.

```
GoError: 로그인 실패 (리다이렉트 설정(redirect_uri) 또는 realm 구성 확인 필요) status=200
	at login (file:///.../loadtest/lib/session.js:71:9(102))
```

메시지는 realm 을 가리켰지만, Keycloak 의 계정 상태는 멀쩡했다.

```bash
# 계정에 걸린 required action 확인
curl -H "Authorization: Bearer <admin-token>" \
  "$KEYCLOAK_URL/admin/realms/$REALM/users?search=loadtest&max=6"
```

```
loadtest-01@example.com | requiredActions= [] | emailVerified= False | enabled= True
... (6개 모두 동일)
```

그래서 **착지 URL 을 직접 찍어 봤다.**

```
authorize: status=200 url=https://<keycloak-host>/realms/unigate/protocol/openid-connect/auth?...
form found: true
login POST: status=200
landed url : https://<console-host>/
baseUrl    : https://<gateway-host>
startsWith(baseUrl) = false
form again = false
title      : unigate 검증 콘솔
```

관찰: **로그인은 성공했다.** 착지가 게이트웨이가 아니라 **FE 콘솔**이었을 뿐이다.
`lib/session.js` 의 판정이 `r.url.startsWith(baseUrl)` 였는데, FE 분리 배포로
`UNIGATE_FRONTEND_BASE_URI` 가 들어오면서(PR #47) 착지가 바뀐 것이다.

판정을 **세션 쿠키 유무**로 바꾼 뒤 재실행:

```
checks_succeeded...: 100.00% 36 out of 36
    ✓ 로그인 폼이 다시 나오지 않았다
    ✓ 게이트웨이 세션 쿠키가 생겼다
    ✓ 인증이 유지된다 (401/302 아님)
```

### 확인 4 — 100 VU: 분산과 스케일아웃이 실제로 동작한다

```bash
k6 run -e BASE_URL=https://<gw-host> -e USERS='<6개 계정>' \
       -e MAX_VUS=100 -e SLEEP_SECONDS=0.1 loadtest/scenario-b-capacity.js
```

```
✓ unigate_target_duration p(95)<1000   ✓ p(99)<2000
✓ http_req_failed{status:429} rate==0  ✓ checks rate>0.99

  요청 수      : 205708 (311.65 req/s)
  실패율       : 0%
  429 발생     : 0
  지연         : avg=98.13ms med=81.81ms p(95)=195.78ms max=2354.3ms
  checks       : 615822 통과 / 2 실패

  부하 생성기  : blocked p(95)=0ms · connecting p(95)=0ms · waiting p(95)=193.37ms
  VU 최대      : 100 · think time 0.1s
```

부하 중 15초 간격 관측(HPA · 노드 분포 · 파드별 CPU):

```
14:32:07 | hpa=3%/60%   replicas=2 desired=2 | nodes: 1 node-0 1 node-3
14:33:43 | hpa=131%/60% replicas=3 desired=5 | nodes: 2 node-0 2 node-1 1 node-3
14:36:11 | hpa=184%/60% replicas=8 desired=8 | nodes: 2 node-0 3 node-1 3 node-3
14:41:39 | hpa=162%/60% replicas=8 desired=8 | nodes: 2 node-0 3 node-1 3 node-3
           gw:...=309m gw:...=304m gw:...=324m gw:...=289m
           gw:...=336m gw:...=321m gw:...=287m gw:...=327m  iam:...=686m
```

관찰:

1. 8개가 `maxSkew=1` 을 지켜 **2/3/3** 으로 갈렸다. Pending 은 0.
   (2026-07-28 에는 4개가 전부 `worker-node-1` 에 있었다.)
2. HPA 가 **156~195%/60%** 다. 같은 종류의 부하에서 지난 회차는 **400%** 였다 —
   `request` 를 50m → 200m 로 되돌린 효과다.
3. 게이트웨이 파드당 ~320m 은 limit(1000m)의 32%. **아직 포화가 아니다.**
   가장 바쁜 것은 **IAM(686m)** — replica 1개, HPA 없음.
4. `blocked`·`connecting` 이 0ms 인데 `waiting` 이 193ms 다. 지연의 99%가 서버 시간이므로
   **로컬 부하 생성기는 병목이 아니다.**

> **스케일아웃 과도기에는 파드별 부하가 고르지 않다.** 새 파드가 뜨는 구간에는
> `998m` 과 `249m` 이 공존했다(4배). 8개가 다 Ready 가 된 뒤에야 287~336m 으로 고르게 갔다.
> 2026-07-28 회차에 "모든 파드가 고르게 받았다" 고 적었는데, 그건 **정상 상태의 성질**이지
> 스케일아웃 중의 성질이 아니다.

### 확인 5 — 200 VU: 여기서 무너졌다

think time 을 0으로 두면 요청률이 응답시간에만 좌우돼 앱이 버티는 만큼만 나간다.

```bash
k6 run ... -e MAX_VUS=200 -e SLEEP_SECONDS=0 loadtest/scenario-b-capacity.js
```

```
✗ checks rate>0.99
✓ http_req_failed{status:429} rate==0  ✓ p(95)<1000  ✓ p(99)<2000

  요청 수      : 380266 (576.17 req/s)
  실패율       : 21.43%
  429 발생     : 0
  지연         : avg=213.57ms med=184.76ms p(95)=475.19ms max=5115.6ms
  checks       : 1056709 통과 / 81489 실패
```

체크별로 갈라 보면 **무너진 곳이 하나뿐**이다.

```
  인증이 유지된다 (401/302 아님) ... 통과 379266 / 실패     0
  rate limit 에 걸리지 않았다 ...... 통과 379266 / 실패     0
  5xx 가 아니다 .................... 통과 297777 / 실패 81489
```

### 확인 6 — 5xx 의 정체: IAM 이 아니라 회로가 낸 것

먼저 IAM 로그를 봤다.

```bash
kubectl -n <ns> logs deploy/unigate-iam-deploy --since=15m | grep -iE "error|exception|timeout|refused"
```

```
(출력 없음)
```

관찰: **실패한 요청이 IAM 에 도달조차 하지 않았다.**

게이트웨이 로그:

```bash
kubectl -n <ns> logs -l app.kubernetes.io/name=unigate-gateway --since=15m --tail=-1 | grep 503
```

```
INFO ... [llEventLoop-5-2] RequestLoggingFilter : [post] GET /iam/profile status=503 SERVICE_UNAVAILABLE signal=onComplete elapsedMs=10
INFO ... [llEventLoop-5-2] RequestLoggingFilter : [post] GET /iam/profile status=503 SERVICE_UNAVAILABLE signal=onComplete elapsedMs=6
... (elapsedMs=3~18 범위)
```

관찰: 백엔드를 왕복한 시간이 아니다. WARN/ERROR 레벨 로그는 **한 줄도 없었다**
(resilience4j 는 회로 전이를 로그로 내지 않는다 — actuator 이벤트로만 노출된다).

그래서 **응답 본문을 직접 잡으러** 짧게 재현했다(200 VU · 105초).

```js
if (res.status >= 500 && /iam_unavailable/.test(res.body)) { console.log(res.body) }
```

```json
{"type":"about:blank","title":"IAM Unavailable","status":503,
 "detail":"IAM 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도하세요.",
 "reasonCode":"iam_unavailable"}
```

관찰: `reasonCode: iam_unavailable` 은 `FallbackRoutes.iamFallbackRouter` 만 내는 값이다.
**IAM Circuit Breaker 가 열린 것이 확정됐다.** k6 가 관측한 `max=5115.6ms` 가
`timelimiter.instances.iam.timeout-duration: 5s` 와 맞아떨어지는 것이 연쇄의 시작점을 가리킨다.

### 확인 7 — 측정이 끝나고 오버레이를 원복했다

```bash
deploy/deploy-alpha.sh --yes --skip-build --tag <tag> gateway   # 오버레이 없이
```

```
(오버레이 값 없음 → application.yml 기본값 사용)
{"limits":{"cpu":"1","memory":"1536Mi"},"requests":{"cpu":"50m","memory":"512Mi"}}
unigate-gateway-hpa   Deployment/unigate-gateway-deploy   cpu: 16%/60%   2   4   4
```

관찰: `RATELIMIT_*` 환경변수가 사라져 운영 기본값(5/10/1)으로 돌아갔고,
`maxReplicas` 도 4로, 분산 제약도 제거됐다. **완화 프로파일 동안 게이트웨이는 사실상 무방비다.**

## 5. 함정 / 실패 모드

| 함정 | 증상 | 원인 | 해결 |
|---|---|---|---|
| **백엔드 로그가 깨끗한 5xx** | 인증·429 는 정상인데 5xx 만 쏟아진다. 백엔드를 뒤져도 아무 흔적이 없다 | CB 가 열려 **백엔드를 부르지 않는다** | 응답 본문의 `reasonCode` 와 게이트웨이 `elapsedMs` 를 본다. **한 자릿수 ms 면 회로다** |
| **포화인데 응답이 빨라진다** | 지연 그래프가 회복처럼 보인다 | 회로가 열리면 즉시 거절이라 지연이 **짧아진다** | 지연만 보지 말고 **실패율과 함께** 본다 |
| **`request` 를 낮춰 HPA 가 과민해진다** | 상한에 붙었는데 응답은 멀쩡하다 | utilization = 실사용 ÷ request | 부하 창에서만 request 를 실사용에 맞춘다. 운영값은 스케줄 통과를 우선 |
| **`labelSelector` 없는 spread 제약** | 매니페스트엔 분산이 켜져 있는데 **한 노드에 몰린다.** 에러 없음 | 셀렉터가 없으면 세는 대상이 0개 → skew 항상 0 | 라이브러리가 `selectorLabels` 를 자동 주입 |
| **`DoNotSchedule` 로 균등 강제** | Pending 이 쌓이고 "용량 한계" 처럼 보인다 | 노드 여유가 어긋나면 배치 자체가 막힌다 | `ScheduleAnyway`. 재려는 건 강제력이 아니라 분산됐을 때의 거동 |
| **착지 URL 로 로그인 성공 판정** | 앱은 멀쩡한데 부하테스트만 죽는다. 메시지는 realm·redirect_uri 를 가리킨다 | 배포 구성(`UNIGATE_FRONTEND_BASE_URI`)이 바뀌면 착지가 바뀐다 | 판정을 **세션 쿠키**로. 실패 메시지에 착지 URL 을 넣어 다음엔 바로 보이게 |
| **고정 think time 으로 포화점 탐색** | VU 를 올려도 요청률이 비례해 안 오른다 | `sleep(0.5)` 가 VU 당 요청률에 천장을 만든다 | 포화 탐색에는 `SLEEP_SECONDS=0` |
| **과도기 편차를 정상 상태로 오독** | "파드마다 부하가 4배 차이난다" | 새 파드가 Ready 되기 전 구간 | Ready 수가 안정된 뒤 구간만 결론에 쓴다 |

### 이번에 새로 배운 판단 기준

**"처리량이 얼마인가" 를 묻기 전에 "무엇이 상한을 정하는가" 를 먼저 확인한다.**
576 req/s 는 게이트웨이의 수치가 아니라 **IAM replica 1개가 정한 전 구간 상한**이었다.
경로 위에 스케일 정책이 다른 앱이 섞여 있으면, 가장 약한 고리를 찾기 전의 수치는
"어떤 조합에서 이만큼 나왔다" 이상을 말하지 못한다.

## 6. 남은 의문

### 이번에 답이 나온 것

- [x] 노드 분산이 실제로 걸리는가 → **걸린다.** 8개가 2/3/3, Pending 0 (§4 확인 4)
- [x] `request` 정상화가 HPA 왜곡을 없애는가 → **없앤다.** 400% → 156~195% (§4 확인 4)
- [x] 로컬에서 쏘는 것이 병목인가 → **아니다.** blocked·connecting p95 = 0ms (§4 확인 4)
      → README "남은 것" 의 **클러스터 내부 k6 Job 은 이 근거로 접었다**
- [x] 576 req/s 에서 무엇이 무너졌나 → **IAM CB.** 본문 `reasonCode=iam_unavailable` 로 확정 (§4 확인 6)

### 아직 모르는 것

- [ ] **게이트웨이 자체의 상한은 얼마인가** — IAM 이 먼저 막혀 그 너머를 못 봤다.
      IAM 에 request 정상화 + HPA 를 넣고 같은 부하를 다시 걸면 답이 나온다
- [ ] **회로가 몇 번 열렸다 닫혔나** — 실패율 21.43% 가 "계속 열려 있었다" 인지
      "열림·half-open 을 반복했다" 인지 구분하지 못했다. resilience4j 는 전이를 로그로 내지 않으므로,
      `/actuator/circuitbreakerevents` 를 부하 중 폴링하거나 상태 전이를 로그로 내는 이벤트 리스너가 필요하다.
      ⚠️ actuator 는 **인증 뒤에 있어**(`SecurityConfig`) 지금 구성으로는 부하 중 스크랩이 안 된다 —
      [27](27-helm-library-chart-and-alpha-deploy.md) 에 적힌 관측성 공백과 같은 뿌리다
- [ ] **IAM 의 포화 원인이 CPU 인가 다른 것인가** — 877m(limit 의 88%)까지 갔지만,
      JPA 커넥션 풀·VT pinning([37](37-vt-pinning-measurement.md))·Keycloak 왕복 중
      무엇이 지배적인지는 갈라 보지 않았다
- [ ] **5xx 2건(100 VU 회차)의 정체** — 205,208건 중 2건이라 CB 와 무관한 산발적 실패로 보이지만
      확인하지 않았다. 그 시각의 게이트웨이 로그를 좁혀 보면 답이 나온다
