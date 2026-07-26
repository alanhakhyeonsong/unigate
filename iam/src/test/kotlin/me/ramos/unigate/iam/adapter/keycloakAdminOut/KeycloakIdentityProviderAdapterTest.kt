package me.ramos.unigate.iam.adapter.keycloakAdminOut

import me.ramos.unigate.iam.application.user.port.outbound.CreateIdentityCommand
import me.ramos.unigate.iam.application.user.port.outbound.IdentityAlreadyExistsException
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderUnavailableException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

/**
 * Keycloak Admin 어댑터 테스트 — **실제 Keycloak 없이 HTTP 계약을 고정**한다.
 *
 * ## 무엇을 검증하고 무엇은 못 하는가
 * MockWebServer 는 우리가 **가정한** Keycloak 동작(201 + Location, 409 Conflict, `exact=true` 조회)을
 * 흉내 낼 뿐이다. 그 가정이 실제 Keycloak 과 같은지는 이 테스트가 증명하지 못한다.
 * 실측은 realm 에 `unigate-iam` client 를 만든 뒤에 가능하다(`docs/KEYCLOAK_REALM_SETUP.md` §4.5).
 *
 * 그래도 이 테스트에는 값이 있다: **멱등 로직·토큰 캐싱·예외 분류**는 우리가 짠 코드이고,
 * 그것이 정확히 동작하는지는 여기서 못 박을 수 있다.
 *
 * 계층: L2 (외부 HTTP 를 mock 으로 대체). JUnit5 사용(testing skill 규칙 2 — 슬라이스/통합).
 */
class KeycloakIdentityProviderAdapterTest {
  private lateinit var server: MockWebServer
  private lateinit var adapter: KeycloakIdentityProviderAdapter
  private lateinit var tokenProvider: ServiceAccountTokenProvider

  @BeforeEach
  fun setUp() {
    server = MockWebServer()
    server.start()

    val properties =
      KeycloakAdminProperties(
        serverUrl = server.url("/").toString().trimEnd('/'),
        realm = REALM,
        clientId = "unigate-iam",
        clientSecret = "test-secret",
      )
    tokenProvider = ServiceAccountTokenProvider(properties, RestClient.builder())
    adapter = KeycloakIdentityProviderAdapter(properties, tokenProvider, RestClient.builder())
  }

  @AfterEach
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `사용자를 생성하면 Location 헤더에서 id 를 뽑아 UserRef 로 돌려준다`() {
    server.enqueue(tokenResponse())
    server.enqueue(emptyUserListResponse()) // findByEmail — 없음
    server.enqueue(
      MockResponse()
        .setResponseCode(201)
        .setHeader("Location", "${server.url("/")}admin/realms/$REALM/users/$USER_ID"),
    )

    val ref = adapter.createUser(command())

    assertThat(ref.value).isEqualTo(USER_ID)

    server.takeRequest() // 토큰
    val lookup = server.takeRequest()
    // exact=true 가 빠지면 부분 일치로 엉뚱한 사용자를 집는다 — 회귀 가드.
    assertThat(lookup.path).contains("exact=true")
    // URLEncoder(form 규칙)를 쓰므로 `@` 는 `%40` 이 된다. 서버가 `@` 로 되돌리니 정상이다.
    assertThat(lookup.path).contains("email=alice%40example.local")

    val create = server.takeRequest()
    assertThat(create.method).isEqualTo("POST")
    assertThat(create.getHeader("Authorization")).isEqualTo("Bearer $ACCESS_TOKEN")
  }

  @Test
  fun `이미 존재하는 이메일이면 생성하지 않고 기존 참조를 재사용한다`() {
    // outbox 는 최소 1회 실행이라 같은 요청이 다시 올 수 있다. 그때 중복 생성하면 안 된다.
    server.enqueue(tokenResponse())
    server.enqueue(userListResponse(USER_ID))

    val ref = adapter.createUser(command())

    assertThat(ref.value).isEqualTo(USER_ID)
    // 토큰 + 조회 두 번뿐 — POST 가 나가지 않았다.
    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun `생성 도중 409 가 나면 재조회해서 그 참조를 쓴다`() {
    // 조회와 생성 사이에 다른 워커가 만든 경우(경합). 실패로 처리하면 영영 진행되지 않는다.
    server.enqueue(tokenResponse())
    server.enqueue(emptyUserListResponse()) // 처음엔 없었다
    server.enqueue(MockResponse().setResponseCode(409)) // 그새 누가 만들었다
    server.enqueue(userListResponse(USER_ID)) // 재조회하니 있다

    val ref = adapter.createUser(command())

    assertThat(ref.value).isEqualTo(USER_ID)
  }

  @Test
  fun `409 인데 재조회에도 없으면 정정이 필요한 실패로 분류한다`() {
    server.enqueue(tokenResponse())
    server.enqueue(emptyUserListResponse())
    server.enqueue(MockResponse().setResponseCode(409))
    server.enqueue(emptyUserListResponse()) // 재조회에도 없다 → 우리가 해결할 수 없다

    assertThatThrownBy { adapter.createUser(command()) }
      .isInstanceOf(IdentityAlreadyExistsException::class.java)
  }

  @Test
  fun `서버 오류는 재시도 가능한 예외로 분류한다`() {
    // 이 구분이 중요하다: outbox 워커가 재시도할지(Unavailable) 사용자에게 정정을 요구할지
    // (AlreadyExists) 를 예외 타입으로 판단한다.
    server.enqueue(tokenResponse())
    server.enqueue(emptyUserListResponse())
    server.enqueue(MockResponse().setResponseCode(500))

    assertThatThrownBy { adapter.createUser(command()) }
      .isInstanceOf(IdentityProviderUnavailableException::class.java)
  }

  @Test
  fun `토큰은 캐시되어 매 호출마다 재발급하지 않는다`() {
    server.enqueue(tokenResponse(expiresIn = 300))
    server.enqueue(emptyUserListResponse())
    server.enqueue(emptyUserListResponse())

    adapter.findByEmail("alice@example.local")
    adapter.findByEmail("bob@example.local")

    // 토큰 1회 + 조회 2회 = 3. 캐시가 없으면 4가 된다.
    assertThat(server.requestCount).isEqualTo(3)
  }

  @Test
  fun `만료가 임박한 토큰은 재발급한다`() {
    // expiresIn 이 skew(기본 30초)보다 작으면 받자마자 만료로 간주된다.
    server.enqueue(tokenResponse(expiresIn = 10))
    server.enqueue(emptyUserListResponse())
    server.enqueue(tokenResponse(expiresIn = 10))
    server.enqueue(emptyUserListResponse())

    adapter.findByEmail("alice@example.local")
    adapter.findByEmail("bob@example.local")

    // 토큰 2회 + 조회 2회 = 4
    assertThat(server.requestCount).isEqualTo(4)
  }

  @Test
  fun `생성 응답에 Location 이 없으면 실패로 처리한다`() {
    // id 를 못 얻으면 프로필과 신원을 연결할 수 없다. 조용히 넘어가면 안 된다.
    server.enqueue(tokenResponse())
    server.enqueue(emptyUserListResponse())
    server.enqueue(MockResponse().setResponseCode(201)) // Location 없음

    assertThatThrownBy { adapter.createUser(command()) }
      .isInstanceOf(IdentityProviderUnavailableException::class.java)
  }

  @Test
  fun `plus 가 든 이메일은 %2B 로 인코딩해 보낸다`() {
    // 실측으로 확인된 버그의 회귀 가드다.
    //   raw(+ 그대로) 조회 건수: 0   /   %2B 인코딩 조회 건수: 1
    // `+` 는 URI 쿼리에서 합법이라 인코딩되지 않지만, 서버는 form 규칙으로 **공백으로 디코딩**한다.
    // 이게 깨지면 gmail alias 형태 이메일은 멱등 검사가 실패해 **영영 가입할 수 없다.**
    server.enqueue(tokenResponse())
    server.enqueue(emptyUserListResponse())

    adapter.findByEmail("alice+tag@example.local")

    server.takeRequest() // 토큰
    val lookup = server.takeRequest()
    assertThat(lookup.path).contains("email=alice%2Btag%40example.local")
    // `+` 가 날것으로 나가면 서버가 공백으로 읽는다 — 절대 있으면 안 된다.
    assertThat(lookup.path).doesNotContain("alice+tag")
  }

  @Test
  fun `토큰 요청은 client_credentials 로 나간다`() {
    server.enqueue(tokenResponse())
    server.enqueue(emptyUserListResponse())

    adapter.findByEmail("alice@example.local")

    val tokenRequest: RecordedRequest = server.takeRequest()
    assertThat(tokenRequest.path).endsWith("/realms/$REALM/protocol/openid-connect/token")
    val body = tokenRequest.body.readUtf8()
    assertThat(body).contains("grant_type=client_credentials")
    assertThat(body).contains("client_id=unigate-iam")
  }

  private fun command() =
    CreateIdentityCommand(
      email = "alice@example.local",
      firstName = "alice",
      lastName = "tester",
    )

  private fun tokenResponse(expiresIn: Long = 300) =
    MockResponse()
      .setResponseCode(200)
      .setHeader("Content-Type", "application/json")
      // snake_case 로 준다 — camelCase 로 매핑하면 null 이 되는 함정을 이 응답이 재현한다.
      .setBody("""{"access_token":"$ACCESS_TOKEN","expires_in":$expiresIn,"token_type":"Bearer"}""")

  private fun emptyUserListResponse() =
    MockResponse()
      .setResponseCode(200)
      .setHeader("Content-Type", "application/json")
      .setBody("[]")

  private fun userListResponse(id: String) =
    MockResponse()
      .setResponseCode(200)
      .setHeader("Content-Type", "application/json")
      .setBody("""[{"id":"$id","username":"alice@example.local"}]""")

  companion object {
    private const val REALM = "test"
    private const val ACCESS_TOKEN = "test-access-token"
    private const val USER_ID = "115f2213-2d36-4bf0-a187-b124f7817b7d"
  }
}
