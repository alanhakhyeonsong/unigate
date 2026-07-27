# unigate — 프로젝트 지침

> unigate 는 **MSA 공통 IAM 플랫폼**이다. **GW**(Spring Cloud Gateway)는 인증(BFF + Token Relay) +
> **coarse 인가**(route-level role · 테넌트 멤버십 게이트) + 테넌트 전파를, **IAM 서비스**는 회원·프로필·역할·
> 테넌트 도메인과 Keycloak Admin 봉인을 담당한다. **fine 인가(자원 소유권·상태)는 다운스트림**이 처리한다.
> 다운스트림은 인증/관리 흐름에서 Keycloak 에 직접 접근하지 않는다(JWKS 서명검증만 허용).
>
> 📌 **Phase 8부터 적용된 문구다**(`docs/IAM_PLATFORM_DECISION.md` §15 의 2단계 갱신 완료).
> Phase 1~7 산출물은 "게이트웨이는 인증만" 전제로 만들어졌고 **그대로 유효하다.**
> GW 의 coarse 인가는 **Phase 9f 에서 실제로 들어왔다** — `TenantGateFilter`(테넌트 멤버십 게이트 +
> `X-Tenant-Id` strip 후 재주입). route-level role 은 **넣지 않았다**(관리 평면의 역할 검사는 IAM 소관).
> 게이트는 "빨리 거절" 이지 **최종 방어선이 아니다** — 다운스트림은 자기 인가를 별도로 가져야 한다.
>
> 전역 지침(`~/.claude/CLAUDE.md`)을 상속한다. 이 문서는 **unigate 고유 규칙**만 담는다.

## 문서 지도

| 문서 | 역할 | 커밋 |
|---|---|---|
| `README.md` | 스택·구조·실행법 | ✅ |
| `docs/PROJECT_SETUP_PLAN.md` | 설계 결정과 근거(SSOT) | ✅ |
| `docs/IAM_PLATFORM_DECISION.md` | IAM 플랫폼 확장 결정(Phase 8 착수 전 게이트) | ✅ |
| `docs/KEYCLOAK_REALM_SETUP.md` | Keycloak realm 구성·검증·런북 | ✅ |
| `docs/learning/` | **개인 학습 문서** (§2) | ✅ |
| `docs/plans/` | 작업용 계획·체크리스트 | ❌ `.gitignore` |
| `samples/` | 샘플 다운스트림 BE + FE (§6) | ❌ `.gitignore` |

---

## 1. 학습자 컨텍스트 (가장 중요)

이 프로젝트는 **학습이 1차 목적**이다. 동작하는 코드보다 **왜 그렇게 되는지 이해한 상태**가 산출물이다.

### 1.1 처음 접하는 기술

아래는 사용자가 **이번에 처음 쓰는** 기술이다. 익숙하다고 전제하지 말 것.

| 기술 | 사용자가 이미 아는 대응물 | 설명 시 반드시 대조할 것 |
|---|---|---|
| Spring Cloud Gateway | Spring MVC `@RestController` | 요청이 컨트롤러가 아니라 **필터 체인**을 흐른다 |
| WebFlux / Reactor | Servlet + 스레드풀 | 요청당 스레드 없음. **이벤트 루프를 막으면 전체가 멈춘다** |
| Kotlin Coroutine | 동기 블로킹 코드 | `suspend`는 스레드가 아니라 **연속(continuation)** 을 중단·재개 |
| R2DBC | JPA / Hibernate | 지연로딩·더티체킹·영속성 컨텍스트 **전부 없다** |
| Virtual Thread | 플랫폼 스레드 | **unigate에선 쓰지 않는다** — 이유는 §1.3 |

### 1.2 설명 방식 (모든 신규 개념에 적용)

새 개념이나 낯선 API를 도입할 때는 코드보다 먼저 이 3가지를 쓴다.

1. **대조** — 익숙한 Servlet/JPA/블로킹 방식이면 어떻게 썼을지, 여기선 왜 안 되는지
2. **실패 모드** — 잘못 쓰면 어떤 증상으로 터지는지 (컴파일 에러가 아니라 **런타임·부하 시** 터지는 게 대부분이라 중요)
3. **판단 기준** — 선택지가 여럿이면 언제 무엇을 고르는지

"이건 이렇게 쓰면 됩니다"로 끝내지 않는다. 근거 없는 단정보다 **모르면 모른다고 하고 공식 문서를 확인**한다.

### 1.3 Virtual Thread를 쓰지 않는 이유 (자주 헷갈리는 지점)

Virtual Thread(JDK 21)와 Reactive는 **같은 문제(적은 스레드로 높은 동시성)의 경쟁 해법**이다. 둘을 섞으면 이점이 사라진다.

- Spring Cloud Gateway는 **WebFlux 위에서만** 동작한다. Servlet 스택을 클래스패스에 넣으면 자동설정이 충돌해 기동 자체가 실패한다.
- 따라서 unigate는 Reactive를 택했고, `spring.threads.virtual.enabled`는 **켜지 않는다.**
- Virtual Thread가 정답인 경우: Servlet MVC + JPA(블로킹 JDBC) 스택에서 스레드풀 고갈을 해소할 때. 이번 프로젝트 범위가 아니다.

---

## 2. 학습 문서 규칙 (필수 산출물)

### 2.1 언제 쓰는가

아래 중 **하나라도** 해당하면 구현과 **함께** 학습 문서를 남긴다. 나중에 몰아서 쓰지 않는다.

- §1.1 표의 기술을 처음 실제로 사용한 시점
- 예상과 다르게 동작해 원인을 파악한 시점 (**디버깅 기록은 가장 가치 있는 학습 문서다**)
- 대안 중 하나를 고른 시점 (선택 기준이 남아야 한다)

### 2.2 위치와 이름

```
docs/learning/
├── README.md              # 인덱스 + 진행 체크리스트
├── 01-spring-cloud-gateway-filter-chain.md
├── 02-webflux-event-loop.md
└── ...
```

`NN-kebab-case-주제.md`. `NN`은 **작성 순서**(학습 순서)이며 재정렬하지 않는다.

### 2.3 문서 템플릿

```markdown
# NN. 주제

> 한 줄 요약 — 이 문서를 덮고 나면 무엇을 말할 수 있어야 하는가
> 관련: Phase N · 커밋 `abc1234` · 코드 `gateway/src/.../Foo.kt`

## 1. 왜 필요했나
어떤 작업을 하다가 이 주제를 만났는지. 맥락 없는 개념 정리는 나중에 안 읽힌다.

## 2. 익숙한 방식과의 대조
| | Servlet/JPA 방식 | 여기서의 방식 | 왜 다른가 |

## 3. 동작 원리
(필요 시 Mermaid — 전역 지침의 따옴표 wrap 규칙 준수)

## 4. 직접 확인한 것
실행한 명령/코드와 **실제 출력**. 추측이 아니라 관찰을 남긴다.

## 5. 함정 / 실패 모드
잘못 쓰면 어떤 증상으로 터지는가. 겪었다면 증상 → 원인 → 해결 순으로.

## 6. 남은 의문
아직 모르는 것. 비워두지 말 것 — 다음 학습의 진입점이다.
```

### 2.4 인덱스 갱신

문서를 추가하면 `docs/learning/README.md`의 표에 **한 줄 추가**한다. 인덱스에 없는 문서는 없는 것과 같다.

| # | 주제 | Phase | 상태 | 한 줄 |
|---|---|---|---|---|

상태: `학습중` / `이해함` / `재방문 필요`

### 2.5 AI가 지켜야 할 선

> **정책 변경(2026-07-24):** 빠른 진행을 위해 AI가 학습 문서 전 구간(§4·§6 포함)을 직접 작성한다.
> 사용자는 **모든 Phase 작업이 끝난 뒤 한 번에** 검토한다. 이전엔 §4·§6을 사용자가 직접 채웠으나,
> 검토 시점을 뒤로 미루는 대신 작성은 AI가 맡는다.

- **§4 "직접 확인한 것"은 반드시 AI가 실제로 실행한 명령과 실제 출력으로만 채운다.** 추측·예상 출력
  금지 — 실행하지 않았으면 쓰지 않는다. 이게 이 섹션의 유일한 불변 규칙이다(환각 방지선).
  비밀번호·토큰·세션 쿠키 원문은 마스킹한다(§8).
- §6 "남은 의문"은 검증 중 실제로 드러난 미해결 질문을 적는다. 채워둘 게 없으면 비워도 되지만,
  다음 학습의 진입점이므로 가능하면 남긴다.
- §1~3, §5는 종전대로 AI가 초안 작성한다.
- 큰 단계가 끝나면 **무엇을 했고 무엇이 남았는지** 짚는다. (사용자 이해 확인은 일괄 검토 시점으로 이동.)

---

## 3. 진행 상황 추적

사용자가 "내가 뭐 했는지" 중간중간 확인한다. 다음을 유지한다.

- `docs/plans/PHASE_ROADMAP.md` — Phase 단위 진행 (gitignore, 작업용)
- `docs/learning/README.md` — 학습 진행 (커밋 대상, 자산)

Phase 하나가 끝나면 **양쪽 모두** 갱신하고, 사용자에게 다음 3가지를 보고한다:
완료한 것 / 검증한 근거 / 다음 선택지.

---

## 4. 이 스택의 함정 (코드 작성 전 확인)

Reactive 스택에서 **컴파일은 되지만 부하 시 터지는** 것들이다.

| 함정 | 증상 | 규칙 |
|---|---|---|
| 이벤트 루프에서 블로킹 호출 | 저부하 정상, 고부하에서 전체 지연 폭발 | `reactor-netty-http-nio` 스레드에서 JDBC·`Thread.sleep`·동기 HTTP 금지 |
| `.block()` 사용 | `IllegalStateException` 또는 데드락 | 프로덕션 코드에서 금지. coroutine 경계는 `mono { }` / `awaitBody()` |
| Servlet 의존성 유입 | 기동 실패 | **`gateway` 모듈 한정** — `spring-boot-starter-web` 금지, WebFlux만. `iam` 모듈은 **반대**다(§5.1) |
| JPA 습관 | 컴파일은 되나 의도와 다름 | R2DBC엔 지연로딩·더티체킹·영속성 컨텍스트 없음. 저장은 항상 명시적 |
| 스키마 마이그레이션 | 테이블 없음 | R2DBC는 마이그레이션 기능 없음 → **Flyway(JDBC, 부팅 1회)** 로 분리 |
| `@Transactional` 오용 | 트랜잭션 미적용 | reactive는 `TransactionalOperator` 또는 reactive tx manager 기반 |

> 상세 근거는 `docs/PROJECT_SETUP_PLAN.md` §2.1.
>
> ⚠️ **이 표는 전부 `gateway` 모듈(WebFlux) 이야기다.** `iam` 모듈은 Servlet MVC + JPA + Virtual Thread 라
> 위 함정이 대부분 해당하지 않고, 오히려 **블로킹이 정상**이다. 모듈을 헷갈리면 정반대 조언을 하게 된다.

---

## 5. 아키텍처 · 컨벤션

의존성 방향은 **`adapter → application → domain` 단방향만** 허용한다. 이는 **모든 모듈 공통**이다.

- `domain` — 순수 Kotlin. Spring 어노테이션·외부 의존성 0
- `application` — 포트 인터페이스로만 외부와 소통. Spring은 스테레오타입(`@Service` 등)까지만 허용한다
- `adapter` — 모듈별 목록은 §5.1

> **이 규칙은 문서가 아니라 테스트가 강제한다** (Phase 5):
> `gateway/src/test/.../architecture/HexagonalArchitectureTest.kt`. 위반하면 빌드가 깨진다.

### 5.1 모듈 — 스택이 서로 다르다 (헷갈리면 정반대 조언이 된다)

| | `gateway` | `iam` |
|---|---|---|
| 스택 | **WebFlux + SCG** (Netty) | **Servlet MVC + JPA + Virtual Thread** |
| DB | R2DBC (논블로킹) | JPA / JDBC (**블로킹이 정상**) |
| UseCase | **`suspend` 함수** | 평범한 블로킹 함수 |
| 어댑터 | `gatewayIn` · `keycloakOut` · `r2dbcOut` · `loggingOut` | `iamIn` · `schedulerIn` · `keycloakAdminOut` · `jpaOut` · `jacksonOut` |
| Keycloak 접점 | **OIDC 표준만**(discovery·JWKS·end_session·token) | **Admin API**(service account, 봉인) + **JWKS**(Resource Server, 아래) |
| 인증 역할 | OAuth2 **Client**(BFF) — 로그인시키고 세션에 토큰 보관 | **Resource Server** — relay 된 Bearer 검증만, 세션 없음(STATELESS) |
| CSRF | **활성** (쿠키로 인증하므로 공격 표면이 실재) | **비활성** (쿠키를 쓰지 않아 전제가 성립 안 함) |

**왜 스택이 다른가:** WebFlux 강제는 **SCG 제약**이다(§1.3). `iam`은 SCG가 아니므로 자유롭고, 워크로드가
VT에 더 맞는다 — Keycloak Admin client는 블로킹이고, 관리 도메인 CRUD는 JPA의 관계·트랜잭션이 낫다.
§1.3의 "VT와 Reactive를 섞지 말라"는 경고는 **한 앱 안** 이야기이므로, 앱을 나눠 쓰는 것은 위반이 아니다.

> `valkeyOut`은 세션을 Spring Session이 전부 처리해 커스텀 어댑터가 필요 없어 **제거**했다
> (빈 디렉토리는 "미완성"이라는 잘못된 신호를 준다). 필요해지면 그때 만든다.

> **`iam` 의 JWKS 사용은 D7 위반이 아니다** (Phase 8f). D7이 `iam` 에 몰아준 것은 **Admin API 봉인**이고,
> 막은 것은 게이트웨이가 Admin API 를 쓰는 것이다. 반대 방향 — Resource Server 가 **자기에게 온 토큰을
> 검증하려고** JWKS 를 읽는 것 — 은 O2 에서 다운스트림에도 허용한 표준 동작이다
> (`IAM_PLATFORM_DECISION.md` §9). 즉 "IAM 은 Keycloak 에 Admin 으로만 접근한다" 가 아니라
> **"Keycloak 을 Admin 으로 쓰는 것은 IAM 뿐이다"** 가 정확한 문장이다.

게이트웨이의 Keycloak 어댑터는 **OIDC 표준(discovery·JWKS)에만** 의존한다. **Admin API는 `iam` 소관**이며
게이트웨이에 넣지 않는다(`IAM_PLATFORM_DECISION.md` D7).

### skill 라우팅

`.claude/skills/`의 규칙을 따른다 (상세: `.claude/skills/README.md`).

- 코드: `architecture` / `domain-model` / `usecase` / `kotlin-style` / `testing` / `convention-check`
- 워크플로: **`git-flow`**(브랜치·PR·스쿼시 머지) · **`learning-doc`**(학습 문서 작성)

> ⚠️ `rest-controller` skill은 **MVC 전제**다. unigate는 SCG(WebFlux)이므로 `adapter/gatewayIn`
> (`GlobalFilter`, `RouterFunction`) 작성 시 **참고만** 하고 그대로 적용하지 않는다.

---

## 6. 샘플 애플리케이션 (커밋 대상 — 2026-07-27 방침 변경)

게이트웨이 동작을 **눈으로 검증**하기 위한 앱이다. unigate의 산출물은 아니지만 **검증 장치**이고,
학습 문서와 PR 이 이 코드를 인용하므로 저장소에 있어야 한다.

```
samples/
├── README.md                 # ⚠️ 일부러 취약하게 둔 곳 목록 — 먼저 읽을 것
├── downstream-demo/          # 샘플 다운스트림 BE (Resource Server, :8081)
└── frontend-demo/            # 샘플 FE (React + TS + TanStack Query, :5173)
```

> **왜 방침을 바꿨나:** 예전에는 `samples/` 전체를 `.gitignore` 했다("산출물이 아니다"). 그런데
> 실측이 프로젝트의 핵심 산출물이 되면서, 검증 장치가 저장소에 없으면 ① 학습 문서·PR 이 인용하는
> 코드를 아무도 볼 수 없고 ② 새로 클론한 사람은 실측을 재현할 수단이 없다. 원래 이유 중
> "빌드에 영향 없다" 는 `settings.gradle.kts` 미포함으로 그대로 유지된다.

- **빌드 산출물과 로컬 비밀만 무시한다** — `node_modules/` · `build/` · `.env` · `.env.alpha`.
- `settings.gradle.kts` 에 `include` 하지 않는다. 샘플 BE는 **독립 Gradle 빌드**로 두어
  `./gradlew build` 가 샘플에 영향받지 않게 한다.
- **실제 좌표를 담지 않는다(§8).** 샘플 설정도 환경변수로만 받고 **fallback 기본값을 두지 않는다** —
  주입을 빠뜨렸을 때 조용히 엉뚱한 곳을 보는 것보다 기동이 실패하는 편이 낫다.
- ⚠️ **샘플에는 일부러 취약한 엔드포인트가 있다**(`/legacy/orders`·`/echo` 등). 무엇이 왜 취약한지는
  `samples/README.md` §3 에 표로 있다. **레퍼런스 구현으로 복사하지 않는다.**
- 샘플 BE는 Keycloak client `unigate-downstream-demo` 를 **audience로 검증**한다
  (realm에 이미 구성됨 — `docs/KEYCLOAK_REALM_SETUP.md` §4.3~4.4).

### 6.1 BFF + SPA 조합의 함정 (FE 작업 전 필독)

**FE는 Keycloak client를 갖지 않는다.** 토큰은 게이트웨이 세션 안에만 있고 브라우저는 세션 쿠키만 받는다.
FE가 토큰을 보게 되는 순간 BFF를 쓰는 이유가 사라진다.

| 함정 | 증상 | 해결 |
|---|---|---|
| **XHR 리다이렉트** | `fetch()` 로 보호 리소스 호출 시 원인 불명의 CORS 에러 | 게이트웨이가 XHR에는 302 대신 **401 + 로그인 URL** 반환. FE가 `window.location` 으로 **top-level 이동** |
| **세션 쿠키 미전송** | 로그인은 되는데 매 요청이 401 | FE dev server와 게이트웨이의 origin이 다르면 쿠키가 안 실림 → **Vite dev proxy로 same-origin 유지**(권장) |
| **CORS credentials** | preflight 통과했는데 쿠키 없음 | 별도 origin 유지 시 `Allow-Credentials: true` + **정확한 Origin**(와일드카드 불가) + FE `credentials: 'include'` |
| **CSRF 토큰 전달** (Phase 9c) | GET 은 전부 정상인데 **인증된 POST 만 403**. 토큰을 실어 보내도 로그엔 `Did not find a CSRF token in the request` | 세 가지가 **모두** 필요하다 — ① 저장소를 쿠키로(`CookieServerCsrfTokenRepository.withHttpOnlyFalse()`) ② **XOR 핸들러 해제**(`ServerCsrfTokenRequestAttributeHandler`) ③ **구독 강제 필터**(WebFlux 의 `CsrfToken` 은 lazy `Mono` 라 구독 없이는 쿠키가 안 실린다) |

> **CSRF 세 조각은 하나라도 빠지면 조용히 실패한다.** ①이 없으면 토큰이 세션에만 있어 클라이언트가
> 읽을 수 없고, ②가 없으면 쿠키의 원본 값과 서버가 기대하는 마스킹 값이 어긋나며, ③이 없으면
> 쿠키 자체가 응답에 실리지 않는다. **셋 다 증상이 "403" 하나로 같아서** 어느 조각이 빠졌는지
> 응답만 봐서는 구분되지 않는다 — `org.springframework.security.web.server.csrf` 를 TRACE 로 켜야 갈린다.

> **XHR 리다이렉트가 왜 헷갈리는가**: 302 응답을 `fetch`가 그대로 따라가 Keycloak 로그인 페이지를 요청하고,
> 그 응답이 CORS 정책에 걸린다. 브라우저 콘솔에는 "CORS 에러"만 찍혀 **진짜 원인(미인증)이 가려진다.**
> 로그인 리다이렉트는 반드시 **브라우저 주소창 이동(top-level navigation)** 이어야 한다.

---

## 7. 명령어

```bash
docker compose up -d          # postgres + valkey + sentinel
./gradlew build               # 컴파일 + ktlint + 테스트
./gradlew :gateway:bootRun    # local 프로파일 실행

source ./keycloak.secret.env  # Keycloak 자격증명 (gitignore 대상)
```

Testcontainers 통합 테스트는 **로컬 전용**이다.

---

## 8. 보안 (커밋 전 필수)

- **실제 좌표를 커밋 대상에 넣지 않는다** — 호스트명, 계정, 네임스페이스, 레지스트리 경로, ingress host.
  문서·스크립트에는 `<keycloak-host>` 같은 placeholder만 쓰고 실제 값은 환경변수로 주입한다.
  이 저장소는 **public 리모트**가 붙어 있다.
- secret은 `.gitignore` 대상 파일로 격리: `*.secret.env`, `application-*-secret.yml`, `deploy/**/*.secret.yaml`
- 인입 `Authorization` 헤더는 **strip 후 재주입**
- 토큰 검증은 introspection이 아니라 **JWKS 로컬 서명검증**
- 토큰·비밀번호·secret은 **로그에 남기지 않는다**

```bash
# 커밋 전 점검
git diff --cached | grep -nE '<사내-도메인-키워드>|[0-9]{1,3}(\.[0-9]{1,3}){3}'
```
