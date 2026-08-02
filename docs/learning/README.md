# 학습 문서 인덱스

> unigate를 만들며 **처음 접한 기술**을 정리한 개인 학습 기록.
> 작성 규칙: `.claude/skills/learning-doc/SKILL.md` · 템플릿: [`_TEMPLATE.md`](_TEMPLATE.md)

`NN` 은 **작성 순서**(= 학습 순서)이며 재정렬하지 않는다.
**인덱스에 없는 문서는 없는 것과 같다** — 문서를 추가하면 아래 표에 한 줄 추가한다.

## 작성된 문서

| # | 주제 | Phase | 상태 | 한 줄 |
|---|---|---|---|---|
| [01](01-scg-route-and-filter-chain.md) | SCG 라우트와 필터 체인 | 1 | 이해함 | 요청이 컨트롤러가 아니라 Predicate + Filter 를 통과해 프록시된다 |
| [02](02-webflux-event-loop.md) | WebFlux 이벤트 루프 | 1 | 학습중 | 요청당 스레드가 없다. 블로킹은 국소 손해가 아니라 전역 장애가 된다 |
| [03](03-spring-session-valkey-reactive.md) | Spring Session + Valkey | 1 | 학습중 | 앱 재시작에도 세션이 살아남는다. 대신 세션 저장소가 인증 가용성이 된다 |
| [04](04-oauth2-authorization-code-bff.md) | OAuth2 Authorization Code + BFF | 1 | 학습중 | 토큰은 세션에만 둔다. 세션은 Valkey에 있어도 **토큰은 기본값으로 힙에 남는다** |
| [05](05-token-relay.md) | TokenRelay | 1 | 학습중 | 세션의 토큰을 다운스트림에 붙인다. 만료 갱신도 여기서. **단, 보안 필터는 아니다** |
| [06](06-gateway-trust-boundary-header-forgery.md) | 게이트웨이 신뢰 경계와 헤더 위조 방어 | 1 | 학습중 | 인입 Authorization 은 **무조건 제거** 후 재주입. "제거"와 "덮어쓰기"는 다른 연산이다 |
| [07](07-downstream-resource-server-audience.md) | 다운스트림 Resource Server 와 aud 검증 | 1 | 학습중 | Resource Server 는 기본적으로 `aud` 를 안 본다. 안 끼우면 같은 realm 의 아무 토큰이나 통과한다 |
| [08](08-offline-integration-test-bff-gateway.md) | BFF 게이트웨이 오프라인 통합 테스트 | 1 | 학습중 | issuer-uri·DB·Redis 때문에 그냥은 부팅도 안 된다. 정적 endpoint + autoconfigure 제외로 외부의존 0 |
| [09](09-rp-initiated-logout-session-invalidation.md) | RP-Initiated Logout 과 세션 무효화 함정 | 1.5 | 학습중 | 게이트웨이 세션만 지우면 자동 재로그인된다. Keycloak end_session 까지. 단 Spring Session invalidate 는 500 |
| [10](10-jwks-local-verification.md) | JWKS 로컬 검증 (introspection 배제) | 2 | 학습중 | 공개키를 캐시하고 로컬 서명검증. kid 미스 시만 재조회. reactive 디코더 예외 문구가 servlet 과 다름 |
| [11](11-resilience-ratelimit-circuitbreaker.md) | Resilience — 토큰버킷 rate limit + Circuit Breaker | 3 | 학습중 | Redis 토큰버킷으로 429, CB+Timeout 으로 장애 fast-fail(503). 2000ms→10ms 로 회로 열림 관찰 |
| [12](12-observability-audit-r2dbc.md) | 관측성 + 감사로그 (R2DBC 첫 사용) | 4 | 학습중 | R2DBC 엔 영속성 컨텍스트가 없어 명시 INSERT + 논블로킹. WebFlux 는 인증 이벤트 미발행 → 커스텀 핸들러 |
| [13](13-distributed-tracing-reactor-context.md) | 분산 트레이싱 — traceparent 전파와 Reactor 컨텍스트 | 4 | 학습중 | SCG 가 traceparent 를 자동 주입. 단 `mono { }` 안에서 읽은 traceId 는 **항상 null** — 복원은 Reactor 연산자 경계까지다 |
| [14](14-problem-detail-xhr-auth-boundary.md) | RFC 9457 Problem Detail 과 XHR 인증 경계 | 4 | 학습중 | 미인증에 무조건 302 를 주면 SPA 에선 CORS 에러로 둔갑. `Sec-Fetch-Mode` 로 갈라 401 + loginUrl |
| [15](15-archunit-dependency-guard.md) | ArchUnit — 아키텍처 규칙을 문서에서 테스트로 | 5 | 학습중 | 규칙을 빌드가 막게 한다. 단 **통과만 하는 가드는 무의미** — 일부러 위반을 넣어 검증해야 한다 |
| [16](16-virtual-thread-vs-reactive-two-modules.md) | 한 저장소에 Reactive와 Virtual Thread를 함께 두기 | 8 | 학습중 | "VT 금지"는 원칙이 아니라 **SCG 제약의 파생**이었다. 앱을 나누면 각자 자기 모델을 온전히 쓴다 |
| [17](17-service-account-and-idempotent-admin-api.md) | service account 토큰과 멱등한 Admin API 호출 | 8 | 학습중 | 토큰이 **두 종류**다(사용자 JWT ≠ 관리 자격). outbox 재시도 대비 멱등 필수, VT 라 `ReentrantLock` |
| [18](18-outbox-worker-multi-instance.md) | 다중 인스턴스 outbox 워커 — SKIP LOCKED와 트랜잭션 경계 | 8 | 학습중 | 중복을 막는 건 스케줄러가 아니라 **DB 행 잠금**. 분산 락은 오히려 병목. 워커가 죽으면 롤백으로 자동 인계 |
| [19](19-gateway-iam-route-and-registration-rate-limit.md) | GW→IAM 프록시 라우트 — 공개/인증 분리와 가입 rate limit | 8 | 학습중 | 접두사 하나를 둘로 쪼개면 순서·CSRF·rate limit·audience 가 동시에 문제가 된다. 전부 기동으로는 안 드러난다 |
| [20](20-caller-identity-and-idor-free-design.md) | 호출자 신원으로 자원을 정하기 — 검사하지 않아도 되게 만드는 설계 | 8 | 학습중 | 대상을 토큰 `sub` 로만 정하면 IDOR 이 성립할 자리가 없다. 인라인 value class 는 DI 를 깨뜨린다 |
| [21](21-two-audit-streams-and-transaction-boundary.md) | 감사 스트림 두 개 — 합치지 않고 traceId 로 잇기 | 8 | 학습중 | 합칠지보다 **어느 트랜잭션에 속하는지**가 먼저다. GW 는 fail-open, IAM 은 fail-closed — 정반대인데 둘 다 근거가 있다 |
| [22](22-outbox-dlq-and-circuit-breaker.md) | 죽지 못하는 레코드 — outbox DLQ 와 회로 차단기 | 9 | 학습중 | 롤백은 **실패 기록까지 되돌린다**. 재시도 상한을 줄이는 결정은 차단기와 짝일 때만 안전하고, 401 을 "등록 실패" 로 오진했다 |
| [23](23-coarse-authz-tenant-gate.md) | 게이트웨이의 첫 인가 — coarse 테넌트 게이트와 "제거 후 재주입" | 9 | 학습중 | 게이트의 절반은 통과·거부가 아니라 **인입 헤더를 지우는 것**. 우회하면 위조 헤더가 그대로 200 이 된다 — 막는 건 **검사하는 엔드포인트뿐**이었다 |
| [24](24-fail-closed-by-default-tenant-guard.md) | 잊으면 닫히는 기본값 — 다운스트림 테넌트 격리를 구조로 옮기기 | 9 | 학습중 | 검사를 컨트롤러에서 **인가 규칙과 저장소로** 옮긴다. 잊었을 때의 결과가 데이터 유출이 아니라 **403 과 컴파일 에러**가 되게 |
| [25](25-email-change-outbox-compensation.md) | 되돌릴 것이 있는 outbox — 이메일 변경과 보상 | 9+ | 학습중 | 어려운 건 성공이 아니라 **실패한 뒤의 상태**다. 확정 값과 요청 값을 나눠 **보상을 필드 하나 지우기**로 줄였다 |
| [26](26-bff-spa-integration.md) | BFF 에 SPA 를 붙이며 배운 것 — 토큰이 없는 프론트엔드 | 9+ | 학습중 | FE 는 토큰을 모른다. 어려운 건 인증이 아니라 **origin·쿠키·"아직 반영 안 됨"** 이었다 |
| [27](27-helm-library-chart-and-alpha-deploy.md) | 모듈별 Helm 차트와 실전 배포 | 6 | 학습중 | 비밀은 git 말고 **`helm get values -a`** 로도 샌다. 배포 오류는 레지스트리·인증·용량으로 위장한다 |
| [28](28-k6-loadtest-silent-failures.md) | 부하테스트가 조용히 실패하는 법 | 6 | 학습중 | 429 가 0건이고 checks 가 100% 통과인데 아무것도 측정하지 않을 수 있다. **성공만 검사하면 실패가 침묵한다** |
| [29](29-k6-load-testing-basics.md) | k6 실행 모델 — 생명주기 · executor · 판정 | 6 | 학습중 | 28 의 실패들이 전부 같은 뿌리였다. **check 는 실패해도 exit 0, threshold 만 exit 99** |
| [30](30-session-token-refresh-recurrence.md) | 같은 실패가 두 번 났다 — 세션에서 토큰 꺼내기 | 9f | 학습중 | repository 는 저장된 걸 주고 manager 는 갱신해서 준다. **이름이 자연스러운 쪽이 틀린 쪽** |
| [31](31-kotlin-coroutine-suspend.md) | `suspend` 는 스레드를 멈추지 않는다 | 2~5 | 학습중 | 컴파일하면 `Object f(Continuation)` 이 된다. 바이트코드에 `COROUTINE_SUSPENDED` 분기가 그대로 있다 |
| [32](32-reactive-transaction.md) | Reactive 에서 `@Transactional` | 5 | 학습중 | "안 된다"가 아니라 **조건이 다르고, 못 맞추면 예외 없이 안 걸린다.** gateway 는 0개 — 제약이 아니라 선택 |
| [33](33-claim-propagation-delay.md) | 권한을 회수해도 즉시 막히지 않는다 | 9e~9f | 학습중 | 지연이 **두 겹**이다 — outbox(최대 10s) + 토큰 만료(5m). 관리 화면과 실제 차단이 어긋난다 |
| [34](34-jwt-iss-aud-azp.md) | `iss` · `aud` · `azp` 가 각각 보장하는 것 | 2 · 8f | 학습중 | **`aud` 는 Spring 기본 검증에 없다.** 인가 테스트는 이 검증을 지켜주지 않는다 |
| [35](35-transaction-propagation.md) | 트랜잭션 전파 — 경계가 곧 장애 대응 방식 | 8d · 9b | 학습중 | `REQUIRES_NEW` 는 스타일이 아니라 **"워커가 죽으면 무슨 일이 나는가"** 를 고르는 것 |
| [36](36-conditional-on-property.md) | `@ConditionalOnProperty` 와 안전 기본값 | 5 · 8d | 학습중 | 어려운 건 문법이 아니라 **`matchIfMissing` 의 방향** — 빠뜨렸을 때 무엇이 선택되는가 |
| [37](37-vt-pinning-measurement.md) | VT pinning 실측 — 안 나온 것을 근거로 삼기 | 8 | 학습중 | pinning 0건. 단 **플래그가 보고한다는 것부터 증명**해야 0건이 근거가 된다 |
| [38](38-reactor-coroutine-boundary.md) | Reactor ↔ Coroutine 경계 — `mono { }` 와 `await*` | 1~9 | 학습중 | 방향마다 도구가 다르다. **구독 안 한 `mono { }` 는 예외조차 사라진다** |
| [39](39-optimistic-lock-and-flush-timing.md) | 낙관적 락 — 막는 것은 `@Version` 이 아니라 flush 시점 | 8+ | 학습중 | 커밋 때 터지는 예외는 유스케이스 **밖**이라 아무도 못 잡는다. 워커가 미분류로 두면 정상 지시가 DEAD 가 된다 |
| [40](40-fail-closed-cost-reproduced.md) | fail-closed 의 대가를 문장에서 관찰로 | 8g | 학습중 | 정상 경로에서 fail-open 과 **결과가 같아서**, 고장내지 않으면 정책이 뒤집혀도 모른다. 워커까지 멈춘다 |
| [41](41-what-breaks-first.md) | 무엇이 먼저 무너지는가 — 포화의 위치를 찾는 법 | 6+ | 학습중 | 게이트웨이를 8개로 늘려도 상한을 정한 건 replica 1개짜리 IAM 이었다. 포화는 장애가 아니라 **3~18ms 짜리 거절**로 나타났고, 백엔드 로그는 깨끗했다 |

상태: `학습중` → `이해함` → (필요 시) `재방문 필요`

---

## 학습 예정 주제

`CLAUDE.md` §1.1 의 "처음 접하는 기술"에서 파생된 후보다. **순서는 고정이 아니고**,
실제 작업에서 마주친 시점에 번호를 받는다. 다루고 싶은 범위는 직접 지정한다.

### Phase 1 — 핵심 인증 게이트웨이

- [x] **Spring Cloud Gateway 필터 체인** → [01](01-scg-route-and-filter-chain.md)
- [x] **WebFlux 이벤트 루프** → [02](02-webflux-event-loop.md)
- [x] **Kotlin Coroutine `suspend`** — 스레드가 아니라 연속(continuation)을 중단·재개한다는 것 → [31](31-kotlin-coroutine-suspend.md)
- [x] **Reactor ↔ Coroutine 경계** — `mono { }`, `await*` 를 언제 어디에 쓰는가 → [38](38-reactor-coroutine-boundary.md)
      **컨텍스트 전파 측면은 [13](13-distributed-tracing-reactor-context.md) §5** 이 이미 다뤘고
      (`mono { }` 안에서는 ThreadLocal 이 복원되지 않는다), 38 이 나머지 절반 — **방향별 판단 기준**을 채웠다.
- [x] **구독하지 않은 `mono { }` 는 실행조차 되지 않는다** — 예외까지 사라져 **아무 증상이 없다.**
      이 스택에서 가장 비싼 실수 → [38](38-reactor-coroutine-boundary.md) §4 [2][4] · §5
- [x] **OAuth2 Authorization Code + BFF** → [04](04-oauth2-authorization-code-bff.md)
- [x] **TokenRelay** — 세션의 토큰을 다운스트림으로, 만료 시 refresh → [05](05-token-relay.md)
- [x] **Spring Session + Valkey(Reactive)** → [03](03-spring-session-valkey-reactive.md)
- [x] **게이트웨이 신뢰 경계 · 헤더 위조 방어** → [06](06-gateway-trust-boundary-header-forgery.md)

### Phase 2 — 토큰 검증

- [x] **JWKS 서명 검증** — introspection 대비 장단점, 키 회전(`kid` 미스) 대응 → [10](10-jwks-local-verification.md)
- [x] **JWT 구조** — `iss` / `aud` / `azp` 가 각각 무엇을 보장하는가 → [34](34-jwt-iss-aud-azp.md)

### Phase 3~4 — Resilience · 관측성 · 영속성

- [x] **R2DBC vs JPA** — 지연로딩·더티체킹·영속성 컨텍스트가 없다는 것의 실제 영향 → [12](12-observability-audit-r2dbc.md)
- [x] **Reactive 트랜잭션** — `@Transactional` 이 왜 그대로 동작하지 않는가 → [32](32-reactive-transaction.md)
- [x] **Resilience4j (reactive)** — Circuit Breaker · Bulkhead · Timeout 의 상호작용 → [11](11-resilience-ratelimit-circuitbreaker.md)
- [x] **분산 트레이싱 · Reactor 컨텍스트 전파** → [13](13-distributed-tracing-reactor-context.md)
- [x] **RFC 9457 Problem Detail · XHR 인증 경계** → [14](14-problem-detail-xhr-auth-boundary.md)

### Phase 5 — 의존성 가드 · 교체가능성

- [x] **ArchUnit 으로 아키텍처 테스트** → [15](15-archunit-dependency-guard.md)
- [x] **포트 교체가능성** — 구현이 하나뿐인 인터페이스는 추상화가 검증되지 않은 상태다 → [15](15-archunit-dependency-guard.md) §4
- [x] **`@ConditionalOnProperty` 로 빈 선택** — 조건부 자동설정의 우선순위·디버깅 방법 → [36](36-conditional-on-property.md)

### Phase 8 — IAM 서비스

- [x] **Virtual Thread vs Reactive** — 같은 문제의 경쟁 해법. **`iam` 모듈에서 실제로 VT 를 쓴다** → [16](16-virtual-thread-vs-reactive-two-modules.md)
- [x] **service account · 멱등 Admin API** → [17](17-service-account-and-idempotent-admin-api.md)
- [x] **outbox 패턴 · 다중 인스턴스 워커** → [18](18-outbox-worker-multi-instance.md)
- [x] **JPA 엔티티 ↔ 도메인 모델 매핑** — 분리 비용을 실제로 치러봤다 → [18](18-outbox-worker-multi-instance.md) (`JpaUserProfileAdapter`)
- [x] **GW→IAM 프록시 라우트 · 공개/인증 분리 · 가입 rate limit** → [19](19-gateway-iam-route-and-registration-rate-limit.md)
- [x] **Resource Server 로서의 IAM** — 같은 요청이 경계를 넘으며 세션 쿠키 → Bearer 로 인증이 교체된다 → [19](19-gateway-iam-route-and-registration-rate-limit.md) §3
- [x] **fine 인가 (자원 소유권)** — 검사를 잘 하는 것보다 검사가 필요 없게 만드는 편이 안전하다 → [20](20-caller-identity-and-idor-free-design.md)
- [x] **Kotlin 인라인 value class 와 Spring DI** — 도메인 VO 에는 맞고 **주입 대상에는 못 쓴다** → [20](20-caller-identity-and-idor-free-design.md) §5 함정 1
- [x] **감사 스트림의 트랜잭션 경계** — fail-open(GW) vs fail-closed(IAM) → [21](21-two-audit-streams-and-transaction-boundary.md)
      - [x] **그 대가를 실제로 재현했다** — `audit_log` 에 INSERT 거부 트리거를 걸어 가입이 정말 멈추는지 확인.
            프로필·outbox 지시·감사 **셋 다** 롤백된다. 예상 밖이었던 것은 **워커도 멈춘다**는 것과,
            워커 경로에서는 `UnexpectedRollbackException` 만 나와 **원인이 예외에 남지 않는다**는 것 → [40](40-fail-closed-cost-reproduced.md)
- [x] **프로필 동시 수정 (낙관적 락)** — 도메인의 "진행 중이면 거절" 검사는 읽은 시점 값이라 경합에 뚫린다.
      `@Version` 만으로는 부족했고 **flush 시점**(`saveAndFlush`)까지 통제해야 워커가 예외를 분류할 수 있다 → [39](39-optimistic-lock-and-flush-timing.md)
- [x] **outbox 를 쓰지 않을 때를 아는 것** — 단일 DB 쓰기에 얹으면 패턴의 cargo cult → [21](21-two-audit-streams-and-transaction-boundary.md) §2
- [x] **VT pinning** — 측정했다. HikariCP 를 커넥션 2개로 조여 400 동시성을 걸어도 **0건**이고, 그 0건이 근거가 되도록 `synchronized` 반례를 먼저 돌려 플래그가 보고한다는 것을 증명했다 → [37](37-vt-pinning-measurement.md)
      - [x] 남은 조각도 닫았다 — `ServiceAccountTokenProviderConcurrencyTest` 가 **그 락을 실제로 지난다.**
            VT 32개가 동시에 토큰을 요구해도 발급은 1회다. 락을 빼면 **32회**가 되는 것까지 확인했으므로
            "통과만 하는 가드"가 아니다. Keycloak 대신 JDK `HttpServer` 를 써 부수효과가 없다
- [x] **Spring 트랜잭션 전파** — `REQUIRES_NEW` 로 건별 커밋을 만들었는데, 전파 옵션별 동작을 정리한 적은 없다 → [35](35-transaction-propagation.md)

### Phase 9 — 정책 · 멀티테넌시

- [x] **outbox DLQ · 회로 차단기** → [22](22-outbox-dlq-and-circuit-breaker.md)
- [x] **coarse 인가 (게이트웨이)** — 소속인지까지만 본다. 도메인 조회를 하지 않는 것이 경계다 → [23](23-coarse-authz-tenant-gate.md)
- [x] **신뢰 경계 헤더의 "제거 후 재주입"** — Phase 1 의 `Authorization` 원칙을 테넌트에 적용 → [23](23-coarse-authz-tenant-gate.md) §3.3
- [x] **fine 인가를 다운스트림에 두는 것의 실제 비용** — 우회 직격으로 확인했다. 검사하지 않는 엔드포인트는 그대로 뚫린다 → [23](23-coarse-authz-tenant-gate.md) §4.7~4.8
- [x] **default-deny 로 반복을 없애기** — opt-in 은 잊으면 열리고 opt-out 은 잊으면 닫힌다 → [24](24-fail-closed-by-default-tenant-guard.md)
- [x] **인라인 value class 를 주입 자리에 쓰면 안 되는 이유(재발)** — 파라미터 타입이 `String` 으로 펴진다 → [24](24-fail-closed-by-default-tenant-guard.md) §5 · [20](20-caller-identity-and-idor-free-design.md) §5 함정 1
- [x] **보상 트랜잭션** — outbox 로 두 시스템을 쓸 때 영구 실패에서 로컬을 되돌리는 법 → [25](25-email-change-outbox-compensation.md)
- [x] **claim 기반 인가의 반영 지연** — 지연이 **두 겹**(outbox + 토큰 만료)이라는 것까지 정리했다. 즉시 차단 수단은 여전히 없다 → [33](33-claim-propagation-delay.md)
- [x] **세션 토큰 갱신 경로의 재발** — repository 로 토큰을 직접 꺼내면 만료를 갱신하지 못한다. [04](04-oauth2-authorization-code-bff.md) §6 과 [23](23-coarse-authz-tenant-gate.md) §4.6 이 **같은 실패의 두 번째 발생**이다 → [30](30-session-token-refresh-recurrence.md)

### Phase 6 — 실전 Alpha 배포 · 부하테스트

- [x] **library chart 로 템플릿을 1벌로 유지하기** — 앱마다 복제하면 복제본이 서서히 갈라진다 → [27](27-helm-library-chart-and-alpha-deploy.md) §2.1
- [x] **비밀이 새는 두 번째 경로** — git 을 막아도 `helm get values -a` 로 평문이 나온다. 차트가 Secret 을 만들지 않게 했다 → [27](27-helm-library-chart-and-alpha-deploy.md) §3.2
- [x] **배포 오류가 다른 것으로 위장한다** — arm64/amd64 는 push 문제로, 사설IP 누락은 자격증명 문제로, 예약 포화는 용량 부족으로 보인다 → [27](27-helm-library-chart-and-alpha-deploy.md) §5.1~5.3
- [x] **`connection attempt failed` ≠ `authentication failed`** — 전자는 TCP, 후자는 인증. 구분하면 조사 범위가 절반으로 준다 → [27](27-helm-library-chart-and-alpha-deploy.md) §5.2
- [x] **성공만 검사하면 실패가 침묵한다** — checks 100% 통과인데 아무것도 측정하지 않은 회차 → [28](28-k6-loadtest-silent-failures.md) §5.1
- [x] **k6 쿠키 jar 의 수명은 iteration** — VU 가 아니다. BFF 부하테스트에서 가장 틀리기 쉬운 곳 → [28](28-k6-loadtest-silent-failures.md) §3.2
- [x] **check 와 threshold 는 다른 물건이다** — check 는 100% 실패해도 **exit 0**. 판정을 만드는 건 threshold 뿐 → [29](29-k6-load-testing-basics.md) §3.3 · §4.2
- [x] **executor 는 "무엇을 고정할지" 를 고르는 것** — VU 기반은 용량, 도착률 기반은 정책 경계 → [29](29-k6-load-testing-basics.md) §3.4
- [x] **VU 가 모자라면 부하가 조용히 줄어든다** — `dropped_iterations` 로만 보이고 종료코드는 0 → [29](29-k6-load-testing-basics.md) §4.3
- [x] **request 는 스케줄링과 HPA 가 공유하는데 요구 방향이 반대다** — 낮추면 스케줄은 되고 HPA 는 과민해진다 → [28](28-k6-loadtest-silent-failures.md) §5.3
- [x] **최대 처리량과 병목 위치** — 이번 수치는 HPA 상한에 막힌 값이다. 노드 여유 확보 후 재측정 필요 → [28](28-k6-loadtest-silent-failures.md) §6
      2026-08-02 재측정 완료 → [41](41-what-breaks-first.md). 세 조건(request 정상화·상한 상향·노드 분산)을
      모두 풀자 **311 req/s 를 실패 0% 로** 처리했고, 576 req/s 에서 무너진 곳은 게이트웨이가 아니라
      **IAM(replica 1)** 이었다. 다만 **게이트웨이 자체의 상한은 여전히 모른다** — IAM 이 먼저 막혀서다
      **2026-07-29 재확인 — 조건이 아직 안 갖춰졌다.** 워커 노드 3대의 **requests 점유**가
      cpu 82·97·97%, memory 85·85·93% 다(실사용은 cpu 7~13% 로 한산한데 **예약이 꽉 찼다**).
      HPA 상한을 올려도 파드가 `Pending` 에 걸린다. 상한 4는 여전히 4다.
      → 막고 있는 것은 **부하가 아니라 예약**이다. 이 구분이 [27](27-helm-library-chart-and-alpha-deploy.md) §5.3 의 "예약 포화가 용량 부족으로 보인다" 와 같다.
- [~] **429 를 받은 클라이언트의 재시도 정책** — **서버 조각을 채웠다**(`RetryAfterFilter`).
      거절한 limiter 가 남긴 `X-RateLimit-*` 만으로 대기 초를 계산해 `Retry-After` 를 붙인다
      (`ceil((requestedTokens - remaining) / replenishRate)`, 최소 1초). 설정을 다시 읽지 않으므로
      limiter 파라미터가 바뀌어도 어긋나지 않는다. 실측: 가입 라우트 4번째 요청이 `429 Retry-After: 5`,
      즉시 재시도는 429, **5초 뒤에는 통과**.
      **자동 재시도는 여전히 하지 않는다** — FE 의 `shouldRetry` 가 4xx 를 막는 판단은 그대로 옳다
      ("429 재시도는 토큰버킷을 더 소진시킨다").
      - [ ] 남은 것: **FE 가 그 헤더를 읽지 않는다.** `parseProblem` 은 본문만 보고 헤더를 버린다 →
            사용자에게 "N초 후 다시 시도" 를 보여줄 수 없다. 서버가 값을 줘도 **아무도 안 보면 없는 것과 같다**
- [ ] **management port 분리** — `/actuator/prometheus` 가 인증 뒤에 있어 스크랩이 401. probe 포트까지 함께 옮겨야 한다.
      **`iam` 에서 401 을 실제로 재현했다**(deny-by-default 가 의도대로 막은 것이지 설정 실수가 아니다) → [37](37-vt-pinning-measurement.md) §4.4
      2026-07-29 확인 — `management.server.port` 는 **두 모듈 모두 미설정**이고,
      alpha 차트도 `podAnnotations: {}` 로 `prometheus.io/scrape` 를 **일부러 안 켠 상태**다
      (`deploy/helm/unigate-gateway/values-alpha.yaml` 주석: "애노테이션이 있으니 수집되고 있다는
      착각이 관측성 공백보다 나쁘다"). 즉 **관측성 공백을 알고 비워둔 것**이지 놓친 게 아니다.

### 샘플 앱 구성 시

- [x] **BFF + SPA 함정** — XHR 리다이렉트 · 세션 쿠키 · CORS credentials → [26](26-bff-spa-integration.md)
- [x] **cross-origin 배치의 대가** — CORS·`loginUrl` 절대경로화. 로컬 same-origin 에서는 **절대 드러나지 않는다** → [26](26-bff-spa-integration.md) §5
- [x] **TanStack Query 캐시와 테넌트 격리** — 서버가 격리해도 캐시 키가 무너뜨리면 요청조차 안 나간다 → [26](26-bff-spa-integration.md) §5
- [ ] **재로그인 강제** — claim 갱신을 서버가 알릴 수단이 없다 → [26](26-bff-spa-integration.md) §6
      **[33](33-claim-propagation-delay.md) 과 같은 뿌리다** — 거긴 "회수해도 즉시 안 막힌다"(지연 두 겹),
      여긴 "부여해도 즉시 안 보인다". 방향만 반대고 원인이 같아 **해결책도 하나**여야 한다.
      지금은 양쪽 다 화면 문구로 안내할 뿐이다.
