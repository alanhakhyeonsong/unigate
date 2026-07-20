---
name: testing
description: 테스트 계층(L1~L4) 표준과 프레임워크 선택(단위=Kotest BehaviorSpec, 슬라이스/통합=JUnit5), 모킹 전략(OutPort만 모킹, MockK), Testcontainers 로컬 전용 실행, HTTP 테스트 파일 규칙. 테스트 코드 작성/수정 시 참조한다.
---

# 테스트 계층 · 프레임워크 · 모킹 전략 규칙

## 규칙 0: 계층별 프레임워크 선택 (먼저 판단)

테스트를 작성하기 전에 어느 계층인지부터 정한다. 계층에 따라 프레임워크가 다르다.

| 계층 | 대상 | 프레임워크 | Docker | 실행 위치 |
|------|------|-----------|--------|-----------|
| **L1** 순수 단위 | UseCase(OutPort 모킹)·도메인·VO·유틸 | **Kotest BehaviorSpec** | ❌ | CI + 로컬 |
| **L2** 영속성 슬라이스 | `@DataJpaTest` + 실 PostgreSQL | **JUnit5** | ✅ Testcontainers | **로컬 전용** |
| **L3** 컨트롤러 슬라이스 | `@WebMvcTest` (DB 미부팅) | **JUnit5** | ❌ | CI + 로컬 |
| **L4** 풀 E2E | `@SpringBootTest` + 실 PostgreSQL | **JUnit5** | ✅ Testcontainers | **로컬 전용** |

- **L1(단위·UseCase)은 반드시 Kotest BehaviorSpec** — 규칙 1을 따른다.
- **L2/L3/L4 슬라이스·통합·E2E는 JUnit5** — 규칙 2를 따른다. (`@DataJpaTest`/`@WebMvcTest`/`@SpringBootTest`가 SpringExtension 을 자동 등록하므로 Kotest 통합은 쓰지 않는다. 코드베이스에 Kotest+Spring 통합 기반이 없다.)
- 이 4계층은 현재 **도입·확산 중**(portalManagement·kubeManagement 표준 정립 완료). 신규 통합/E2E 테스트는 이 패턴을 따르되, 공통 베이스 클래스 배치(모듈별 vs testFixtures)는 확산 시 재검토 여지가 있다.
- 상세 전략·근거: [`docs/todo/testing/integration-test-strategy.md`](../../../docs/todo/testing/integration-test-strategy.md)

## 규칙 1: 단위 테스트(L1)는 항상 BehaviorSpec 사용

**L1 순수 단위·UseCase 테스트**는 반드시 Kotest의 BehaviorSpec을 사용한다. FunSpec, StringSpec, DescribeSpec 등 다른 Kotest 스타일 금지.

> **JUnit5 허용 예외**: 아래 두 경우는 JUnit5 를 사용한다(BehaviorSpec 아님).
> - **ArchUnit 아키텍처 테스트** (`@AnalyzeClasses`/`@ArchTest`) — 규칙 1의 하위 절 참조.
> - **L2/L3/L4 슬라이스·통합·E2E** (`@DataJpaTest`/`@WebMvcTest`/`@SpringBootTest`) — 규칙 0·규칙 2 참조.

// WRONG - JUnit 스타일
```kotlin
@Test
fun `프로젝트를 생성한다`() {
    val result = useCase.execute(command)
    assertEquals(expected, result)
}
```

// CORRECT - Kotest BehaviorSpec
```kotlin
class CreateAlertUseCaseTest : BehaviorSpec() {
    init {
        Given("유효한 알림 생성 요청이 주어졌을 때") {
            val command = CreateAlertCommand(name = "삼성전자", price = BigDecimal("70000"))

            When("UseCase를 실행하면") {
                val result = useCase.execute(command)

                Then("알림이 생성된다") {
                    result.name shouldBe "삼성전자"
                }
            }
        }
    }
}
```

### 예외: ArchUnit 아키텍처 테스트

아키텍처 가드(ArchUnit)는 L1 단위 테스트 중 JUnit5 를 쓰는 예외다(다른 예외는 규칙 0 의 L2/L3/L4 슬라이스). ArchUnit 표준인 JUnit5 `@AnalyzeClasses` / `@ArchTest` 스타일을 사용한다.

- 위치: `src/test/.../architecture/*ArchitectureTest.kt` (BC 모듈 한정)
- 상세 규칙(R1~R7)·작성 패턴·신규 규칙 추가 절차: `docs/testing/archunit-architecture-guide.md`
- L1 순수 단위·UseCase 테스트는 BehaviorSpec 규칙을 그대로 따른다(L2/L3/L4 는 규칙 2 의 JUnit5 패턴).

## 규칙 2: 슬라이스·통합·E2E 테스트(L2/L3/L4)는 JUnit5 + 공통 베이스

L2/L3/L4 는 **JUnit5** (`@Test`) 로 작성하고, 계층별 공통 베이스를 상속한다. (Kotest `SpringExtension` 을 쓰지 않는다.)

패키지: `com.nhn.inje.ccp.integration` (베이스 클래스). 실 DB 배선은 `TestcontainersTestBase` 의 싱글톤 PostgreSQL 컨테이너(`withReuse(true)`) + `@DynamicPropertySource`(`spring.datasource.*` 주입)로 처리하고, `application-test.yml` 은 Flyway·JPA·더미 프로퍼티만 둔다(datasource URL/드라이버는 두지 않음). 스키마는 Flyway 소유. (`jdbc:tc:` URL 방식은 `withReuse` 를 못 걸어 실행 간 재사용이 안 되므로 채택하지 않는다.)

### L2 — 영속성 슬라이스 (`@DataJpaTest`, Testcontainers, 로컬 전용)

```kotlin
// AbstractDataJpaTest 상속 → @DataJpaTest + @AutoConfigureTestDatabase(NONE) + @Import(JpaConfig) + @ActiveProfiles("test")
//                          + TestcontainersTestBase(@Tag("testcontainers")) 전파
class OrganizationJpaRepositoryImplTest(
    @Autowired private val organizationJpaRepository: OrganizationJpaRepository,
) : AbstractDataJpaTest() {
    @Test
    fun `이름이 중복되면 실 DB unique 제약 위반이 발생한다`() { /* ... */ }
}
```

### L3 — 컨트롤러 슬라이스 (`@WebMvcTest`, DB 미부팅, CI 실행)

```kotlin
@WebMvcTest(
    controllers = [ClusterV1Controller::class],
    // common-web 의 config/converter(CustomJwtConverter 등)가 슬라이스 자동스캔되므로 REGEX 로 배제
    excludeFilters = [ComponentScan.Filter(type = FilterType.REGEX, pattern = ["com\\.nhn\\.inje\\.ccp\\.(config|converter)\\..*"])],
)
class ClusterV1ControllerWebTest {
    @Autowired lateinit var mockMvc: MockMvc
    @MockkBean lateinit var getClusterNamesInPort: GetClusterNamesInPort  // InPort 를 MockkBean
}
```
- DB 를 띄우지 않으므로 **Docker 불필요 → `@Tag("testcontainers")` 를 붙이지 않는다**(CI 게이트에서 실행됨).

### L4 — 풀 E2E (`@SpringBootTest` + 실 톰캣, Testcontainers, 로컬 전용)

```kotlin
// AbstractE2ETest 상속 → @SpringBootTest(RANDOM_PORT) + @ActiveProfiles("test","keycloak-local","argo-local","gitea-local","harbor-local","security-local")
//                       + valkey 스택 @MockkBean 중앙 무력화 + TestcontainersTestBase(@Tag("testcontainers"))
class OrganizationE2ETest : AbstractE2ETest() {
    @Test
    fun `조직을 생성하고 단건 조회로 왕복 검증한다`() {
        val resp = restTemplate.postForEntity(url("/v1/organizations"), body, String::class.java)
        // ...
    }
}
```
- 부팅 blocker 인 valkey(Redisson eager·리스너)는 베이스에서 `@MockkBean` 으로 무력화, 외부 Feign 은 `-local` 프로파일로 프로퍼티만 채우고 lazy proxy 라 실 연결 없음.

### Testcontainers(L2/L4) 로컬 전용 실행 — Docker 필요

`@Tag("testcontainers")` 계층은 CI 러너에 Docker 가 없어 **기본 `test` 태스크에서 정적 배제**된다. 로컬에서는 전용 태스크로만 실행한다.

```bash
# Docker Desktop 실행 필수. api.version 은 각 환경의 최소 지원 API 를 동적 주입한다(아래 함정 참조).
./gradlew :portalManagement:integrationTest -Dapi.version=$(docker version --format '{{.Server.MinAPIVersion}}')
./gradlew :portalManagement:integrationTest --tests "com.nhn.inje.ccp.adapter.organization.restIn.OrganizationE2ETest" -Dapi.version=$(docker version --format '{{.Server.MinAPIVersion}}')
```

> **[재발 함정] `-Dapi.version` 은 고정값이 아니라 설치된 Docker 의 최소 지원 API 를 넣는다**: docker-java 기본(1.32)이 최신 Docker 의 최소 지원 API 와 협상 실패 → `Could not find a valid Docker environment`. `docker version --format '{{.Server.MinAPIVersion}}'` 로 각 환경의 최소 API 를 확인해 `-Dapi.version=$(docker version --format '{{.Server.MinAPIVersion}}')` 로 주입한다(`DOCKER_API_VERSION` env 는 gradle 이 시스템 프로퍼티로 변환할 때만 유효). **고정값(예: `1.43`)은 Docker 가 그보다 높은 최소 API(예: `1.44`, Docker 29.x)를 요구하는 환경에서 오히려 실패**하므로 쓰지 않는다.
> **IntelliJ 네이티브 러너(초록 화살표)**: gradle 태스크를 안 타므로 `api.version` 주입이 없다 → **Run Config(또는 Templates→JUnit)의 VM options 에 `-Dapi.version=<MinAPIVersion>` 추가**(값은 위 `docker version` 명령의 출력). 또한 이 테스트는 `@Tag("testcontainers")` 라 IntelliJ 를 Gradle 위임 실행으로 두면 기본 `test` 태스크에서 배제돼 안 돈다 → **"Run tests using: IntelliJ IDEA" + VM 옵션** 조합으로 실행한다.

## 규칙 3: 테스트 데이터는 Instancio 사용

반복적인 테스트 데이터 생성에는 Instancio를 사용한다.

---

## 모킹 전략

### OutPort만 모킹

UseCase 테스트 시 OutPort 인터페이스만 모킹한다. Repository나 Adapter 구현체를 직접 모킹하지 않는다.

// WRONG - Repository 직접 모킹
```kotlin
val repository = mockk<AlertJpaRepository>()  // 금지!
```

// CORRECT - OutPort 인터페이스만 모킹
```kotlin
val saveAlertOutPort = mockk<SaveAlertOutPort>()
val useCase = CreateAlertUseCase(saveAlertOutPort)
```

### MockK 사용

Mockito 대신 MockK를 사용한다.

// WRONG - Mockito 사용
```kotlin
@Mock lateinit var outPort: SaveAlertOutPort
@InjectMocks lateinit var useCase: CreateAlertUseCase
```

// CORRECT - MockK 사용
```kotlin
val outPort = mockk<SaveAlertOutPort>()
val useCase = CreateAlertUseCase(outPort)

every { outPort.execute(any()) } returns expected
verify(exactly = 1) { outPort.execute(any()) }
```

### 전체 테스트 패턴

```kotlin
@SpringBootTest
@ActiveProfiles("test")
class CreateAlertUseCaseTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    init {
        val saveAlertOutPort = mockk<SaveAlertOutPort>()
        val useCase = CreateAlertUseCase(saveAlertOutPort)

        Given("유효한 알림 생성 요청이 주어졌을 때") {
            val command = CreateAlertCommand(
                stockCode = StockCode("005930"),
                targetPrice = Money(BigDecimal("70000")),
            )
            val expected = Alert.create(
                stockCode = StockCode("005930"),
                targetPrice = Money(BigDecimal("70000")),
            )

            every { saveAlertOutPort.execute(any()) } returns expected

            When("UseCase를 실행하면") {
                val result = useCase.execute(command)

                Then("알림이 저장되어 반환된다") {
                    result shouldBe expected
                    verify(exactly = 1) { saveAlertOutPort.execute(any()) }
                }
            }
        }
    }
}
```

---

## HTTP 테스트 파일 (.http)

### 규칙: 새 API 추가 시 .http 파일도 함께 추가

API 수동 테스트용 `.http` 파일을 해당 도메인의 `.http` 파일에 추가한다.

### 위치

```
{모듈}/src/test/resources/http/{도메인}/{파일명}.http
```

### 구조

```http
@baseUrl = http://localhost:8080/{모듈-context-path}/api
@token = Bearer {JWT 토큰}

### ===== {섹션명} =====

### {API 설명}
POST {{baseUrl}}/v1/{path}
Authorization: {{token}}
Content-Type: application/json

{
  "field": "value"
}

### {조회 API 설명}
GET {{baseUrl}}/v1/{path}?param=value
Authorization: {{token}}
```

### 규칙
- 파일 상단에 `@baseUrl`, `@token` 등 공통 변수 선언
- 도메인별 폴더로 구분하여 관리 (예: `http/argo/`, `http/namespace/`)
- 기존 도메인 폴더에 `.http` 파일이 있으면 해당 파일에 추가
- 각 요청 앞에 `###`으로 구분하고 설명 추가
- `###` 구분선과 섹션명으로 API 그룹핑

### 예시

```http
@baseUrl = http://localhost:8080/kube-management/api
@token = Bearer eyJ...

### ===== Storage GitOps 생성 =====

### ConfigMap GitOps 생성
POST {{baseUrl}}/v1/storages/cicd
Authorization: {{token}}
Content-Type: application/json

{
  "type": "CONFIG_MAP",
  "yaml": "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: my-config\n",
  "applicationName": "my-argocd-app",
  "path": "storage/configmaps"
}
```

---

## 체크리스트

### 계층 판단 (먼저)
- [ ] 작성할 테스트가 L1/L2/L3/L4 중 어디인지 규칙 0 표로 판단했는가?
- [ ] 계층에 맞는 프레임워크를 골랐는가? (L1=Kotest, L2/L3/L4=JUnit5)

### 단위 테스트 L1 (Kotest)
- [ ] BehaviorSpec을 상속하는가?
- [ ] Given/When/Then 구조를 따르는가?
- [ ] Spring 통합이 필요하면 SpringExtension이 있는가? (순수 단위는 불필요)
- [ ] @ActiveProfiles("test")가 있는가? (Spring 통합 시)
- [ ] OutPort 인터페이스만 모킹하는가?
- [ ] MockK를 사용하는가? (Mockito 아님)

### 슬라이스·통합·E2E L2/L3/L4 (JUnit5)
- [ ] JUnit5 `@Test` 로 작성하고 계층별 베이스(`AbstractDataJpaTest`/`AbstractE2ETest`)를 상속하는가? (Kotest 아님)
- [ ] L2/L4(Testcontainers)는 `@Tag("testcontainers")` 가 베이스로 전파되는가? / L3(@WebMvcTest)는 태그를 붙이지 않았는가?
- [ ] L2/L4 를 `./gradlew :module:integrationTest -Dapi.version=$(docker version --format '{{.Server.MinAPIVersion}}')` 로 로컬 GREEN 검증했는가?

### HTTP 테스트 파일
- [ ] 새 API에 대한 .http 테스트가 추가되었는가?
- [ ] 기존 도메인 .http 파일에 추가했는가? (신규 파일 생성 지양)
- [ ] 공통 변수(@baseUrl, @token)를 사용하는가?
