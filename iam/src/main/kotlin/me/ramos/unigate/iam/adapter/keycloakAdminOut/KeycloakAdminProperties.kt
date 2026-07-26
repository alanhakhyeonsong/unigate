package me.ramos.unigate.iam.adapter.keycloakAdminOut

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * IAM 이 바라보는 Keycloak 좌표.
 *
 * 이름은 "Admin" 이지만 담는 것은 **realm 좌표**이며, IAM 의 두 토큰 컨텍스트가 모두 여기서 출발한다
 * (`IAM_PLATFORM_DECISION.md` D4 보강):
 * - **Admin 인증** — [tokenUrl] · [adminRealmUrl] (service account, `keycloakAdminOut`)
 * - **호출자 신원 검증** — [issuerUrl] · [jwkSetUri] (Resource Server, `config.IamSecurityConfig`)
 *
 * 좌표가 하나뿐인데 두 곳에서 각자 조립하면 realm 변경 시 한쪽만 고치고 넘어가게 된다.
 *
 * `serverUrl` 과 `realm` 을 따로 받는 이유: Admin API 경로(`/admin/realms/{realm}`)와 토큰 경로
 * (`/realms/{realm}/protocol/openid-connect/token`)의 **접두사가 다르다.** issuer-uri 하나만 받아
 * 문자열을 잘라 쓰면 realm 이름에 슬래시가 없다는 가정에 의존하게 되고, 나중에 조용히 깨진다.
 *
 * ⚠️ `clientSecret` 은 절대 로그에 남기지 않는다(`CLAUDE.md` §8). 이 클래스의 `toString` 도 쓰지 않는다
 * — `data class` 로 만들면 자동 생성된 `toString` 에 secret 이 들어가므로 **일반 class** 로 둔다.
 */
@ConfigurationProperties(prefix = "unigate.iam.keycloak")
class KeycloakAdminProperties(
  /** Keycloak 베이스 URL (예: `https://<keycloak-host>`). 경로 없이 호스트까지만. */
  val serverUrl: String,
  /** 대상 realm 이름. local 은 `test`, alpha 는 `unigate`. */
  val realm: String,
  /** IAM 전용 관리 client. 게이트웨이 로그인 client 와 **분리**한다(blast radius 축소, §14). */
  val clientId: String,
  /** service account 자격증명. 환경변수로만 주입한다. */
  val clientSecret: String,
  /** 토큰 만료 몇 초 전에 미리 갱신할지. 갱신 도중 만료되는 것을 막는 여유분. */
  val tokenRefreshSkewSeconds: Long = 30,
) {
  /** Admin REST API 의 realm 루트. */
  fun adminRealmUrl(): String = "${serverUrl.trimEnd('/')}/admin/realms/$realm"

  /** service account 토큰 발급 엔드포인트. */
  fun tokenUrl(): String = "$realmUrl/protocol/openid-connect/token"

  /**
   * 이 realm 이 발급한 토큰의 `iss` 클레임 값 (Phase 8f).
   *
   * Resource Server 가 relay 된 사용자 JWT 를 검증할 때 쓴다. Admin 용 좌표와 **같은 곳에서**
   * 조립하는 이유는 realm URL 규칙이 하나뿐이기 때문이다 — 두 군데서 문자열을 이어 붙이면
   * realm 을 바꿀 때 한쪽만 고치고 넘어가기 쉽다.
   */
  fun issuerUrl(): String = realmUrl

  /** JWKS 엔드포인트. Keycloak 규약 경로이며, 검증 키는 여기서 지연 조회·캐싱된다. */
  fun jwkSetUri(): String = "$realmUrl/protocol/openid-connect/certs"

  private val realmUrl: String
    get() = "${serverUrl.trimEnd('/')}/realms/$realm"
}
