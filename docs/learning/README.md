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

상태: `학습중` → `이해함` → (필요 시) `재방문 필요`

---

## 학습 예정 주제

`CLAUDE.md` §1.1 의 "처음 접하는 기술"에서 파생된 후보다. **순서는 고정이 아니고**,
실제 작업에서 마주친 시점에 번호를 받는다. 다루고 싶은 범위는 직접 지정한다.

### Phase 1 — 핵심 인증 게이트웨이

- [x] **Spring Cloud Gateway 필터 체인** → [01](01-scg-route-and-filter-chain.md)
- [x] **WebFlux 이벤트 루프** → [02](02-webflux-event-loop.md)
- [ ] **Kotlin Coroutine `suspend`** — 스레드가 아니라 연속(continuation)을 중단·재개한다는 것
- [ ] **Reactor ↔ Coroutine 경계** — `mono { }`, `awaitBody()` 를 언제 어디에 쓰는가
- [x] **OAuth2 Authorization Code + BFF** → [04](04-oauth2-authorization-code-bff.md)
- [x] **TokenRelay** — 세션의 토큰을 다운스트림으로, 만료 시 refresh → [05](05-token-relay.md)
- [x] **Spring Session + Valkey(Reactive)** → [03](03-spring-session-valkey-reactive.md)

### Phase 2 — 토큰 검증

- [ ] **JWKS 서명 검증** — introspection 대비 장단점, 키 회전(`kid` 미스) 대응
- [ ] **JWT 구조** — `iss` / `aud` / `azp` 가 각각 무엇을 보장하는가

### Phase 3~4 — Resilience · 관측성 · 영속성

- [ ] **R2DBC vs JPA** — 지연로딩·더티체킹·영속성 컨텍스트가 없다는 것의 실제 영향
- [ ] **Reactive 트랜잭션** — `@Transactional` 이 왜 그대로 동작하지 않는가
- [ ] **Resilience4j (reactive)** — Circuit Breaker · Bulkhead · Timeout 의 상호작용

### 참고 (직접 쓰지는 않지만 이해가 필요한 것)

- [ ] **Virtual Thread vs Reactive** — 같은 문제의 경쟁 해법. unigate 가 VT 를 쓰지 않는 이유 (`CLAUDE.md` §1.3)

### 샘플 앱 구성 시

- [ ] **BFF + SPA 함정** — XHR 리다이렉트, 세션 쿠키 SameSite, CORS credentials (`CLAUDE.md` §6.1)
