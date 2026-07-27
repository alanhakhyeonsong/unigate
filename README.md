# unigate

> MSA 공통 IAM 플랫폼 (토이 프로젝트)
> Spring Cloud Gateway + Kotlin + Keycloak(OIDC), 헥사고날 아키텍처

Keycloak을 신원 저장소로 하는 사내 표준 IAM 플랫폼의 참조 구현.

- **게이트웨이(GW)** — 인증(BFF + Token Relay) + **coarse 인가**(route-level role · 테넌트 멤버십 게이트) + 테넌트 전파
- **IAM 서비스** — 회원·프로필·역할·테넌트 도메인과 **Keycloak Admin API 봉인**
- **fine 인가**(자원 소유권·상태)는 **다운스트림**이 처리한다

다운스트림은 인증/관리 흐름에서 Keycloak에 직접 접근하지 않는다(JWKS 서명검증만 허용).

> GW의 coarse 인가는 Phase 9f에서 추가된다 — 현재 GW의 인가는 "인증됐는가"까지다.

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
├── deploy/               # Helm 차트(alpha) + 로컬 직접 배포 스크립트
├── scripts/keycloak/     # realm 구성 자동화(setup-realm.sh)
└── docs/                 # 설계 문서 + docs/learning (학습 기록)
```

의존성 방향은 **`adapter → application → domain` 단방향만** 허용하며, 문서가 아니라 **ArchUnit 테스트가 강제**한다.

> `samples/`(샘플 다운스트림 BE·FE), `docs/plans/`, `*.secret.env` 는 **커밋 대상이 아니다**.

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

CI/CD 파이프라인 없이 로컬에서 직접 배포한다.

```bash
# 사전: 컨테이너 레지스트리 로그인, kubectl 컨텍스트 선택,
#       deploy/helm/unigate/values-alpha.secret.yaml 작성(커밋 금지)
./deploy/deploy-alpha.sh
```

> ⚠️ 배포 구성은 **Phase 6에서 재조정 예정**이다. 현재 차트는 게이트웨이 단일 앱 전제로 작성돼 있고,
> Phase 8 이후 앱이 둘(gateway·iam), DB도 둘(`unigate`·`unigate_iam`)로 늘었다.

## 문서

| 문서 | 역할 |
|---|---|
| [`docs/PROJECT_SETUP_PLAN.md`](docs/PROJECT_SETUP_PLAN.md) | 설계 결정과 근거 (SSOT) |
| [`docs/IAM_PLATFORM_DECISION.md`](docs/IAM_PLATFORM_DECISION.md) | IAM 플랫폼 확장 결정 · 목표 아키텍처 |
| [`docs/KEYCLOAK_REALM_SETUP.md`](docs/KEYCLOAK_REALM_SETUP.md) | Keycloak realm 구성·검증·런북 |
| [`docs/learning/`](docs/learning/README.md) | 학습 기록 — 겪은 함정과 판단 근거 |

`docs/learning/`은 이 프로젝트의 부산물이 아니라 **산출물**이다. 처음 쓰는 기술(SCG·WebFlux·R2DBC·
Virtual Thread·outbox)에서 실제로 부딪힌 것과 그때의 판단 기준이 남아 있다.
