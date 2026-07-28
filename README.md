# unigate

> MSA 공통 IAM 플랫폼 (토이 프로젝트)
> Spring Cloud Gateway + Kotlin + Keycloak(OIDC), 헥사고날 아키텍처

Keycloak을 신원 저장소로 하는 사내 표준 IAM 플랫폼의 참조 구현.

- **게이트웨이(GW)** — 인증(BFF + Token Relay) + **coarse 인가**(route-level role · 테넌트 멤버십 게이트) + 테넌트 전파
- **IAM 서비스** — 회원·프로필·역할·테넌트 도메인과 **Keycloak Admin API 봉인**
- **fine 인가**(자원 소유권·상태)는 **다운스트림**이 처리한다

다운스트림은 인증/관리 흐름에서 Keycloak에 직접 접근하지 않는다(JWKS 서명검증만 허용).

> GW의 coarse 인가는 Phase 9f에서 실제로 들어왔다 — `TenantGateFilter`(테넌트 멤버십 게이트 +
> `X-Tenant-Id` strip 후 재주입). **route-level role 검사는 넣지 않았다** — 관리 평면의 역할 검사는 IAM 소관이다.
> 게이트는 "빨리 거절"이지 **최종 방어선이 아니다.** 다운스트림은 자기 인가를 따로 가져야 한다
> ([`docs/learning/23`](docs/learning/23-coarse-authz-tenant-gate.md) ·
> [`24`](docs/learning/24-fail-closed-by-default-tenant-guard.md)).

## 기술 스택

**두 모듈의 스택이 서로 다르다.** WebFlux 강제는 Spring Cloud Gateway의 제약이고, IAM은 SCG가 아니므로
자유롭다. 워크로드도 다르다 — Keycloak Admin client는 블로킹이고, 관리 도메인 CRUD는 JPA의 관계·트랜잭션이 낫다.

| | `gateway` | `iam` |
|---|---|---|
| 스택 | **WebFlux + SCG** (Netty) | **Servlet MVC + JPA + Virtual Thread** (Tomcat) |
| DB | R2DBC(런타임) + Flyway(JDBC) | JPA/JDBC + Flyway |
| UseCase | `suspend` 함수 | 평범한 블로킹 함수 |
| Keycloak 접점 | OIDC 표준만(discovery·JWKS·end_session·token) | **Admin API**(service account) + JWKS(Resource Server) |
| 인증 역할 | OAuth2 **Client**(BFF) | **Resource Server** (STATELESS) |
| CSRF | **활성**(쿠키 인증) | 비활성(쿠키를 쓰지 않음) |
| 포트 | 8080 | 8090 |

공통: Kotlin 2.1.21 / JDK 21 / Spring Boot 3.5.4 / Spring Cloud 2025.0.0 / PostgreSQL / Valkey(Sentinel HA) /
Gradle 8.14.3 (`build-logic` convention plugins + version catalog)

## 프로젝트 구조

```
unigate/
├── build-logic/          # convention plugins (common, gateway, iam, ktlint)
├── gateway/              # 인증 게이트웨이 (WebFlux)
│   └── src/main/kotlin/me/ramos/unigate/
│       ├── domain/       # 순수 도메인 (외부 의존성 0)
│       ├── application/  # 포트(inbound/outbound) + UseCase(suspend)
│       ├── adapter/      # gatewayIn / keycloakOut / r2dbcOut / loggingOut
│       └── config/
├── iam/                  # IAM 서비스 (MVC + JPA + VT)
│   └── src/main/kotlin/me/ramos/unigate/iam/
│       ├── domain/       # tenant / membership / user / audit
│       ├── application/  # 포트 + UseCase (+ outbox: 기술 패턴이라 domain 아님)
│       ├── adapter/      # iamIn / schedulerIn / keycloakAdminOut / jpaOut / jacksonOut
│       └── config/
├── docker/               # server.dockerfile, entrypoint, valkey/sentinel.conf
├── docker-compose.yml    # 로컬: postgres + valkey + valkey-sentinel
├── deploy/               # 모듈별 Helm 차트(library chart 공유) + 배포 스크립트
│   ├── helm/             #   unigate-common(library) + 앱 차트 4개
│   └── env/              #   좌표·비밀 (.env.example 만 커밋)
├── loadtest/             # k6 부하 시나리오 (rate limit 경계 · 용량/HPA)
├── scripts/keycloak/     # realm 구성 자동화(setup-realm.sh)
├── samples/              # 검증용 샘플 앱 — 아래 참고 (독립 빌드, ./gradlew build 와 무관)
│   ├── downstream-demo/  #   샘플 다운스트림 BE (Resource Server, :8081)
│   └── frontend-demo/    #   샘플 FE (React + TS + TanStack Query + Vite, :5173)
└── docs/                 # 설계 문서 + docs/learning (학습 기록)
```

의존성 방향은 **`adapter → application → domain` 단방향만** 허용하며, 문서가 아니라 **ArchUnit 테스트가 강제**한다.

> `docs/plans/`, `*.secret.env`, `.env*` 는 **커밋 대상이 아니다.** `samples/` 는 2026-07-27 부터
> **커밋 대상**이다 — 아래 참고.

### samples/ — 눈으로 확인하는 검증 장치

게이트웨이·IAM 이 실제로 무엇을 지우고 무엇을 넣는지 **화면과 응답으로** 드러내기 위한 앱이다.
unigate 의 산출물은 아니지만, 학습 문서와 PR 이 이 코드를 인용하므로 저장소에 둔다.

`settings.gradle.kts` 에 `include` 하지 않는 **독립 Gradle 빌드**라 `./gradlew build` 는 영향을 받지 않는다.
무시하는 것은 빌드 산출물과 로컬 비밀뿐이다 — `node_modules/` · `build/` · `.env` · `.env.alpha`.

> ⚠️ **레퍼런스 구현이 아니다.** `/legacy/orders`·`/echo` 처럼 **일부러 취약하게 둔** 엔드포인트가 있다.
> 무엇이 왜 취약한지는 [`samples/README.md`](samples/README.md) §3 의 표에 있다. 복사해 쓰지 말 것.

## 로컬 개발

```bash
# 1) 로컬 인프라 기동 (postgres + valkey sentinel)
docker compose up -d

# 2) 빌드 & 테스트 (컴파일 + ktlint + 단위/슬라이스)
./gradlew build

# 3) 환경변수 주입 (gitignore 대상)
set -a; source ./keycloak.secret.env; set +a

# 4) 실행 (local 프로파일)
./gradlew :gateway:bootRun   # :8080 (Netty)
./gradlew :iam:bootRun       # :8090 (Tomcat, Virtual Thread)
```

### 샘플 앱까지 함께 띄우기

```bash
(cd samples/downstream-demo && ./gradlew bootRun)          # :8081 샘플 다운스트림
(cd samples/frontend-demo && npm install && npm run dev)   # :5173 샘플 FE
```

브라우저는 **5173** 으로 연다. Vite dev proxy 가 게이트웨이를 같은 origin 으로 보이게 해야
세션 쿠키와 CSRF 쿠키가 그대로 동작한다.

> Keycloak realm 에 `http://localhost:5173/login/oauth2/code/keycloak` 이 등록돼 있어야 한다
> (`scripts/keycloak/setup-realm.sh --env local` 이 등록한다). 없으면 로그인이
> `Invalid parameter: redirect_uri` 로 끊기고 **게이트웨이 로그에는 아무것도 남지 않는다** —
> 302 는 정상 발행되고 거절하는 쪽이 Keycloak 이기 때문이다.
> BFF + SPA 조합에서 밟은 함정 전체는 [`docs/learning/26`](docs/learning/26-bff-spa-integration.md).

### 통합 테스트 (로컬 전용)

실제 PostgreSQL이 필요해 `./gradlew build`에서 제외된다(`@Tag("testcontainers")`).

```bash
docker exec unigate-postgres createdb -U testuser unigate_iam_test   # 최초 1회
./gradlew :iam:integrationTest
```

> Testcontainers는 이 환경에서 뜨지 않아(Docker 29.x + Testcontainers 1.21.3) docker-compose의 로컬
> PostgreSQL에 JDBC로 직접 붙는다. 경위는 [`docs/learning/18`](docs/learning/18-outbox-worker-multi-instance.md) §5.

### 환경별 설정

프로파일 규칙이 두 모듈 공통이다 — **local은 기본값 fallback이 있고, alpha는 없다.**
alpha에서 환경변수 주입을 빠뜨리면 조용히 localhost를 보는 대신 **기동이 실패한다.**

Keycloak은 외부 제공 엔드포인트를 사용한다. **인스턴스는 공유하고 realm은 환경별로 격리**한다 —
local `test` / alpha `unigate`. realm 구성 절차와 자동화 스크립트는
[`docs/KEYCLOAK_REALM_SETUP.md`](docs/KEYCLOAK_REALM_SETUP.md) 참고.

## Alpha 배포 (로컬 → 공유 k8s 직접 배포)

CI/CD 파이프라인 없이 로컬에서 직접 배포한다. 배포 대상은 **앱 4개**다.

```bash
# 사전: 컨테이너 레지스트리 로그인, kubectl 컨텍스트 선택,
#       deploy/env/alpha.coord.env 와 앱별 alpha.<app>.secret.env 작성(전부 커밋 금지)
cp deploy/env/alpha.coord.env.example deploy/env/alpha.coord.env      # 좌표
cp deploy/env/alpha.gateway.secret.env.example deploy/env/alpha.gateway.secret.env

./deploy/deploy-alpha.sh --dry-run all    # 먼저 렌더만 확인
./deploy/deploy-alpha.sh all              # iam → demo-be → gateway → demo-fe 순
./deploy/deploy-alpha.sh gateway          # 하나만 재배포
```

### 차트 구조 — library chart 공유

```
deploy/helm/
├── unigate-common/     # type: library — Deployment·Service·Ingress·HPA·PDB 정의 (템플릿 1벌)
├── unigate-gateway/    # BFF · WebFlux         :8080  ingress ✅  HPA ✅
├── unigate-iam/        # MVC + JPA + VT        :8090  ingress ❌  HPA ✅
├── unigate-demo-be/    # 샘플 다운스트림        :8081  ingress ❌
└── unigate-demo-fe/    # 샘플 콘솔 (nginx)      :8080  ingress ✅
```

앱 차트의 `templates/` 에는 `{{ include "unigate-common.all" . }}` 한 줄만 있고, 앱별 차이는
전부 values 로 낸다. 템플릿을 앱마다 복제하면 그 복제본들이 서서히 갈라지기 때문이다.

### 비밀은 Helm values 를 거치지 않는다

차트는 Secret 을 **만들지 않는다.** 배포 스크립트가 gitignore 된 `.env` 파일로
`kubectl create secret` 을 만들고 차트는 이름으로 참조만 한다(`envFrom`).

> **왜 이렇게까지 하나:** Helm 은 릴리즈 values 를 클러스터의 `sh.helm.release.v1.*` Secret 에
> 통째로 보관한다. values 에 비밀을 넣으면 git 을 막아도 `helm get values -a` 로 평문이 그대로
> 나온다 — 네임스페이스 read 권한자 전원에게 노출된다. 두 경로를 모두 막으려면 비밀이 values 를
> 거치지 않아야 한다.

실제 좌표(네임스페이스·호스트·레지스트리)도 커밋 대상 values 에 두지 않고, 배포 시
임시 values 파일로 주입한다. 클러스터 내부 통신은 **같은 네임스페이스의 짧은 Service 이름**
(`http://unigate-iam-svc`)을 써서 네임스페이스가 값에 박히지 않게 한다.

### 부하테스트

`loadtest/` 에 k6 시나리오가 있다. **시나리오가 둘로 나뉘어 있고, 이유가 있다** —
게이트웨이의 rate limit 키가 `sub` 라 운영 설정 그대로 부하를 주면 429 만 나오고 HPA 는
반응하지 않는다. 자세한 것은 [`loadtest/README.md`](loadtest/README.md).

## 문서

| 문서 | 역할 |
|---|---|
| [`docs/PROJECT_SETUP_PLAN.md`](docs/PROJECT_SETUP_PLAN.md) | 설계 결정과 근거 (SSOT) |
| [`docs/IAM_PLATFORM_DECISION.md`](docs/IAM_PLATFORM_DECISION.md) | IAM 플랫폼 확장 결정 · 목표 아키텍처 |
| [`docs/KEYCLOAK_REALM_SETUP.md`](docs/KEYCLOAK_REALM_SETUP.md) | Keycloak realm 구성·검증·런북 |
| [`docs/learning/`](docs/learning/README.md) | 학습 기록 — 겪은 함정과 판단 근거 |
| [`samples/README.md`](samples/README.md) | 샘플 앱 실행법 · **일부러 취약하게 둔 곳 목록** |

`docs/learning/`은 이 프로젝트의 부산물이 아니라 **산출물**이다. 처음 쓰는 기술(SCG·WebFlux·R2DBC·
Virtual Thread·outbox)에서 실제로 부딪힌 것과 그때의 판단 기준이 남아 있다.
