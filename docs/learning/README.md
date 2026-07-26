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

상태: `학습중` → `이해함` → (필요 시) `재방문 필요`

---

## 학습 예정 주제

`CLAUDE.md` §1.1 의 "처음 접하는 기술"에서 파생된 후보다. **순서는 고정이 아니고**,
실제 작업에서 마주친 시점에 번호를 받는다. 다루고 싶은 범위는 직접 지정한다.

### Phase 1 — 핵심 인증 게이트웨이

- [x] **Spring Cloud Gateway 필터 체인** → [01](01-scg-route-and-filter-chain.md)
- [x] **WebFlux 이벤트 루프** → [02](02-webflux-event-loop.md)
- [ ] **Kotlin Coroutine `suspend`** — 스레드가 아니라 연속(continuation)을 중단·재개한다는 것
- [~] **Reactor ↔ Coroutine 경계** — `mono { }`, `awaitBody()` 를 언제 어디에 쓰는가.
      **컨텍스트 전파 측면은 [13](13-distributed-tracing-reactor-context.md) §5 에서 실패를 겪으며 다뤘다**
      (`mono { }` 안에서는 ThreadLocal 이 복원되지 않는다). 그 외 사용 지침은 아직 미정리.
- [x] **OAuth2 Authorization Code + BFF** → [04](04-oauth2-authorization-code-bff.md)
- [x] **TokenRelay** — 세션의 토큰을 다운스트림으로, 만료 시 refresh → [05](05-token-relay.md)
- [x] **Spring Session + Valkey(Reactive)** → [03](03-spring-session-valkey-reactive.md)
- [x] **게이트웨이 신뢰 경계 · 헤더 위조 방어** → [06](06-gateway-trust-boundary-header-forgery.md)

### Phase 2 — 토큰 검증

- [x] **JWKS 서명 검증** — introspection 대비 장단점, 키 회전(`kid` 미스) 대응 → [10](10-jwks-local-verification.md)
- [ ] **JWT 구조** — `iss` / `aud` / `azp` 가 각각 무엇을 보장하는가

### Phase 3~4 — Resilience · 관측성 · 영속성

- [x] **R2DBC vs JPA** — 지연로딩·더티체킹·영속성 컨텍스트가 없다는 것의 실제 영향 → [12](12-observability-audit-r2dbc.md)
- [ ] **Reactive 트랜잭션** — `@Transactional` 이 왜 그대로 동작하지 않는가
- [x] **Resilience4j (reactive)** — Circuit Breaker · Bulkhead · Timeout 의 상호작용 → [11](11-resilience-ratelimit-circuitbreaker.md)
- [x] **분산 트레이싱 · Reactor 컨텍스트 전파** → [13](13-distributed-tracing-reactor-context.md)
- [x] **RFC 9457 Problem Detail · XHR 인증 경계** → [14](14-problem-detail-xhr-auth-boundary.md)

### Phase 5 — 의존성 가드 · 교체가능성

- [x] **ArchUnit 으로 아키텍처 테스트** → [15](15-archunit-dependency-guard.md)
- [x] **포트 교체가능성** — 구현이 하나뿐인 인터페이스는 추상화가 검증되지 않은 상태다 → [15](15-archunit-dependency-guard.md) §4
- [ ] **`@ConditionalOnProperty` 로 빈 선택** — 조건부 자동설정의 우선순위·디버깅 방법

### Phase 8 — IAM 서비스

- [x] **Virtual Thread vs Reactive** — 같은 문제의 경쟁 해법. **`iam` 모듈에서 실제로 VT 를 쓴다** → [16](16-virtual-thread-vs-reactive-two-modules.md)
- [x] **service account · 멱등 Admin API** → [17](17-service-account-and-idempotent-admin-api.md)
- [x] **outbox 패턴 · 다중 인스턴스 워커** → [18](18-outbox-worker-multi-instance.md)
- [x] **JPA 엔티티 ↔ 도메인 모델 매핑** — 분리 비용을 실제로 치러봤다 → [18](18-outbox-worker-multi-instance.md) (`JpaUserProfileAdapter`)
- [ ] **VT pinning** — `ReentrantLock` 으로 예방했으나 **실측 여전히 미완**. HikariCP 를 태우게 됐으니 `-Djdk.tracePinnedThreads=full` 로 확인 가능한 조건은 갖춰졌다
- [ ] **Spring 트랜잭션 전파** — `REQUIRES_NEW` 로 건별 커밋을 만들었는데, 전파 옵션별 동작을 정리한 적은 없다

### 참고 (직접 쓰지는 않지만 이해가 필요한 것)

### 샘플 앱 구성 시

- [ ] **BFF + SPA 함정** — XHR 리다이렉트, 세션 쿠키 SameSite, CORS credentials (`CLAUDE.md` §6.1)
