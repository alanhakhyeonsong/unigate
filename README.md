# unigate

> 중앙 인증 게이트웨이 (토이 프로젝트)
> Spring Cloud Gateway + Kotlin(Coroutine) + Keycloak(OIDC), 헥사고날 아키텍처

Keycloak을 OIDC 공급자로 하는 사내 표준 인증 게이트웨이의 참조 구현. 게이트웨이는 **인증만** 담당하고
(BFF + Token Relay), 인가는 다운스트림이 처리한다.

## 기술 스택

| 영역 | 선택 |
|---|---|
| 언어/런타임 | Kotlin 2.1.21, JDK 21 |
| 프레임워크 | Spring Boot 3.5.4, Spring Cloud 2025.0.0 (Gateway **WebFlux**) |
| 세션/캐시 | Spring Session + Valkey(Redis Reactive), Sentinel HA |
| 영속성 | R2DBC(런타임) + Flyway(마이그레이션, JDBC) / PostgreSQL |
| 빌드 | Gradle 8.14.3 (`build-logic` convention plugins + version catalog) |

## 프로젝트 구조

```
unigate/
├── build-logic/          # convention plugins (common, gateway, ktlint)
├── gateway/              # 애플리케이션 모듈 (헥사고날)
│   └── src/main/kotlin/me/ramos/unigate/
│       ├── domain/       # 순수 도메인 (외부 의존성 0)
│       ├── application/  # 포트(inbound/outbound) + UseCase(suspend)
│       ├── adapter/      # gatewayIn / keycloakOut / valkeyOut / r2dbcOut
│       └── config/
├── docker/               # server.dockerfile, entrypoint, valkey/sentinel.conf
├── docker-compose.yml    # 로컬: postgres + valkey + valkey-sentinel
├── deploy/               # Helm 차트(alpha) + 로컬 직접 배포 스크립트
│   ├── helm/unigate/
│   └── deploy-alpha.sh
└── docs/                 # 설계 문서 + docs/learning (학습 기록)
```

> `samples/`(샘플 다운스트림 BE·FE), `docs/plans/`, `*.secret.env` 는 **커밋 대상이 아니다**.

## 로컬 개발

```bash
# 1) 로컬 인프라 기동 (postgres + valkey sentinel)
docker compose up -d

# 2) 빌드 & 테스트
./gradlew build

# 3) 실행 (local 프로파일)
./gradlew :gateway:bootRun
```

Keycloak 은 외부 제공 엔드포인트를 사용한다. `KEYCLOAK_ISSUER_URI` 등 환경변수로 주입한다.
**인스턴스는 공유하고 realm 은 환경별로 격리**한다 — local `test` / alpha `unigate`.
realm 구성 절차와 자동화 스크립트는 [`docs/KEYCLOAK_REALM_SETUP.md`](docs/KEYCLOAK_REALM_SETUP.md) 참고.

## Alpha 배포 (로컬 → 공유 k8s 직접 배포)

CI/CD 파이프라인 없이 로컬에서 직접 배포한다.

```bash
# 사전: 컨테이너 레지스트리 로그인, kubectl 컨텍스트 선택,
#       deploy/helm/unigate/values-alpha.secret.yaml 작성(커밋 금지)
./deploy/deploy-alpha.sh
```

자세한 설계·의사결정은 [`docs/PROJECT_SETUP_PLAN.md`](docs/PROJECT_SETUP_PLAN.md) 참고.
IAM 플랫폼으로의 확장은 **결정됨(Phase 8부터 적용)** — 목표 아키텍처는 [`docs/IAM_PLATFORM_DECISION.md`](docs/IAM_PLATFORM_DECISION.md) 참고.
