# samples — 검증용 로컬 앱

> ⚠️ **레퍼런스 구현이 아니다. 일부러 취약하게 만든 코드가 들어 있다.**
> 여기 있는 것을 제품 코드에 복사하지 말 것. 무엇이 왜 취약한지는 §3 에 적어 두었다.

게이트웨이·IAM 의 동작을 **눈으로 확인**하기 위한 앱이다. unigate 의 산출물이 아니며,
`settings.gradle.kts` 에 include 하지 않으므로 `./gradlew build` 는 이 디렉토리의 영향을 받지 않는다.

| 앱 | 스택 | 포트 | 역할 |
|---|---|---|---|
| `downstream-demo` | Kotlin · Spring MVC · Resource Server | 8081 | 제품 다운스트림 흉내. 받은 헤더를 되비추고 테넌트 격리를 강제한다 |
| `downstream-billing` | Kotlin · Spring MVC · Resource Server | 8082 | **2대째** 다운스트림. 소비자가 하나일 때는 **구조적으로 안 보이는 것**을 드러낸다(§6) |
| `frontend-demo` | React 18 · TS · TanStack Query · Vite | 5173 | BFF 를 쓰는 SPA. 로그인·CSRF·테넌트·비동기 반영을 화면으로 드러낸다 |

> **왜 다운스트림이 둘인가:** 공유 `aud` 재생 · claim 누출 · 교차 테넌트 판정 · 회로 격리는
> **소비자가 2대여야 값이 눈에 보이는 형태로 나타난다.** 1대일 때의 "정상" 은 검증이 아니다.
> 상세는 §6 과 `docs/learning/43` · `44`.

## 1. 실행

```bash
# 사전: docker compose up -d  (postgres · valkey)
source ./keycloak.secret.env          # Keycloak 좌표 — 기본값이 없다(§2)

./gradlew :gateway:bootRun            # 8080
./gradlew :iam:bootRun                # 8090
(cd samples/downstream-demo && ./gradlew bootRun)      # 8081
(cd samples/downstream-billing && ./gradlew bootRun)   # 8082
(cd samples/frontend-demo && npm install && npm run dev)  # 5173
```

> §6 의 시나리오를 재현하려면 **두 다운스트림을 동시에** 띄워야 한다. 하나만 띄우면
> 재생·격리 시나리오가 성립하지 않고, `/api/billing/**` 는 회로가 열려 503 이 된다.

브라우저는 **5173** 으로 연다. Vite dev proxy 가 게이트웨이를 같은 origin 으로 보이게 한다 —
그래야 세션 쿠키·CSRF 쿠키가 그냥 동작한다.

> Keycloak client 에 `http://localhost:5173/login/oauth2/code/keycloak` 이 등록돼 있어야 한다.
> `scripts/keycloak/setup-realm.sh --env local` 이 등록한다. 없으면 로그인이
> `Invalid parameter: redirect_uri` 로 끊기고 **게이트웨이 로그에는 아무것도 남지 않는다.**

## 2. 실제 좌표를 담지 않는다

`CLAUDE.md` §8 — 이 저장소는 public 리모트다. 샘플 설정에도 호스트·계정을 하드코딩하지 않고
환경변수로만 받는다. **기본값(fallback)도 두지 않는다** — 주입을 빠뜨렸을 때 조용히 엉뚱한 곳을
바라보는 것보다 기동이 실패하는 편이 낫다.

`.env.alpha` 는 `.gitignore` 대상이다. 커밋되는 것은 `.env.alpha.example`(placeholder)뿐이다.

## 3. ⚠️ 일부러 취약하게 만든 것들

검증 목적으로 **의도적으로** 남겨둔 구멍이다. 각 파일 KDoc 에 이유가 적혀 있다.

| 위치 | 무엇이 취약한가 | 무엇을 증명하려고 두었나 |
|---|---|---|
| `downstream-demo` `/legacy/orders` | 요청 **본문의 `tenantId`** 를 그대로 써서 자원을 만든다 | default-deny 인가는 **쓰기 경로를 지켜주지 못한다**. 헤더는 정직해도 본문이 남의 테넌트를 가리킬 수 있다 |
| `downstream-demo` `/echo` | 인가 규칙의 **예외**라 테넌트 검증을 받지 않는다 | 게이트웨이를 우회하면 위조 `X-Tenant-Id` 가 그대로 도달한다(P9g 실측) |
| `downstream-demo` `/invoices` | 테넌트에 관한 코드가 **한 줄도 없다** | 그런데도 막힌다 — `anyRequest` 인가 규칙이 문 앞에서 거른다(잊어도 닫히는 기본값) |
| `downstream-demo` `/public/ping` | 인증 없이 200 | 브라우저에서 다운스트림 origin 을 얻기 위한 **검증 도구**. 자원이 아니다 |
| **`downstream-billing` `/subscriptions/{id}`** | 테넌트 판정을 **토큰의 소속 목록**으로 한다 | **게이트도 제품도 각자 옳은데 합치면 교차 테넌트가 열린다**(§6). 대조군은 바로 아래 줄 |
| `downstream-billing` `ResourceServerConfig` | `anyRequest` 가 `authenticated` 까지만 — 문 앞에서 테넌트를 **안 본다** | demo 의 "잊으면 닫히는 기본값" 과 **정반대**로 둔 것. 문 앞을 닫으면 위 구멍이 재현 자체가 안 된다 |
| `frontend-demo` 진단 화면 | 위조 헤더를 **일부러** 실어 보낸다 | 게이트웨이가 무엇을 지우고 무엇을 넣는지 눈으로 본다 |

> ⚠️ **`downstream-billing` 의 안전한 쪽도 있다.** `/scoped/subscriptions/{id}` 는 같은 자원을
> **검증된 스코프 헤더**로 판정한다(규약대로). 취약/안전을 한 앱에 나란히 둔 이유는
> **같은 요청이 판정 근거만으로 200 과 403 을 오가는 것**을 보이기 위해서다.
> 복사할 것은 `scoped` 쪽이고, 더 나은 배치는 `downstream-demo` 형태다.

## 4. alpha 배포

두 샘플 모두 **배포 대상**이다. 게이트웨이만 띄우면 토큰 릴레이·audience 검증·트레이싱 전파를
실제 클러스터에서 확인할 대상이 없기 때문이다.

| 앱 | 차트 | 이미지 | ingress |
|---|---|---|---|
| `downstream-demo` | `deploy/helm/unigate-demo-be` | `docker/server.dockerfile` (`MODULE_NAME=samples/downstream-demo`) | ❌ GW 경유만 |
| `downstream-billing` | `deploy/helm/unigate-demo-billing` | `docker/server.dockerfile` (`MODULE_NAME=samples/downstream-billing`) | ❌ GW 경유만 |
| `frontend-demo` | `deploy/helm/unigate-demo-fe` | `samples/frontend-demo/Dockerfile` (nginx) | ✅ 콘솔 host |

```bash
deploy/deploy-alpha.sh demo-be
deploy/deploy-alpha.sh demo-billing
deploy/deploy-alpha.sh demo-fe
```

> ⚠️ **billing 을 alpha 에 올리기 전에 realm 을 먼저 갱신한다** —
> `scripts/keycloak/setup-realm.sh --env alpha`. `unigate-billing-demo` client 와 audience
> mapper 가 없으면 `/api/billing/**` 이 **전부 401** 이고, 응답만 봐서는 원인이 안 보인다
> (토큰을 디코드해 `aud` 를 눈으로 봐야 안다).
>
> ⚠️ **배포 순서는 GW 보다 먼저.** GW 의 `DOWNSTREAM_BILLING_URI` 가 이 Service 를 가리킨다.
> 뒤집어도 GW 는 뜨지만 해당 라우트가 CB open 으로 503 이 된다.

### FE 는 게이트웨이 주소를 **런타임에** 받는다

로컬에서는 `.env.alpha` 가 빌드 시점에 `VITE_API_BASE_URL` 을 번들에 박지만, 배포 이미지는
주소를 모르는 채로 빌드된다. 컨테이너 기동 시 `docker/entrypoint.sh` 가 `API_BASE_URL` 을
`/tmp/config.js` 로 써서 브라우저에 전달하고, `src/api/env.ts` 가 그 값을 먼저 본다.

이렇게 하지 않으면 환경마다 이미지를 다시 구워야 하고 "같은 이미지를 승격한다" 는 원칙이 깨진다.

```bash
# 로컬에서 배포 이미지를 그대로 재현해 보려면 (k8s 와 같은 제약 조건으로)
docker build -f samples/frontend-demo/Dockerfile -t unigate-demo-fe:local samples/frontend-demo
docker run --rm -p 18080:8080 --read-only --tmpfs /tmp --user 101:101 \
  -e API_BASE_URL=https://<gw-host> unigate-demo-fe:local
curl -s localhost:18080/config.js
```

> ⚠️ **다운스트림 샘플은 부팅 시 Keycloak 을 조회한다.** `JwtDecoders.fromIssuerLocation` 이
> discovery/JWKS 를 그 자리에서 가져오므로, Keycloak 이 안 떠 있으면 **기동 자체가 실패**하고
> CrashLoopBackOff 가 된다. 게이트웨이·IAM 은 지연 JWKS 라 이 특성이 없다 — 셋을 같게 보면 오진한다.

## 5. 관련 문서

- `docs/learning/23` — 게이트웨이의 coarse 인가와 "제거 후 재주입"
- `docs/learning/24` — 잊으면 닫히는 기본값(다운스트림 테넌트 격리)
- `docs/learning/25` — outbox 보상(이메일 변경)
- `docs/learning/43` — 공유 audience 와 토큰 재생
- `docs/learning/44` — 두 검사가 각자 옳은데 합치면 구멍이다
- `CLAUDE.md` §6.1 — BFF + SPA 조합의 함정

## 6. 다운스트림 2대로만 재현되는 것 (재연 시나리오)

**실행:** `scripts/verify/two-downstream-scenarios.sh --env local`
(로컬 realm 은 테스트 계정이 있어 로그인까지 스크립트가 한다. alpha 는 `--env alpha` +
`SESSION` 쿠키 — `docs/ALPHA_CONSOLE_SCENARIOS.md` §6)

**사전조건 두 가지. 하나라도 어긋나면 결과가 거짓말을 한다.**

1. 픽스처 테넌트 두 개가 **realm 에 실재**해야 한다
   (`unigate.billing.fixture.tenant-a/b` — 기본값은 로컬 realm 기준)
2. 시나리오를 도는 사용자가 **두 테넌트 모두에 속해야** 한다

한 곳에만 속하거나 테넌트가 없으면 교차 테넌트 시나리오가 **우연히 막혀** 통과해 버린다 —
재현이 안 되는 게 아니라 **틀린 안심**을 준다.

| # | 무엇을 보는가 | 방법 | 관찰된 것 |
|---|---|---|---|
| S1 | **공유 `aud`** | `GET /api/billing/token` | `aud` 에 demo·billing·iam 이 **함께** 실린다 |
| S1b | **토큰 재생** | `/api/echo` 가 되비춘 Bearer 를 billing(:8082)에 **직접** | **200.** 두 서비스의 aud 검증은 각자 정확했는데도 |
| S2 | **claim 누출** | 같은 응답의 `tenantMemberships` | 요청 스코프는 하나인데 **소속 전부**가 실려 온다 |
| S3 | **교차 테넌트 구멍** | `acme` 컨텍스트로 `globex` 자원(`sub-b-1`) 요청 | `/subscriptions/…` **200** ❌ / `/scoped/subscriptions/…` **403** ✅ |
| S3b | 게이트는 정상인가 | `X-Requested-Tenant: nonmember` | **403** — 게이트 자체는 자기 몫을 한다 |
| S4 | **회로 격리** | billing 프로세스를 죽이고 양쪽 호출 | billing `503 billing_unavailable` · demo **200 유지** |

> S3 이 이 샘플의 존재 이유다. **같은 토큰·같은 헤더·같은 자원인데 판정 근거만 다르다.**
> S4 는 CB 인스턴스를 서비스별로 나눈 이유(bulkhead)를 실측으로 보여준다 —
> 공유했다면 청구 장애가 주문 API 까지 끊는다.
