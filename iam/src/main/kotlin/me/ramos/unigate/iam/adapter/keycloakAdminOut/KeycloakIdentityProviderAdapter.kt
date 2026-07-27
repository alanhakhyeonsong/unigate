package me.ramos.unigate.iam.adapter.keycloakAdminOut

import com.fasterxml.jackson.annotation.JsonProperty
import me.ramos.unigate.iam.application.user.port.outbound.CreateIdentityCommand
import me.ramos.unigate.iam.application.user.port.outbound.IdentityAlreadyExistsException
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderPort
import me.ramos.unigate.iam.application.user.port.outbound.IdentityProviderUnavailableException
import me.ramos.unigate.iam.domain.shared.vo.UserRef
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Keycloak Admin REST API 어댑터 — [IdentityProviderPort] 구현. **Admin API 는 여기서 끝난다.**
 *
 * ## 왜 공식 `keycloak-admin-client` 라이브러리를 쓰지 않는가
 * 그 라이브러리는 RESTEasy/JAX-RS 스택을 끌고 들어온다. 우리는 이미 Spring MVC 를 쓰고 있어
 * HTTP 클라이언트가 중복되고, 라이브러리 타입(`UserRepresentation` 등)이 코드에 퍼지면 봉인이
 * 느슨해진다. 필요한 엔드포인트가 두 개뿐이므로 `RestClient` 로 직접 호출하는 편이 가볍고,
 * **무엇을 Keycloak 에 의존하는지가 코드에 그대로 드러난다.**
 *
 * ## 블로킹이 정상이다
 * `RestClient` 는 동기 클라이언트다. 게이트웨이(WebFlux)였다면 이벤트 루프를 막아선 안 됐지만,
 * 여기는 Virtual Thread 라 블로킹 시 캐리어를 반납한다(`CLAUDE.md` §5.1).
 *
 * ## 멱등성 — outbox 가 최소 1회 실행이라 필수다
 * 워커가 커밋 후 죽었다 살아나면 같은 생성 요청이 다시 온다. 그래서 [createUser] 는
 * **조회 → 생성 → (409 면) 재조회** 순서로 동작한다. 마지막 재조회가 중요한 이유는 §4 참조.
 */
@Component
class KeycloakIdentityProviderAdapter(
  private val properties: KeycloakAdminProperties,
  private val tokenProvider: ServiceAccountTokenProvider,
  restClientBuilder: RestClient.Builder,
) : IdentityProviderPort {
  private val log = LoggerFactory.getLogger(javaClass)
  private val restClient = restClientBuilder.build()

  override fun createUser(command: CreateIdentityCommand): UserRef {
    // 1) 이미 있으면 그대로 쓴다. 재시도로 들어온 요청이 여기서 걸러진다.
    findByEmail(command.email)?.let {
      log.info("이미 존재하는 신원을 재사용한다 (멱등 재시도로 추정)")
      return it
    }

    // 2) 생성 시도.
    val location =
      try {
        createUserRequest(command)
      } catch (e: RestClientResponseException) {
        if (e.statusCode == HttpStatus.CONFLICT) {
          // 3) 409 — 1)과 2) 사이에 다른 워커/사용자가 만들었다(경합). 재조회해서 그 참조를 쓴다.
          //    재조회로도 안 나오면 우리가 만들 수 없는 충돌이므로 정정이 필요하다.
          return findByEmail(command.email)
            ?: throw IdentityAlreadyExistsException(command.email)
        }
        throw toUnavailable("Keycloak 사용자 생성에 실패했습니다 (HTTP ${e.statusCode.value()})", e)
      } catch (e: RestClientException) {
        throw toUnavailable("Keycloak 사용자 생성에 실패했습니다", e)
      }

    return UserRef(extractUserId(location))
  }

  override fun findByEmail(email: String): UserRef? {
    // exact=true 가 없으면 **부분 일치**로 동작해 엉뚱한 사용자를 집을 수 있다.
    //
    // ⚠️ `URI` 객체로 넘긴다. `uri(String)` 에 넘기면 RestClient 가 그 문자열을 **URI 템플릿으로 보고
    // 다시 인코딩**해 `%2B` 가 `%252B` 로 이중 인코딩된다(실제로 그렇게 깨졌다).
    // `URI.create` 는 이미 인코딩된 문자열을 그대로 받아들인다.
    val uri = URI.create("${properties.adminRealmUrl()}/users?email=${encodeQueryValue(email)}&exact=true")

    val users =
      try {
        restClient
          .get()
          .uri(uri)
          .header("Authorization", "Bearer ${tokenProvider.accessToken()}")
          .retrieve()
          .body(Array<KeycloakUser>::class.java)
      } catch (e: RestClientResponseException) {
        if (e.statusCode == HttpStatus.UNAUTHORIZED) {
          // 토큰이 만료됐거나 무효화됐다. 캐시를 버려 다음 호출에서 새로 받게 한다.
          tokenProvider.invalidate()
        }
        throw toUnavailable("Keycloak 사용자 조회에 실패했습니다 (HTTP ${e.statusCode.value()})", e)
      } catch (e: RestClientException) {
        throw toUnavailable("Keycloak 사용자 조회에 실패했습니다", e)
      }

    return users?.firstOrNull()?.id?.let { UserRef(it) }
  }

  /**
   * 테넌트 group `/tenants/{tenantId}` 를 만든다 (Phase 9c-2).
   *
   * ## 흐름은 [createUser] 의 멱등 전략과 같다
   * 조회 → 없으면 생성 → 409 면 "이미 있다" 로 간주하고 성공 처리. outbox 가 최소 1회 실행이라
   * 같은 지시가 두 번 올 수 있고, 그때 예외를 던지면 **정상 재시도가 영구 실패로 둔갑**한다.
   *
   * ## 부모 group 을 먼저 보장한다
   * `/tenants/{id}` 는 `tenants` 라는 부모 아래의 자식이다. realm 을 새로 만든 환경에는 그 부모가
   * 없으므로, 가정하지 않고 **없으면 만든다.** 이 한 줄이 없으면 새 환경의 첫 테넌트 생성이
   * 404 로 실패하고, 원인이 "부모 group 부재" 라는 것은 응답만 봐서는 드러나지 않는다.
   */
  override fun createTenantGroup(tenantId: String) {
    val parentId = ensureParentGroup()

    val existing = findChildGroup(parentId, tenantId)
    if (existing != null) {
      return
    }

    try {
      restClient
        .post()
        .uri("${properties.adminRealmUrl()}/groups/$parentId/children")
        .header("Authorization", "Bearer ${tokenProvider.accessToken()}")
        .contentType(MediaType.APPLICATION_JSON)
        .body(mapOf("name" to tenantId))
        .retrieve()
        .toBodilessEntity()
    } catch (e: RestClientResponseException) {
      if (e.statusCode == HttpStatus.CONFLICT) {
        // 다른 워커(또는 재시도)가 먼저 만들었다. 경합을 실패로 만들지 않는다.
        return
      }
      if (e.statusCode == HttpStatus.UNAUTHORIZED) {
        tokenProvider.invalidate()
      }
      throw toUnavailable("Keycloak 테넌트 group 생성에 실패했습니다 (HTTP ${e.statusCode.value()})", e)
    } catch (e: RestClientException) {
      throw toUnavailable("Keycloak 테넌트 group 생성에 실패했습니다", e)
    }
  }

  /**
   * 사용자를 테넌트 group 에 넣는다 (Phase 9d).
   *
   * Keycloak 의 group 멤버 추가는 `PUT /users/{id}/groups/{groupId}` 로 **원래 멱등**이다 —
   * 이미 소속이어도 204 다. outbox 재시도와 잘 맞는다.
   */
  override fun addUserToTenantGroup(
    tenantId: String,
    userRef: String,
  ) {
    val groupId = requireTenantGroupId(tenantId)
    try {
      restClient
        .put()
        .uri("${properties.adminRealmUrl()}/users/$userRef/groups/$groupId")
        .header("Authorization", "Bearer ${tokenProvider.accessToken()}")
        .retrieve()
        .toBodilessEntity()
    } catch (e: RestClientResponseException) {
      if (e.statusCode == HttpStatus.UNAUTHORIZED) {
        tokenProvider.invalidate()
      }
      throw toUnavailable("Keycloak group 멤버 추가에 실패했습니다 (HTTP ${e.statusCode.value()})", e)
    } catch (e: RestClientException) {
      throw toUnavailable("Keycloak group 멤버 추가에 실패했습니다", e)
    }
  }

  /**
   * 사용자를 테넌트 group 에서 뺀다 (Phase 9d).
   *
   * ⚠️ **404 를 성공으로 처리한다.** 제거는 "그 상태로 만든다" 는 뜻이지 "지금 있어야 한다" 가
   * 아니다. 없는 것을 실패로 만들면 재시도가 첫 성공 이후 영원히 실패하고, 결국 DEAD 로 간다 —
   * 실제로는 원하던 상태가 이미 이뤄졌는데도.
   */
  override fun removeUserFromTenantGroup(
    tenantId: String,
    userRef: String,
  ) {
    val groupId = requireTenantGroupId(tenantId)
    try {
      restClient
        .delete()
        .uri("${properties.adminRealmUrl()}/users/$userRef/groups/$groupId")
        .header("Authorization", "Bearer ${tokenProvider.accessToken()}")
        .retrieve()
        .toBodilessEntity()
    } catch (e: RestClientResponseException) {
      if (e.statusCode == HttpStatus.NOT_FOUND) {
        return
      }
      if (e.statusCode == HttpStatus.UNAUTHORIZED) {
        tokenProvider.invalidate()
      }
      throw toUnavailable("Keycloak group 멤버 제거에 실패했습니다 (HTTP ${e.statusCode.value()})", e)
    } catch (e: RestClientException) {
      throw toUnavailable("Keycloak group 멤버 제거에 실패했습니다", e)
    }
  }

  /**
   * 테넌트 group 의 id 를 찾는다. 없으면 **재시도 대상**으로 던진다.
   *
   * group 은 `CREATE_KEYCLOAK_GROUP` 지시가 만든다. 그 지시가 아직 처리되지 않았다면 여기서
   * 못 찾는데, 그건 영구 실패가 아니라 **순서 문제**다 — 재시도하면 풀린다.
   */
  private fun requireTenantGroupId(tenantId: String): String {
    val parentId =
      findTopLevelGroup(TENANT_GROUP_PARENT)
        ?: throw IdentityProviderUnavailableException("테넌트 부모 group 이 아직 없습니다")
    return findChildGroup(parentId, tenantId)
      ?: throw IdentityProviderUnavailableException("테넌트 group 이 아직 없습니다: $tenantId")
  }

  /** 부모 group `tenants` 의 id 를 돌려준다. 없으면 만든다. */
  private fun ensureParentGroup(): String {
    findTopLevelGroup(TENANT_GROUP_PARENT)?.let { return it }

    try {
      restClient
        .post()
        .uri("${properties.adminRealmUrl()}/groups")
        .header("Authorization", "Bearer ${tokenProvider.accessToken()}")
        .contentType(MediaType.APPLICATION_JSON)
        .body(mapOf("name" to TENANT_GROUP_PARENT))
        .retrieve()
        .toBodilessEntity()
    } catch (e: RestClientResponseException) {
      // 409 면 경합에서 진 것이므로 아래 재조회로 이어간다.
      if (e.statusCode != HttpStatus.CONFLICT) {
        if (e.statusCode == HttpStatus.UNAUTHORIZED) {
          tokenProvider.invalidate()
        }
        throw toUnavailable("Keycloak 부모 group 생성에 실패했습니다 (HTTP ${e.statusCode.value()})", e)
      }
    } catch (e: RestClientException) {
      throw toUnavailable("Keycloak 부모 group 생성에 실패했습니다", e)
    }

    // 만들었는데 못 찾는 것은 일관성 지연(또는 경합)일 수 있으므로 **재시도 대상**으로 던진다.
    return findTopLevelGroup(TENANT_GROUP_PARENT)
      ?: throw IdentityProviderUnavailableException("부모 group 을 만들었지만 다시 찾지 못했습니다")
  }

  private fun findTopLevelGroup(name: String): String? {
    val uri = URI.create("${properties.adminRealmUrl()}/groups?search=${encodeQueryValue(name)}")
    return try {
      restClient
        .get()
        .uri(uri)
        .header("Authorization", "Bearer ${tokenProvider.accessToken()}")
        .retrieve()
        .body(Array<KeycloakGroup>::class.java)
        // search 는 **부분 일치**다. 이름이 정확히 같은 것만 골라야 `tenants-archive` 같은
        // 유사 group 을 잘못 집지 않는다.
        ?.firstOrNull { it.name == name }
        ?.id
    } catch (e: RestClientResponseException) {
      if (e.statusCode == HttpStatus.UNAUTHORIZED) {
        tokenProvider.invalidate()
      }
      throw toUnavailable("Keycloak group 조회에 실패했습니다 (HTTP ${e.statusCode.value()})", e)
    } catch (e: RestClientException) {
      throw toUnavailable("Keycloak group 조회에 실패했습니다", e)
    }
  }

  private fun findChildGroup(
    parentId: String,
    name: String,
  ): String? {
    val uri = URI.create("${properties.adminRealmUrl()}/groups/$parentId/children?search=${encodeQueryValue(name)}")
    return try {
      restClient
        .get()
        .uri(uri)
        .header("Authorization", "Bearer ${tokenProvider.accessToken()}")
        .retrieve()
        .body(Array<KeycloakGroup>::class.java)
        ?.firstOrNull { it.name == name }
        ?.id
    } catch (e: RestClientResponseException) {
      if (e.statusCode == HttpStatus.UNAUTHORIZED) {
        tokenProvider.invalidate()
      }
      throw toUnavailable("Keycloak 자식 group 조회에 실패했습니다 (HTTP ${e.statusCode.value()})", e)
    } catch (e: RestClientException) {
      throw toUnavailable("Keycloak 자식 group 조회에 실패했습니다", e)
    }
  }

  /** 생성 요청을 보내고 `Location` 헤더를 돌려준다. */
  private fun createUserRequest(command: CreateIdentityCommand): String {
    val payload =
      KeycloakUserPayload(
        username = command.email,
        email = command.email,
        firstName = command.firstName,
        lastName = command.lastName,
        // 이메일 인증은 Keycloak 플로우에 맡긴다. 여기서 true 로 두면 검증되지 않은 주소가 승격된다.
        emailVerified = false,
        enabled = true,
      )

    val response =
      restClient
        .post()
        .uri("${properties.adminRealmUrl()}/users")
        .header("Authorization", "Bearer ${tokenProvider.accessToken()}")
        .contentType(MediaType.APPLICATION_JSON)
        .body(payload)
        .retrieve()
        .toBodilessEntity()

    // Keycloak 은 생성된 사용자의 id 를 **본문이 아니라 Location 헤더**로 준다.
    // (`.../admin/realms/{realm}/users/{id}`)
    return response.headers.location?.toString()
      ?: throw IdentityProviderUnavailableException("Keycloak 이 Location 헤더를 주지 않았습니다")
  }

  /**
   * 쿼리 파라미터 값을 인코딩한다.
   *
   * ## 왜 `UriComponentsBuilder.encode()` 로는 부족한가 (실측으로 확인된 버그)
   * `+` 는 URI 쿼리에서 **합법 문자**라 `UriComponentsBuilder` 가 인코딩하지 않는다. 그런데 서버는
   * 쿼리를 `application/x-www-form-urlencoded` 로 해석해 **`+` 를 공백으로 디코딩**한다.
   * 결과적으로 `alice+tag@example.local` 을 찾으면 `alice tag@example.local` 을 찾게 되어 **0건**이 나온다.
   *
   * 실제 Keycloak 으로 측정한 결과:
   * ```
   * raw(+ 그대로) 조회 건수: 0   /   %2B 인코딩 조회 건수: 1
   * ```
   *
   * 이게 왜 치명적인가: [createUser] 의 멱등 검사가 기존 사용자를 못 찾고 → 생성 시도 → 409 →
   * 재조회도 같은 이유로 실패 → `IdentityAlreadyExistsException`. **그 이메일은 영영 가입할 수 없다.**
   *
   * `URLEncoder` 는 form 인코딩 규칙을 쓰므로 `+` 를 `%2B` 로 바꾼다. 서버가 form 규칙으로 디코딩하니
   * 이쪽이 짝이 맞는다. (`@` 는 `%40` 이 되는데, 서버가 `@` 로 되돌리므로 문제없다.)
   */
  private fun encodeQueryValue(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  /** `.../users/{id}` 에서 마지막 세그먼트를 뽑는다. */
  private fun extractUserId(location: String): String =
    location.trimEnd('/').substringAfterLast('/').also {
      if (it.isBlank()) {
        throw IdentityProviderUnavailableException("Location 헤더에서 사용자 id 를 읽지 못했습니다")
      }
    }

  /**
   * 실패를 재시도 가능한 예외로 감싼다.
   *
   * ⚠️ 원인 예외의 메시지를 우리 메시지에 **이어 붙이지 않는다.** Keycloak 응답 본문이나 요청 정보가
   * 그대로 로그·상위 예외에 실릴 수 있기 때문이다(`CLAUDE.md` §8). cause 로만 보존한다.
   */
  private fun toUnavailable(
    message: String,
    cause: Throwable,
  ): IdentityProviderUnavailableException {
    log.warn("Keycloak Admin 호출 실패: {}", message)
    return IdentityProviderUnavailableException(message, cause)
  }

  /** group 조회 응답의 필요한 필드만. Keycloak 표현이 이 클래스 밖으로 새지 않는다. */
  internal data class KeycloakGroup(
    val id: String = "",
    val name: String = "",
  )

  /** 조회 응답에서 우리가 쓰는 필드만. Keycloak 의 나머지 필드에 의존하지 않는다. */
  internal data class KeycloakUser(
    val id: String,
  )

  /** 생성 요청 본문. Keycloak 고유 형식이며 **이 파일 밖으로 나가지 않는다**(봉인). */
  internal data class KeycloakUserPayload(
    val username: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    @param:JsonProperty("emailVerified") val emailVerified: Boolean,
    val enabled: Boolean,
  )

  private companion object {
    /**
     * 테넌트 group 들의 부모. `TenantId.GROUP_PREFIX`(`/tenants/`)와 **같은 이름**이어야 한다 —
     * 토큰 claim 에 실릴 경로가 `/tenants/{id}` 이므로, 여기가 어긋나면 GW 의 테넌트 게이트(P9f)가
     * 아무 것도 매칭하지 못한다.
     */
    const val TENANT_GROUP_PARENT = "tenants"
  }
}
