# 05. TokenRelay — 세션의 토큰을 다운스트림으로

> BFF 가 세션에 감춰둔 access token 을, 다운스트림 호출 순간에만 꺼내 `Authorization` 헤더로 붙인다.
> 그리고 만료된 토큰을 **누가 언제** 갱신하는지가 여기서 정해진다.
> 관련: Phase 1 Step 6 · 코드 `gateway/src/main/kotlin/me/ramos/unigate/config/TokenRelayConfig.kt`,
> `gateway/src/main/kotlin/me/ramos/unigate/config/GatewayRouteConfig.kt`

## 1. 왜 필요했나

Step 5 까지 로그인은 되고 토큰도 세션(Valkey)에 있었지만, 다운스트림 echo 응답의
`authorization.present` 는 계속 `false` 였다. **토큰을 세션에 담는 것과 다운스트림에 전달하는 것은
별개의 작업**이기 때문이다(04 문서 §4 확인 6).

Step 6 은 그 마지막 한 칸을 채운다. 다운스트림이 처음으로 "누가 요청했는지"를 알게 된다.

동시에 04 §6 에서 미뤄둔 질문의 답이 여기서 나온다 — **access token 은 5분, 세션은 30분인데
그 사이 만료된 토큰을 누가 갱신하는가.** 답은 "토큰을 실제로 쓰는 쪽", 즉 TokenRelay 다.

## 2. 익숙한 방식과의 대조

| | 직접 구현한다면 (MVC 감각) | TokenRelay (SCG) |
|---|---|---|
| 토큰 획득 | 컨트롤러에서 `@RegisteredOAuth2AuthorizedClient` 주입 | 라우트 필터가 `AuthorizedClientManager` 로 조회 |
| 헤더 주입 | `RestTemplate` 인터셉터에서 `setBearerAuth` | 필터가 `exchange.mutate().request(...)` |
| 만료 갱신 | 직접 refresh 호출 코드 작성 | provider 에 `refreshToken()` 넣으면 **자동** |
| 적용 위치 | 서비스 코드 곳곳 | **라우트 정의 한 줄** (`tokenRelay()`) |

핵심 차이는 **토큰을 다루는 코드가 애플리케이션에 없다**는 점이다. 라우트 필터가 요청 흐름
안에서 토큰을 얹고, 만료되면 갱신까지 한다. SCG 의 "모든 것이 필터 체인의 어느 지점인가"
(01 문서) 가 토큰 릴레이에도 그대로 적용된다.

## 3. 동작 원리

### 3.1 요청 한 건의 흐름

```mermaid
sequenceDiagram
    participant B as Browser
    participant GW as unigate (tokenRelay 필터)
    participant M as AuthorizedClientManager
    participant V as Valkey (세션)
    participant KC as Keycloak
    participant DS as downstream-demo

    B->>GW: GET /api/echo (세션 쿠키)
    GW->>M: authorize(principal=alice)
    M->>V: 세션에서 AUTHORIZED_CLIENTS 로드
    alt access token 유효
        V-->>M: access token 반환
    else 만료됨 + refresh token 있음
        M->>KC: refresh_token 으로 갱신 요청
        KC-->>M: 새 access token
        M->>V: 갱신된 토큰을 세션에 다시 저장
    end
    M-->>GW: access token
    GW->>DS: GET /echo + Authorization: Bearer <token>
    DS-->>GW: 응답
    GW-->>B: 응답
```

### 3.2 필터가 실제로 하는 일 (SCG 소스)

`TokenRelayGatewayFilterFactory` 의 apply 는 이게 전부다.

```java
// spring-cloud-gateway-server / TokenRelayGatewayFilterFactory.java
return (exchange, chain) -> exchange.getPrincipal()
    .filter(principal -> principal instanceof Authentication)
    .cast(Authentication.class)
    .flatMap(principal -> authorizationRequest(defaultClientRegistrationId, principal))
    .flatMap(this::authorizedClient)                 // ← AuthorizedClientManager.authorize()
    .map(OAuth2AuthorizedClient::getAccessToken)
    .map(token -> withBearerAuth(exchange, token))   // ← setBearerAuth 로 헤더 주입
    .defaultIfEmpty(exchange)                         // ← 토큰 없으면 아무것도 안 함
    .flatMap(chain::filter);
```

주목할 두 줄:

- `authorizedClient` → `clientManager.authorize(request)`. **이 한 번의 호출 안에서 조회와 갱신이
  모두 일어난다.** 유효하면 그대로, 만료면 refresh. 필터는 유효/만료를 신경 쓰지 않는다.
- `defaultIfEmpty(exchange)`. principal 이 없거나(미인증) 토큰이 없으면 **헤더를 건드리지 않고**
  요청을 그대로 흘려보낸다. 이 동작이 §5 의 보안 함정으로 이어진다.

### 3.3 manager 는 왜 직접 만들어야 했나

TokenRelay 필터는 `ReactiveOAuth2AuthorizedClientManager` 빈을 **필수로** 요구한다.
없으면 이렇게 죽는다.

```
IllegalStateException: No ReactiveOAuth2AuthorizedClientManager bean was found.
```

그런데 이 버전의 Spring Boot 자동 구성은 `ReactiveOAuth2AuthorizedClientService`(InMemory)까지만
만들고 **manager 는 만들지 않는다**(소스에서 확인: `ReactiveOAuth2ClientConfigurations` 에
manager 정의 없음). 그래서 `TokenRelayConfig` 에서 직접 등록한다.

manager 종류 선택이 중요하다.

| | `DefaultReactiveOAuth2AuthorizedClientManager` | `AuthorizedClientServiceReactive...Manager` |
|---|---|---|
| 컨텍스트 | **웹 요청**(세션·exchange) | 백그라운드(요청 없음) |
| 토큰 출처 | `ServerOAuth2AuthorizedClientRepository`(=세션) | `...ClientService`(인메모리) |
| 용도 | **BFF · TokenRelay** | 스케줄러·M2M |

unigate 는 로그인한 사용자의 요청 흐름 안에서 토큰을 쓰므로 **Default(요청 기반)** 를 골랐고,
Step 5 에서 등록한 세션 저장소(`WebSessionServerOAuth2AuthorizedClientRepository`)를 주입해
**Valkey 에서** 토큰을 읽게 했다.

> exchange 는 어디서 오나: 필터는 `OAuth2AuthorizeRequest` 에 exchange 를 넣지 않는다.
> manager 가 **Reactor Context** 에서 `ServerWebExchange` 를 꺼낸다 — WebFlux 가 채워둔 값이다.
> Servlet 의 ThreadLocal 대신 Context 로 요청 데이터를 나르는 02 문서의 그 구조가 여기서도 쓰인다.

## 4. 직접 확인한 것

사전 준비: 로그인해서 유효한 세션 쿠키 확보(04 문서 §4).

확인 1 — 다운스트림이 Bearer 토큰을 받는가
```bash
curl -s -b <세션쿠키> localhost:8080/api/echo | jq '.authorization'
```
관찰 포인트: `present` 가 `true` 인가? payload 의 `aud` 에 `unigate-downstream-demo` 가 있는가?
`sub` 가 alice 인가? `azp`(요청한 클라이언트)는 무엇인가?

```json
// 다운스트림이 받은 authorization (JWT payload 디코드, iss 는 마스킹)
{
  "present": true,
  "jwt": true,
  "payload": {
    "iss": "https://<keycloak-host>/realms/test",
    "aud": ["unigate-downstream-demo", "account"],
    "sub": "115f2213-2d36-4bf0-a187-b124f7817b7d",
    "azp": "unigate-client",
    "typ": "Bearer",
    "scope": "openid email profile",
    "realm_access": { "roles": ["unigate-user", "default-roles-test", "offline_access", "uma_authorization"] },
    "preferred_username": "alice",
    "name": "alice tester",
    "email": "alice@example.local",
    "exp": 1784866978, "iat": 1784866678
  }
}
```

관찰:
- **`present: true`.** 04 §4 확인 6 에서 `false` 였던 것이 채워졌다. TokenRelay 가 붙인 것이다.
- **`aud` 에 `unigate-downstream-demo` 가 있다.** realm 의 audience mapper 가 동작한 결과다
  (`KEYCLOAK_REALM_SETUP.md` §4.4). 이게 없으면 다운스트림이 Step 8 에서 401 을 낸다.
  `account` 도 함께 있는데, 이는 Keycloak 이 기본으로 넣는 값이다.
- `sub` 가 04 §4 의 alice UUID 와 정확히 일치한다. `azp`(authorized party)는 `unigate-client` —
  **토큰을 요청한 게 게이트웨이**임을 뜻한다. `aud`(누가 쓰라고)와 `azp`(누가 받았나)가 다르다.
- `realm_access.roles` 에 `unigate-user` 가 실려 있다. 게이트웨이는 이 역할을 파싱하지 않지만
  (인가는 다운스트림 몫), 토큰 안에는 담겨 다운스트림이 쓸 수 있다.
- **게이트웨이 자신의 세션 쿠키(`SESSION=...`)도 다운스트림에 그대로 넘어갔다.** TokenRelay 는
  Authorization 만 추가할 뿐 다른 헤더를 정리하지 않는다 — Step 7 의 과제다.

확인 2 — 위조 Authorization 헤더를 실어 보내면?
```bash
curl -s -b <세션쿠키> -H 'Authorization: Bearer FORGED' localhost:8080/api/echo \
  | jq '.headers.authorization'
```
관찰 포인트: `FORGED` 가 남아있는가, 정상 토큰으로 교체됐는가?
**로그인된 세션일 때**와 **세션 없이 위조 헤더만 보낼 때**가 어떻게 다른가? (§5 와 연결)

```
# 로그인된 세션 + 위조 헤더
다운스트림이 받은 authorization 앞부분: Bearer eyJhbGciOiJSUzI1NiIs...
FORGED 가 남아있는가: False
정상 JWT 로 교체됐는가: True
```

관찰:
- 로그인된 세션에서는 `FORGED` 가 **사라지고** 정상 JWT 로 교체됐다. tokenRelay 의
  `setBearerAuth` 가 헤더를 덮어썼다.
- **하지만 이걸 "위조 방어"로 읽으면 안 된다.** §5 에서 다루듯, 토큰이 없는 요청(미인증·로그아웃)
  에서는 tokenRelay 가 `defaultIfEmpty(exchange)` 로 아무것도 하지 않아 위조 헤더가 그대로 통과한다.
  덮어쓰기는 방어가 아니라 부수 효과다.
- 세션 없이 위조 헤더만 보내는 케이스는 지금 구조에선 재현이 까다롭다 — 인증이 라우팅보다
  먼저라(01 문서) 미인증이면 302 로 끊겨 다운스트림에 닿지 않기 때문이다. 진짜 위험은
  **인증은 됐지만 토큰이 없는 상태**(refresh 실패 등)이고, 그래서 Step 7 의 무조건 strip 이 필요하다.

확인 3 ★ — 토큰이 만료된 뒤 다시 호출하면 갱신되는가
```bash
# 1) 지금 토큰의 iat/exp 기록
curl -s -b <세션쿠키> localhost:8080/api/echo | jq -r '.authorization.payload | fromjson | "iat=\(.iat) exp=\(.exp)"'
# 2) exp 를 넘길 때까지 대기(access token 수명 = 5분)
# 3) 다시 호출해 iat 이 커졌는지 확인
```
관찰 포인트: `iat` 이 증가했는가? 증가했다면 **세션은 그대로인데 access token 만 새로 발급**된 것이다.
`refreshToken()` provider 를 뺀 뒤 같은 실험을 하면 무엇이 달라지는가?

```
BEFORE  iat = 1784866678  exp = 1784866978   (수명 300초)
# exp 를 87초 넘긴 뒤 재호출
AFTER   iat = 1784867065  exp = 1784867365
토큰이 갱신됐는가(iat 증가): True
```

관찰:
- **`iat` 이 증가했다**(1784866678 → 1784867065). 만료된 토큰을 그대로 보낸 게 아니라
  **refresh token 으로 새 access token 을 받아** 붙였다.
- 같은 세션 쿠키로 호출했는데 토큰만 새것이다. 즉 **세션(로그인)은 그대로, access token 만 교체**됐다.
  04 §6 에서 "만료 69초 뒤에도 갱신 안 됨"이었던 상태가, `refreshToken()` provider 하나로 해결됐다.
- 갱신 계기는 요청 그 자체다. TokenRelay 가 `authorize()` 를 부르는 순간 manager 가 만료를 감지하고
  갱신한다 — 별도 스케줄러나 백그라운드 작업이 아니다. **토큰을 쓰려는 요청이 곧 갱신 트리거**다.
- provider 에서 `.refreshToken()` 을 빼면 이 실험에서 `iat` 이 그대로이고 다운스트림은
  만료 토큰을 받아 401 을 낸다(§5).

확인 4 — refresh 후 세션에 저장된 토큰도 갱신됐는가
```bash
# 확인 3 직후, whoami 로 세션의 accessToken.issuedAt 을 본다
curl -s -b <세션쿠키> localhost:8080/debug/whoami | jq '.accessToken'
```
관찰 포인트: `issuedAt` 이 확인 3 의 새 `iat` 과 일치하는가?
(갱신 결과가 **세션에 다시 저장**됐는지 — 다음 요청도 갱신된 토큰을 쓰는지)

```
확인 3 의 새 iat 1784867065  → 2026-07-24T04:24:25Z
세션 accessToken.issuedAt     = 2026-07-24T04:24:23.983554Z
세션 accessToken.expiresAt    = 2026-07-24T04:29:23.983554Z
```

관찰:
- 세션에 저장된 `issuedAt`(04:24:23Z)이 확인 3 의 새 `iat`(04:24:25Z)과 사실상 일치한다.
  (2초 차이는 `iat` 이 초 단위로 잘리고 `issuedAt` 은 밀리초 클라이언트 시각이라 생기는 오차다.)
- 즉 refresh 결과가 **세션(Valkey)에 다시 저장**됐다. TokenRelay 가 토큰만 갈아 끼우고 버린 게
  아니라, manager 가 갱신본을 저장소에 write-back 한 것이다.
- 이게 중요한 이유: 저장 안 하면 **매 요청이 refresh** 를 유발해 Keycloak 을 두드린다.
  세션에 갱신본을 넣어두므로 다음 요청은 갱신된 토큰이 만료되기 전까지 refresh 없이 그대로 쓴다.
- `whoami`(`AuthProbeConfig`)는 tokenRelay 를 거치지 않고 저장소에서 직접 읽는다. 그런데도 새
  `issuedAt` 이 보인다는 건, refresh 시점에 이미 세션이 갱신돼 있었다는 뜻이다.

## 5. 함정 / 실패 모드

| 함정 | 증상 | 원인 | 해결 |
|---|---|---|---|
| **manager 빈 누락** | 기동은 되나 첫 프록시에서 500 | Boot 자동 구성이 manager 를 만들지 않는다. TokenRelay 는 필수로 요구 | `DefaultReactiveOAuth2AuthorizedClientManager` 직접 등록 |
| **`refreshToken()` 누락** | 5분 뒤부터 다운스트림 401, 재로그인 유도 없음 | provider 가 `authorizationCode()` 뿐이라 만료를 갱신하지 못함 | provider 빌더에 `.refreshToken()` 추가 |
| **잘못된 manager 선택** | 토큰을 못 찾거나 세션과 불일치 | `AuthorizedClientService...Manager`(인메모리)를 쓰면 세션 저장소를 안 본다 | 요청 기반 `Default...Manager` + 세션 저장소 |
| **tokenRelay 를 strip 으로 오해** | 미인증·만료 사용자의 위조 헤더가 통과 | `defaultIfEmpty(exchange)` — 토큰이 없으면 헤더를 **건드리지 않는다** | 명시적 헤더 strip 을 따로 둔다(Step 7) |
| **인입 Authorization 신뢰** | 토큰 있는 사용자는 안전하나 없는 사용자는 위험 | tokenRelay 는 "교체"지 "제거 후 재주입"이 아니다 | 신뢰 경계에서 인입 헤더를 먼저 제거 |

### tokenRelay 는 보안 필터가 아니다 ★

가장 헷갈리는 지점이다. tokenRelay 가 위조 `Authorization` 헤더를 덮어쓰는 것을 보면
"헤더 위조 방어가 됐다"고 착각하기 쉽다. **아니다.**

`defaultIfEmpty(exchange)` 때문에 tokenRelay 는 **토큰이 있을 때만** 헤더를 교체한다.
토큰이 없는 요청 — 미인증, 로그아웃, refresh 실패 — 에서는 아무것도 하지 않으므로
**인입 위조 헤더가 그대로 다운스트림에 도달한다.**

| 요청 | tokenRelay 동작 | 위조 `Authorization: Bearer FORGED` |
|---|---|---|
| 로그인됨 (토큰 있음) | 우리 토큰으로 교체 | 제거됨 (덮임) |
| 미인증 (토큰 없음) | **아무것도 안 함** | **그대로 통과** |

즉 "덮어쓰기"는 방어가 아니라 **부수 효과**다. 진짜 방어는 신뢰 경계에서 인입 헤더를
**무조건 제거**하는 것이고, 그게 Step 7 이 따로 있는 이유다. tokenRelay 에 보안을 맡기면
"로그인한 사용자는 안전한데 로그인 안 한 공격자만 위조 헤더를 통과시키는" 거꾸로 된 방어가 된다.

### access token 수명과 세션 수명의 간극

access token 5분, 세션 30분. 이 간극이 `refreshToken()` provider 가 필요한 이유다.
provider 를 빼면 다음 시나리오가 된다.

1. 로그인 (토큰 유효, 세션 유효)
2. 5분 경과 (토큰 만료, 세션 유효)
3. 다운스트림 호출 → 만료 토큰이 그대로 전달 → 다운스트림 401
4. 사용자는 여전히 "로그인 상태" → 재로그인 유도 없음

저부하 개발 중에는 요청이 5분 안에 끝나 드러나지 않다가, **오래 열어둔 탭**에서만 재현되는
찾기 어려운 형태다. 04 §6 에서 "지금은 아무도 갱신하지 않는다"고 남긴 상태가 바로 이것이고,
Step 6 의 `refreshToken()` 이 그 구멍을 메운다.

## 6. 남은 의문

> **refresh 관련 의문의 주인은 이 문서다.** [04](04-oauth2-authorization-code-bff.md) §6 에도
> 같은 질문 두 개가 중복돼 있었는데, 갱신 계기를 만드는 것이 TokenRelay 이므로 여기로 모았다.

### 이번에 답이 나온 것

- [x] **다운스트림이 여럿이 되면 라우트마다 clientRegistration 을 나눠야 하는가, 한 토큰에 여러 `aud` 를 담는가?**
      → **한 토큰에 여러 `aud`.** clientRegistration 은 하나로 두고 Keycloak 의 audience mapper 가
      `aud` 배열에 대상들을 넣는다. 게이트웨이의 `expected-audience` 도 값 하나
      (`unigate-downstream-demo`)로 남아 있는데, IAM 라우트의 토큰까지 통과하는 이유가
      **`aud` 배열에 둘 다 들어 있어서**라는 것을 [34](34-jwt-iss-aud-azp.md) §4.6 에서 확인했다.

      다만 그게 **설계한 결과인지 정리되지 않은 설정인지는 아직 구분이 안 된다** —
      그 부분은 [34](34-jwt-iss-aud-azp.md) §6 이 이어받는다. 이 문서에서는 닫는다.

### 아직 모르는 것

- [ ] **동시 요청 여러 개가 동시에 만료된 토큰을 만나면 refresh 가 몇 번 일어나는가?**
      Keycloak 의 refresh token rotation(`KEYCLOAK_REALM_SETUP.md` §4.1 에서 OFF 로 둔 것)과
      겹치면 race 가 되는가? rotation 이 OFF 라 지금은 **터지지 않고 넘어가는 중**일 뿐,
      켜는 순간 드러날 수 있다.
      → 확인 방법: 만료 직후 동일 세션 쿠키로 동시 요청 N 건을 던지고 Keycloak 의
      `/token` 호출 횟수를 센다. 다중 인스턴스면 세션 저장소 경합까지 봐야 한다.
- [ ] **refresh token 자체가 만료되면(SSO Idle/Max) 그 순간 사용자에게는 무엇이 보이는가?**
      세션(30분)은 살아 있는데 갱신만 실패하는 구간이 존재하는가.
      → 게이트웨이가 그때 302 를 줄지 401 을 줄지는 [14](14-problem-detail-xhr-auth-boundary.md) 의
      `Sec-Fetch-Mode` 분기를 따르는데, **그 분기를 refresh 실패 경로에서 확인한 적은 없다.**
