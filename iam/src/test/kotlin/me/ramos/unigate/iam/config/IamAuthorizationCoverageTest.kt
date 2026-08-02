package me.ramos.unigate.iam.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.util.AntPathMatcher
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.reflect.Method

/**
 * **예외 목록을 테스트로 고정한다** (Phase 9g 후속).
 *
 * ## 왜 필요한가 — default-deny 는 예외가 늘면 조용히 무너진다
 * [IamSecurityConfig] 는 `anyRequest().authenticated()` 로 기본 차단이고, 공개 경로만 예외다.
 * 이 구조의 약점은 **예외를 추가하는 비용이 한 줄**이라는 것이다. permitAll 목록에 패턴 하나를
 * 넓게 적으면(`/iam` 하위 전체 같은) 관리 API 까지 통째로 공개되는데, 리뷰에서 그 한 줄은 가볍게 지나간다.
 *
 * 그래서 **정책(상수)과 실제 엔드포인트를 대조**한다. 새 엔드포인트가 공개 패턴에 걸리면
 * 여기서 실패하고, 실패 메시지가 "이걸 정말 공개할 것인가"를 묻게 된다.
 *
 * ## 왜 요청을 보내지 않고 정적으로 보나
 * 실제 호출로 확인하려면 모든 컨트롤러와 그 의존을 띄워야 하는데, 그 목록 자체가
 * [IamSecurityBoundaryTest] 처럼 **손으로 유지하는 목록**이 된다 — 새 컨트롤러를 빠뜨리면
 * 테스트는 통과하면서 커버리지만 사라진다. 클래스패스 스캔은 그 누락이 성립하지 않는다.
 *
 * ⚠️ 그 대가로 **런타임 동작은 검증하지 않는다.** 필터 체인이 실제로 401/403 을 내는지는
 * [IamSecurityBoundaryTest] 가 본다. 둘은 겨냥하는 실패가 다르다 —
 * 이쪽은 "정책이 의도와 다른 것", 저쪽은 "정책이 동작하지 않는 것".
 *
 * 계층: L1(단위). 스프링 컨텍스트를 띄우지 않는다.
 */
class IamAuthorizationCoverageTest :
  BehaviorSpec({
    val matcher = AntPathMatcher()

    /**
     * `adapter/iamIn` 의 모든 `@RestController` 를 클래스패스에서 찾는다.
     *
     * ## ⚠️ 프로파일을 켜지 않으면 조용히 빠진다 (겪은 실패)
     * 스캐너는 `@Conditional` 계열을 **평가한다.** `@Profile("local")` 컨트롤러는 프로파일이
     * 꺼져 있으면 후보에서 제외되고, 테스트는 **그 엔드포인트가 없는 것처럼 통과**한다.
     * 커버리지 테스트가 대상을 조용히 놓치는 것은 커버리지가 없는 것보다 나쁘다 — 있다고 믿게 된다.
     *
     * 그래서 **모든 프로파일을 켠 상태**로 스캔한다. 여기서 보는 것은 "어느 환경에서든 존재할 수
     * 있는 엔드포인트 전부"여야 한다.
     */
    fun controllerClasses(): List<Class<*>> {
      val scanner = ClassPathScanningCandidateComponentProvider(false)
      scanner.environment =
        StandardEnvironment().apply {
          setActiveProfiles(*PROFILES_THAT_CAN_EXPOSE_ENDPOINTS)
          // ⚠️ 프로파일만으로는 부족하다 — `@ConditionalOnProperty` 컨트롤러는 **속성**으로 갈린다.
          // 스캐너는 `@Conditional` 계열을 전부 평가하므로, 속성이 없으면 그 엔드포인트가
          // 조용히 후보에서 빠지고 테스트는 통과한다. 프로파일과 같은 함정이 한 겹 더 있는 셈이다.
          propertySources.addFirst(
            MapPropertySource("probe-switches", PROPERTIES_THAT_CAN_EXPOSE_ENDPOINTS),
          )
        }
      scanner.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
      return scanner
        .findCandidateComponents(CONTROLLER_PACKAGE)
        .mapNotNull { it: BeanDefinition -> it.beanClassName }
        .map { Class.forName(it) }
        .sortedBy { it.name }
    }

    /** 클래스 레벨 + 메서드 레벨 매핑을 합쳐 실제 경로를 만든다. */
    fun mappingsOf(controller: Class<*>): List<Endpoint> {
      val classPaths =
        AnnotatedElementUtils
          .findMergedAnnotation(controller, RequestMapping::class.java)
          ?.path
          ?.toList()
          ?.ifEmpty { listOf("") }
          ?: listOf("")

      return controller.declaredMethods.flatMap { method: Method ->
        val mapping =
          AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping::class.java)
            ?: return@flatMap emptyList()
        val methodPaths = mapping.path.toList().ifEmpty { listOf("") }
        classPaths.flatMap { base ->
          methodPaths.map { sub ->
            Endpoint(
              controller = controller.simpleName,
              path = ("$base$sub").ifEmpty { "/" },
            )
          }
        }
      }
    }

    val endpoints = controllerClasses().flatMap { mappingsOf(it) }

    fun matchesAny(
      patterns: Array<String>,
      path: String,
    ): Boolean = patterns.any { matcher.match(it, path) }

    given("IAM 의 모든 REST 엔드포인트") {
      `when`("클래스패스에서 수집하면") {
        then("하나 이상 발견된다 — 0개면 스캔이 깨진 것이고 이 테스트 전체가 무의미해진다") {
          // 스캔 실패는 "전부 통과"로 위장된다. 그 위장을 여기서 깬다.
          (endpoints.size >= MINIMUM_EXPECTED_ENDPOINTS) shouldBe true
        }
      }

      `when`("공개(permitAll) 패턴과 대조하면") {
        val publicEndpoints =
          endpoints
            .filter {
              matchesAny(IamSecurityConfig.PUBLIC_PATHS, it.path) ||
                matchesAny(IamSecurityConfig.LOCAL_ONLY_PUBLIC_PATHS, it.path)
            }.map { it.path }
            .distinct()

        then("의도한 것만 공개다 — 새 엔드포인트가 공개 패턴에 걸리면 여기서 깨진다") {
          // ⚠️ 이 목록을 고칠 때는 "왜 인증 없이 열려야 하는가" 를 커밋 메시지에 남긴다.
          // 목록을 늘리는 것이 곧 공격 표면을 늘리는 것이다.
          publicEndpoints shouldContainExactlyInAnyOrder EXPECTED_PUBLIC_ENDPOINTS
        }
      }

      `when`("관리(admin) 경로와 대조하면") {
        // 이름에 Admin 이 들어간 컨트롤러는 남의 자원을 다룬다는 뜻이다. 그런 컨트롤러가
        // 접두사 밖에 경로를 두면 `hasAuthority` 규칙이 적용되지 않는다 — 인증만 하면 통과한다.
        val adminControllerEndpointsOutsidePrefix =
          endpoints
            .filter { it.controller.contains(ADMIN_CONTROLLER_MARKER) }
            .filterNot { matcher.match(IamSecurityConfig.ADMIN_PATHS, it.path) }

        then("관리 컨트롤러의 경로는 전부 관리 접두사 아래에 있다") {
          adminControllerEndpointsOutsidePrefix.shouldBeEmpty()
        }
      }

      `when`("공개 목록의 각 패턴을 거꾸로 보면") {
        // 죽은 예외 = 대응하는 엔드포인트가 없는 permitAll 패턴. 지금은 무해하지만, 나중에
        // 그 경로에 엔드포인트가 생기면 **무인증으로 태어난다.** 아무도 그 사실을 모른다.
        val actuatorOwned = { pattern: String -> pattern.startsWith(ACTUATOR_PREFIX) }
        val orphanPatterns =
          IamSecurityConfig.PUBLIC_PATHS
            .filterNot(actuatorOwned)
            .filterNot { pattern -> endpoints.any { matcher.match(pattern, it.path) } }

        then("대응하는 엔드포인트가 없는 공개 패턴은 없다(actuator 제외)") {
          orphanPatterns.shouldBeEmpty()
        }
      }
    }
  }) {
  data class Endpoint(
    val controller: String,
    val path: String,
  )

  private companion object {
    const val CONTROLLER_PACKAGE = "me.ramos.unigate.iam.adapter.iamIn"

    /** 스캔이 깨졌는데 통과하는 것을 막기 위한 하한선. 엄밀한 수가 아니라 **0 방지**다. */
    const val MINIMUM_EXPECTED_ENDPOINTS = 5

    /**
     * 엔드포인트를 노출할 수 있는 프로파일 전부. 하나라도 빠지면 그 프로파일 전용 컨트롤러가
     * 커버리지에서 **조용히 사라진다**(스캐너가 `@Profile` 을 평가하기 때문 — 위 KDoc).
     */
    val PROFILES_THAT_CAN_EXPOSE_ENDPOINTS = arrayOf("local", "alpha")

    /**
     * 엔드포인트를 노출할 수 있는 **설정 스위치** 전부.
     *
     * [PROFILES_THAT_CAN_EXPOSE_ENDPOINTS] 와 같은 목적이고 축만 다르다 — 어떤 환경에서든
     * 존재할 수 있는 엔드포인트는 여기서 **전부 보여야** 한다. 스위치를 새로 만들고 이 목록에
     * 넣는 것을 잊으면, 그 엔드포인트는 커버리지에서 사라지면서 테스트는 초록으로 남는다.
     */
    val PROPERTIES_THAT_CAN_EXPOSE_ENDPOINTS =
      mapOf<String, Any>(
        // me.ramos.unigate.iam.adapter.iamIn.CallerProbeController
        "unigate.iam.probe.caller.enabled" to "true",
      )

    const val ADMIN_CONTROLLER_MARKER = "Admin"
    const val ACTUATOR_PREFIX = "/actuator"

    /**
     * **인증 없이 열려도 되는 엔드포인트의 전부.**
     *
     * - `/iam/register` — 가입은 토큰이 없는 상태다. 남용 방어는 GW rate limit.
     * - `/debug` 하위(local) — [me.ramos.unigate.iam.adapter.iamIn.ThreadProbeController].
     *   local 프로파일에서만 라우트가 존재한다.
     */
    val EXPECTED_PUBLIC_ENDPOINTS =
      listOf(
        "/iam/register",
        "/debug/thread",
      )
  }
}
