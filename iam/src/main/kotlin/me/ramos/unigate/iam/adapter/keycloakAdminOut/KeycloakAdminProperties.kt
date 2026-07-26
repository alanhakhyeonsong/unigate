package me.ramos.unigate.iam.adapter.keycloakAdminOut

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Keycloak Admin 접속 설정.
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
  fun tokenUrl(): String = "${serverUrl.trimEnd('/')}/realms/$realm/protocol/openid-connect/token"
}
