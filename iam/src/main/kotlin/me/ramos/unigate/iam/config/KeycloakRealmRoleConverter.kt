package me.ramos.unigate.iam.config

import org.springframework.core.convert.converter.Converter
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component

/**
 * Keycloak 토큰의 realm 역할을 Spring Security 권한으로 옮긴다 (Phase 9c).
 *
 * ## 이게 없으면 인가 규칙이 **조용히 항상 실패한다**
 * Spring Security 의 기본 변환기(`JwtGrantedAuthoritiesConverter`)는 `scope` / `scp` 클레임만 본다.
 * 그런데 Keycloak 은 역할을 **`realm_access.roles`** 배열에 넣는다. 그래서 기본 설정 그대로면
 * 토큰에 `unigate-admin` 이 실려 있어도 권한 목록은 **비어 있고**, `hasAuthority(...)` 는 무조건
 * 거짓이 된다.
 *
 * 증상이 고약하다 — 인증은 성공하므로 401 이 아니라 **403** 이 나가고, 토큰을 디코딩해 보면
 * 역할이 분명히 들어 있어서 "왜 안 되지" 로 한참 헤맨다. 그 착시를 이 클래스가 없앤다.
 *
 * ## `ROLE_` 접두사를 붙이지 않는다
 * Spring 관용은 권한에 `ROLE_` 을 붙이고 `hasRole("x")` 로 검사하는 것이다(그 헬퍼가 접두사를
 * 자동으로 붙인다). 여기서는 **붙이지 않고** `hasAuthority("unigate-admin")` 을 쓴다.
 *
 * | | `hasRole` + 접두사 | 여기서 택한 방식 |
 * |---|---|---|
 * | 설정에 적히는 값 | `unigate-admin` | `unigate-admin` |
 * | 실제 권한 문자열 | `ROLE_unigate-admin` | `unigate-admin` |
 * | Keycloak 역할 이름과의 관계 | **한 겹 어긋난다** | 그대로 일치 |
 *
 * 접두사 규칙은 Spring 안에서만 통하는 약속이라, Keycloak realm 에 있는 이름과 코드에 적힌 이름이
 * 달라진다. 디버깅할 때 토큰의 `realm_access.roles` 와 `SecurityContext` 의 authorities 를 나란히
 * 놓고 **문자열이 같은 편**이 낫다고 판단했다.
 *
 * ⚠️ 대신 `hasRole()` 을 쓰면 안 된다. 그 헬퍼는 `ROLE_` 을 붙여 찾으므로 여기서는 항상 거짓이다.
 */
@Component
class KeycloakRealmRoleConverter : Converter<Jwt, Collection<GrantedAuthority>> {
  override fun convert(source: Jwt): Collection<GrantedAuthority> {
    val realmAccess = source.getClaimAsMap(CLAIM_REALM_ACCESS) ?: return emptyList()

    // 클레임은 외부에서 온 값이라 타입을 단정하지 않는다. 형태가 다르면 권한 0개로 처리한다 —
    // 여기서 예외를 던지면 **토큰 검증 자체가 실패**해 401 이 되고, 원인은 인가 설정인데
    // 인증 문제처럼 보이게 된다.
    val roles = realmAccess[CLAIM_ROLES] as? Collection<*> ?: return emptyList()

    return roles
      .filterIsInstance<String>()
      .map { SimpleGrantedAuthority(it) }
  }

  private companion object {
    const val CLAIM_REALM_ACCESS = "realm_access"
    const val CLAIM_ROLES = "roles"
  }
}
