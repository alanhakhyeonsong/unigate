# samples — 검증용 로컬 앱

> ⚠️ **레퍼런스 구현이 아니다. 일부러 취약하게 만든 코드가 들어 있다.**
> 여기 있는 것을 제품 코드에 복사하지 말 것. 무엇이 왜 취약한지는 §3 에 적어 두었다.

게이트웨이·IAM 의 동작을 **눈으로 확인**하기 위한 앱이다. unigate 의 산출물이 아니며,
`settings.gradle.kts` 에 include 하지 않으므로 `./gradlew build` 는 이 디렉토리의 영향을 받지 않는다.

| 앱 | 스택 | 포트 | 역할 |
|---|---|---|---|
| `downstream-demo` | Kotlin · Spring MVC · Resource Server | 8081 | 제품 다운스트림 흉내. 받은 헤더를 되비추고 테넌트 격리를 강제한다 |
| `frontend-demo` | React 18 · TS · TanStack Query · Vite | 5173 | BFF 를 쓰는 SPA. 로그인·CSRF·테넌트·비동기 반영을 화면으로 드러낸다 |

## 1. 실행

```bash
# 사전: docker compose up -d  (postgres · valkey)
source ./keycloak.secret.env          # Keycloak 좌표 — 기본값이 없다(§2)

./gradlew :gateway:bootRun            # 8080
./gradlew :iam:bootRun                # 8090
(cd samples/downstream-demo && ./gradlew bootRun)   # 8081
(cd samples/frontend-demo && npm install && npm run dev)  # 5173
```

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
| `frontend-demo` 진단 화면 | 위조 헤더를 **일부러** 실어 보낸다 | 게이트웨이가 무엇을 지우고 무엇을 넣는지 눈으로 본다 |

## 4. 관련 문서

- `docs/learning/23` — 게이트웨이의 coarse 인가와 "제거 후 재주입"
- `docs/learning/24` — 잊으면 닫히는 기본값(다운스트림 테넌트 격리)
- `docs/learning/25` — outbox 보상(이메일 변경)
- `CLAUDE.md` §6.1 — BFF + SPA 조합의 함정
