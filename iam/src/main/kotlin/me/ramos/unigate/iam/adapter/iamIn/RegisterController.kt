package me.ramos.unigate.iam.adapter.iamIn

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import me.ramos.unigate.iam.application.user.dto.RegisterUserCommand
import me.ramos.unigate.iam.application.user.port.inbound.RegisterUserInPort
import me.ramos.unigate.iam.application.user.service.EmailAlreadyRegisteredException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 가입 API — **공개 라우트**(인증 없음).
 *
 * ## 왜 인증이 없나
 * 가입은 사용자 토큰이 아예 없는 상태다(`IAM_PLATFORM_DECISION.md` D4 보강). 그래서 IAM 라우트를
 * 공개/인증 둘로 나누고, 이 엔드포인트는 공개 쪽에 둔다.
 *
 * ## ⚠️ rate limit 은 여기가 아니라 게이트웨이에서 건다
 * 공개 가입 엔드포인트는 **스팸 가입·계정 열거**의 표적이다. 다만 그 방어는 게이트웨이의 몫이다 —
 * Phase 3 에서 Redis 토큰버킷 rate limiter 를 이미 갖췄고, IAM 은 GW 뒤에 있다. IAM 에 또 다른
 * rate limiter 를 두면 정책이 두 곳으로 갈라진다.
 *
 * **P8f(GW 프록시 라우트)에서 이 경로에 강한 limit 을 걸어야 한다.** 그때까지 이 엔드포인트는
 * 보호되지 않은 상태이므로 외부에 노출하면 안 된다.
 *
 * ## 202 가 아니라 201 인 이유
 * outbox 라 Keycloak 반영은 아직이지만, **IAM 입장에서 프로필 리소스는 실제로 생성됐다.**
 * 미완성인 것은 신원 연결이고 그건 `onboardingState` 로 드러난다. 202 를 주면 "아무것도 안 만들어졌다"
 * 는 오해를 준다.
 */
@RestController
@RequestMapping("/iam")
class RegisterController(
  private val registerUserInPort: RegisterUserInPort,
) {
  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  fun register(
    @Valid @RequestBody request: RegisterRequest,
  ): RegisterResponse {
    val result =
      registerUserInPort.register(
        RegisterUserCommand(
          email = request.email,
          displayName = request.displayName,
          firstName = request.firstName,
          lastName = request.lastName,
          locale = request.locale,
          tosVersion = request.tosVersion,
        ),
      )
    return RegisterResponse(
      email = result.email,
      onboardingState = result.onboardingState,
      // 가입 직후에는 항상 null 이다. FE 는 이 값이 아니라 onboardingState 를 보고 판단해야 한다.
      userRef = result.userRef,
    )
  }

  /**
   * 중복 가입 → 409.
   *
   * 계정 열거(account enumeration) 관점에서는 "이 이메일이 존재한다" 를 알려주는 셈이라 논쟁적이다.
   * 다만 가입 UX 상 중복을 알려주지 않으면 사용자가 막힌다. **rate limit 으로 대량 탐색을 막는 것**이
   * 실질적 방어이며, 그건 게이트웨이 몫이다(위 참조).
   */
  @ExceptionHandler(EmailAlreadyRegisteredException::class)
  @ResponseStatus(HttpStatus.CONFLICT)
  fun handleDuplicate(e: EmailAlreadyRegisteredException): ProblemDetail = duplicateProblem()

  /**
   * **경합 방어** — 사전 조회를 통과한 두 요청이 동시에 INSERT 하면 DB unique 제약이 막는다.
   *
   * `RegisterUserService` 가 저장 전에 이메일을 조회하지만, 그 조회와 INSERT 사이에 다른 요청이
   * 끼어들 수 있다(check-then-act). 그때 터지는 것은 `DataIntegrityViolationException` 이고,
   * 처리하지 않으면 **500** 이 나간다 — 사용자 입장에서는 서버 오류로 보이지만 실제로는 중복 가입이다.
   *
   * ⚠️ 이 경합은 **재현이 어렵다.** 4개 스레드로 동시 요청해본 결과 `[201, 409, 409, 409]` 로
   * 사전 조회가 모두 걸렸고 unique 위반까지 가지 않았다. 즉 이 핸들러가 실제로 타는 것을 확인하지는
   * 못했다. 그래도 두는 이유는 **재현이 안 된다는 것이 안전하다는 뜻은 아니기** 때문이다 —
   * 부하가 높거나 인스턴스가 여럿이면 확률이 올라간다.
   */
  @ExceptionHandler(DataIntegrityViolationException::class)
  @ResponseStatus(HttpStatus.CONFLICT)
  fun handleRaceCondition(e: DataIntegrityViolationException): ProblemDetail = duplicateProblem()

  private fun duplicateProblem(): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.").apply {
      title = "Email Already Registered"
      setProperty("reasonCode", "email_already_registered")
    }
}

data class RegisterRequest(
  @field:Email(message = "이메일 형식이 아닙니다")
  @field:NotBlank(message = "이메일은 필수입니다")
  @field:Size(max = 255)
  val email: String,
  @field:NotBlank(message = "표시 이름은 필수입니다")
  @field:Size(max = 255)
  val displayName: String,
  @field:NotBlank(message = "이름은 필수입니다")
  @field:Size(max = 100)
  val firstName: String,
  @field:NotBlank(message = "성은 필수입니다")
  @field:Size(max = 100)
  val lastName: String,
  @field:Size(max = 16)
  val locale: String? = null,
  @field:Size(max = 32)
  val tosVersion: String? = null,
)

data class RegisterResponse(
  val email: String,
  val onboardingState: String,
  val userRef: String?,
)
