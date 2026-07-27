# 26. BFF 에 SPA 를 붙이며 배운 것 — 토큰이 없는 프론트엔드

> FE 는 토큰을 모른다. 그래서 어려운 것은 인증이 아니라 **origin·쿠키·"아직 반영 안 됨"** 이었다.
> 관련: Phase 9 이후 · 코드 `samples/frontend-demo/` · 커밋 `3c5aca5` · `31e4a78` · `4ae899b`

## 1. 왜 필요했나

여기까지의 검증은 전부 `curl` 과 브라우저 콘솔이었다. 그건 "요청 하나가 어떻게 처리되는가" 는
보여주지만, **여러 요청이 이어지는 흐름**(로그인 → 테넌트 선택 → 쓰기 → 비동기 반영)은 못 본다.
그리고 앞으로 alpha 에 올릴 FE 가 실제로 어떤 제약을 받는지도 알 수 없었다.

샘플 FE 를 붙이자마자 **첫 로그인부터 막혔다.** 그 뒤로 나온 것들이 이 문서다.

## 2. 익숙한 방식과의 대조

| | 흔한 SPA (토큰을 FE 가 들고 있음) | 여기서의 방식 (BFF) |
|---|---|---|
| 토큰 보관 | `localStorage`/메모리 | **게이트웨이 세션.** FE 는 존재조차 모른다 |
| 요청 인증 | `Authorization: Bearer …` 를 FE 가 붙인다 | **세션 쿠키.** Bearer 는 GW→다운스트림 구간에만 있다 |
| 로그인 | FE 가 Keycloak 과 직접 왕복(PKCE) | FE 는 **주소창을 옮길 뿐**. 왕복은 GW 가 한다 |
| CSRF | 필요 없다(쿠키를 안 쓰므로) | **필요하다.** 쿠키로 인증하므로 공격 표면이 실재 |
| XSS 로 토큰 탈취 | 가능 | **불가능**(FE 에 토큰이 없다) |
| 로그아웃 | 저장소에서 토큰 삭제 | GW 세션 폐기 + Keycloak `end_session` |
| 배포 형태의 제약 | 없음(어느 origin 이든 됨) | **origin 이 설계 변수**가 된다 — §3 |

> **FE 가 Bearer 를 다루는 구조가 애매해 보이는 것은 당연하다.** 그럴 자리가 없기 때문이다.
> 애매함의 실체는 Bearer 가 아니라 **FE 와 GW 를 같은 origin 에 둘 것인가**였다.

## 3. 동작 원리 — origin 이 설계 변수다

```mermaid
flowchart LR
    subgraph local ["local (same-origin)"]
      FE1["FE :5173 (Vite)"] -->|"dev proxy"| GW1["Gateway :8080"]
    end
    subgraph alpha ["alpha (cross-origin)"]
      FE2["FE (콘솔 호스트)"] -->|"CORS + credentials"| GW2["Gateway (별도 호스트)"]
    end
    GW1 --> D1["IAM · Downstream"]
    GW2 --> D2["IAM · Downstream"]
```

| | local | alpha |
|---|---|---|
| 배치 | dev proxy 로 **same-origin 을 만든다** | 콘솔·게이트웨이가 **다른 호스트** |
| CORS | 불필요 | **필수**(정확한 origin + credentials) |
| API base | 빈 문자열(상대경로) | 게이트웨이 origin |
| `loginUrl` 처리 | 상대경로 그대로 | **절대경로로 바꿔야 한다**(§5) |

그래서 **로컬에서 잘 되는 것이 alpha 에서 그대로 되지 않는다.** 그 차이를 `vite.config.ts` 와
`api/env.ts` 두 곳에만 두고, 나머지 코드는 `VITE_API_BASE_URL` 만 본다.

### 3.1 잊을 수 있는 자리를 없앤다

401 분기·CSRF 헤더·테넌트 헤더를 `api/client.ts` **한 곳**에 모았다. 페이지마다 흩어지면
"어디서 빠뜨렸지" 를 추적할 수 없다 — 서버에서 `anyRequest` 로 default-deny 를 만든 것과 같은
판단이다([24](24-fail-closed-by-default-tenant-guard.md)).

같은 이유로 다운스트림 호출 함수는 **테넌트를 인자로 강제**한다. 빠뜨리면 컴파일이 안 된다.

## 4. 직접 확인한 것

### 4.1 첫 로그인이 막혔다 — `Invalid parameter: redirect_uri`

FE 를 띄우고 로그인을 누르자 Keycloak 이 거절했다.

```
We are sorry...
Invalid parameter: redirect_uri
(redirect_uri=http://localhost:5173/login/oauth2/code/keycloak)
```

원인: dev proxy 가 Host 를 그대로 넘기므로 **게이트웨이가 만드는 redirect_uri 도 5173** 이 된다.
realm 에는 8080 만 등록돼 있었다.

**게이트웨이 로그에는 아무것도 남지 않았다.** 302 는 정상 발행됐고 거절은 Keycloak 이 했기 때문이다.
`setup-realm.sh` 에 5173 을 추가하고 재실행하니 로그인 화면이 정상 렌더됐고, carol 로 로그인해
**5173 으로 복귀**하는 것까지 확인했다.

### 4.2 게이트웨이가 무엇을 지우고 무엇을 넣는가 (FE 세션으로)

로그인한 뒤 브라우저에서:

```json
[{"case":"acme 주문 목록","status":200,"body":[{"id":"acme-1","tenantId":"acme"}, …]},
 {"case":"globex(비소속) 목록","status":403,"body":{"detail":"요청한 테넌트에 소속되어 있지 않습니다"}},
 {"case":"주문 생성 (CSRF)","status":201,"body":{"id":"acme-103","tenantId":"acme"}},
 {"case":"위조 헤더","status":200,"다운스트림이본_XTenantId":"acme","authorization":"JWT(게이트 재주입)"}]
```

마지막 줄이 요점이다 — `X-Tenant-Id: globex` 와 위조 `Authorization` 을 함께 실었는데,
다운스트림이 본 것은 **검증된 `acme`** 와 게이트가 재주입한 JWT 였다.

세션 화면에서는 IAM 쪽도 확인했다.

```
X-Tenant-Id (검증값)   : (없음 — 제거됨)
X-Requested-Tenant(주장): (없음)
```

### 4.3 cross-origin 을 로컬에서 재현했다 (alpha 배포 없이)

FE 를 `--mode alpha` 로 다른 포트(5174)에 띄워 cross-origin 배치를 흉내 냈다.
게이트웨이의 CORS 허용 목록이 비어 있을 때:

```json
{"label":"cross-origin → GW /iam/debug/whoami","ok":false,"error":"TypeError: Failed to fetch"}
```

`UNIGATE_CORS_ALLOWED_ORIGINS=http://localhost:5174` 로 재기동하니 게이트웨이 로그가 갈렸고,

```
CORS 활성 — 허용 origin 1개          (주입 시)
CORS 비활성 — 허용 origin 이 없다     (미주입 시)
```

같은 호출이 전부 통과했다.

```json
[{"label":"GET (단순 요청)","status":200,"body":"carol"},
 {"label":"GET + 테넌트 헤더 (preflight 유발)","status":200,"body":[…5건…]},
 {"label":"POST + CSRF 헤더 (preflight + 쿠키)","status":201,"body":{"id":"acme-104"}}]
```

관찰 두 가지:
- **preflight 와 자격증명이 둘 다 동작했다.** alpha 의 cross-origin 배치가 성립한다는 근거다.
- **쿠키는 포트를 구분하지 않는다.** 5174 에서 보낸 요청에 8080 에서 만든 세션 쿠키가 실렸다
  (응답의 `carol`). 쿠키는 origin 이 아니라 **호스트** 기준이다 — CORS 와는 다른 규칙이다.

### 4.4 claim 과 도메인 목록이 어긋나는 순간

관리자 화면으로 두 번째 테넌트를 만들고 워커가 프로비저닝을 끝낸 직후:

```
   id   |  display_name   | status          14 | ADD_GROUP_MEMBER      | COMPLETED | 1
 acme   | 에이컴 주식회사 | ACTIVE          13 | CREATE_KEYCLOAK_GROUP | COMPLETED | 1
 globex | 글로벡스        | ACTIVE
```

화면:

```
토큰 claim : acme
도메인 목록: acme(ACTIVE), globex(ACTIVE)
globex 행  → "claim 에 있는가: 없음"
```

같은 상태에서 실제 호출:

```json
[{"case":"acme (claim 에 있음)","status":200,"detail":"6건"},
 {"case":"globex (도메인엔 멤버, claim 엔 없음)","status":403,"detail":"요청한 테넌트에 소속되어 있지 않습니다"}]
```

**"목록에 있어도 claim 에 없으면 403"** 이 화면과 응답으로 동시에 증명됐다. 재로그인해야 claim 이
갱신된다 — 캐시 무효화로 해결되는 문제가 아니다.

### 4.5 value class 함정이 세 번 나왔다

같은 뿌리([20](20-caller-identity-and-idor-free-design.md) §5 함정 1)가 **서로 다른 세 자리**에서 되살아났다.

```
① 컨트롤러 파라미터 (샘플 FE 작업 중)
   NullPointerException: Parameter specified as non-null is null:
     method TenantContext.constructor-impl, parameter tenantId
   → value class 는 파라미터 타입이 String 으로 펴져 argument resolver 가 매칭되지 않는다

② mockk answers 블록
   ClassCastException: class java.lang.String cannot be cast to class TenantId
   → 런타임 인자는 이미 String 이다

③ mockk any() + 형식 검증 value class
   initializationError — 전체 스위트에서만 실패(단독 실행은 통과)
   → mockk 가 시그니처용 더미를 임의 문자열로 만드는데, TenantId 의 slug 검증에 걸린다
```

③ 이 특히 고약했다 — **단독 실행은 통과하고 전체 스위트에서만** 깨져서 원인이 코드가 아니라
실행 순서에 있는 것처럼 보였다.

## 5. 함정 / 실패 모드

| 함정 | 증상 | 원인 | 해결 |
|---|---|---|---|
| **dev proxy 주소가 realm 에 없다** (§4.1) | 로그인이 `Invalid parameter: redirect_uri`. **게이트웨이 로그는 조용하다** | 프록시가 Host 를 넘겨 redirect_uri 가 dev 주소가 된다 | realm 에 dev origin 등록. 대안(changeOrigin)은 로그인 후 FE 를 벗어난다 |
| **`loginUrl` 을 상대경로 그대로 사용** | cross-origin 배포에서 로그인 이동이 **FE 호스트 404** | 그 경로는 게이트웨이에만 있다 | `toAbsolute()` 로 API base 를 붙인다. same-origin 에서는 무해해 **로컬에서 절대 안 드러난다** |
| CORS 를 필터 빈으로만 등록 | preflight(OPTIONS, 자격증명 없음)가 **401** 로 끊기고 콘솔엔 CORS 에러만 | 인증 필터가 먼저 돈다 | 보안 체인에 `.cors {}` 로 연결 |
| `fetch` 가 302 를 따라가게 둔다 | 콘솔에 **CORS 에러**만 뜨고 원인(미인증)이 가려진다 | XHR 은 리다이렉트를 자동으로 따라간다 | 401 + `loginUrl` 로 갈라 **top-level 이동**([14](14-problem-detail-xhr-auth-boundary.md)) |
| queryKey 에 테넌트를 빼먹는다 | 다른 테넌트 목록이 그대로 보이고 **네트워크 요청조차 안 나간다** | 테넌트는 헤더로만 전달돼 URL 이 같다 | 키에 테넌트 포함. 서버가 격리해도 **클라이언트 캐시가 무너뜨린다** |
| 4xx 를 재시도한다 | 429 에서 토큰버킷을 더 소진시켜 상황이 악화된다 | 기본 retry 정책 | 4xx 는 재시도 금지 |
| 응답이 전부 problem+json 이라고 가정 | 본문 없는 429 에서 `res.json()` 이 던져 **상태코드가 가려진다** | 다운스트림은 수제 맵, 429 는 본문 없음 | 텍스트로 읽고 파싱 실패를 견디는 파서 |
| **성공과 보상을 응답으로 구분하려 한다** | 이메일 변경이 됐는지 취소됐는지 화면이 모른다 | 둘 다 `pendingEmail: null` 로 끝난다([25](25-email-change-outbox-compensation.md)) | FE 가 요청 값을 기억해 비교 — **새로고침하면 그것도 못 한다**(서버 쪽 미해결) |
| 관리 메뉴를 역할로 숨긴다 | "서버가 막았는가" 를 확인할 수 없다 | 편의를 위한 숨김이 검증을 가린다 | 숨기지 않는다. 403 이 그대로 보이는 것이 이 앱의 목적 |

## 6. 남은 의문

- [ ] **재로그인을 강제할 수단이 없다.** 초대 수락 후 claim 이 갱신되려면 재로그인해야 하는데,
      지금은 화면 문구로 안내할 뿐이다. GW 가 "토큰을 새로 받아라" 를 알릴 방법(강제 refresh,
      세션에 신호 저장)이 있는지 모르겠다.
- [ ] **`Failed to fetch` 는 아무것도 설명하지 않는다.** CORS·네트워크·인증서 실패가 전부 같은
      문구다. 개발 중에는 GW 로그와 대조해 알아냈는데, 운영에서 사용자가 겪으면 무엇을 물어봐야
      하는지 모른다.
- [ ] **DTO 타입이 수동 사본이다.** 백엔드 필드가 바뀌어도 컴파일러가 못 잡는다. `springdoc` 이
      없어 OpenAPI 생성 경로가 없는데, 산출물 코드에 의존성을 더할 만큼의 이득인지 판단 못 했다.
- [ ] **테넌트 전환 시 캐시 정책.** 지금은 키에 테넌트를 넣어 격리했지만, 로그아웃·계정 전환에서
      `queryClient.clear()` 만으로 충분한지 확인하지 않았다.
