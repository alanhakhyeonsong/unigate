package me.ramos.unigate.iam.adapter.iamIn

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import me.ramos.unigate.iam.application.user.dto.AcceptConsentCommand
import me.ramos.unigate.iam.application.user.dto.ChangeMyEmailCommand
import me.ramos.unigate.iam.application.user.dto.MyProfileResult
import me.ramos.unigate.iam.application.user.dto.UpdateMyProfileCommand
import me.ramos.unigate.iam.application.user.port.inbound.AcceptConsentInPort
import me.ramos.unigate.iam.application.user.port.inbound.ChangeMyEmailInPort
import me.ramos.unigate.iam.application.user.port.inbound.GetMyProfileInPort
import me.ramos.unigate.iam.application.user.port.inbound.UpdateMyProfileInPort
import me.ramos.unigate.iam.application.user.port.outbound.ProfileConcurrentlyModifiedException
import me.ramos.unigate.iam.application.user.service.ConsentVersionMismatchException
import me.ramos.unigate.iam.application.user.service.EmailAlreadyRegisteredException
import me.ramos.unigate.iam.application.user.service.ProfileNotFoundException
import me.ramos.unigate.iam.domain.user.exception.UserProfileDomainException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 프로필 API — **인증 라우트**. 게이트웨이가 relay 한 사용자 JWT 로 호출자를 식별한다.
 *
 * ## 대상 자원을 경로에 두지 않는다
 * `GET /iam/profile/{id}` 가 아니라 `GET /iam/profile` 이다. 대상은 **오직 토큰의 `sub`** 로 정해진다.
 * 그래서 "남의 id 를 넣어 본다" 는 공격이 성립할 자리가 없다(IDOR 불가). 인가 검사 코드가 안 보이는
 * 것이 허술한 게 아니라, **검사할 것이 없게 설계한 결과**다.
 *
 * ## 이 컨트롤러가 GW 없이도 안전한 이유
 * `IamSecurityConfig` 가 `/iam/register` 외 전부를 authenticated 로 잡는다. 게이트웨이를 우회해
 * `:8090` 에 직접 붙어도 토큰 없이는 여기 도달하지 못한다.
 *
 * ## 토큰이 두 종류라는 점(반복해도 지나치지 않다)
 * 여기서 쓰는 것은 **호출자 신원**이다. Keycloak 을 고칠 권한은 이 토큰에 없다 — email 변경 같은
 * 신원 필드 수정을 이 API 가 하지 않는 이유이기도 하다(`UpdateMyProfileService` KDoc).
 */
@RestController
@RequestMapping("/iam/profile")
class ProfileController(
  private val getMyProfileInPort: GetMyProfileInPort,
  private val updateMyProfileInPort: UpdateMyProfileInPort,
  private val acceptConsentInPort: AcceptConsentInPort,
  private val changeMyEmailInPort: ChangeMyEmailInPort,
) {
  @GetMapping
  fun getMyProfile(authentication: JwtAuthenticationToken): ProfileResponse =
    getMyProfileInPort.get(authentication.callerRef()).toResponse()

  /**
   * 부분 갱신. **PUT 이 아니라 PATCH 인 이유**: 클라이언트가 표시 이름만 바꾸려 할 때 locale 까지
   * 실어 보내게 하면, 그 사이 다른 탭에서 바뀐 locale 을 오래된 값으로 덮어쓴다.
   *
   * 본문에서 생략한(혹은 null 인) 필드는 **변경하지 않는다.** 두 필드 모두 도메인에서 blank 를
   * 금지하므로 "빈 값으로 지우기" 는 애초에 없는 연산이다.
   */
  @PatchMapping
  fun updateMyProfile(
    authentication: JwtAuthenticationToken,
    @Valid @RequestBody request: UpdateProfileRequest,
  ): ProfileResponse =
    updateMyProfileInPort
      .update(
        UpdateMyProfileCommand(
          userRef = authentication.callerRef(),
          displayName = request.displayName,
          locale = request.locale,
        ),
      ).toResponse()

  /**
   * 약관 동의. 갱신이 아니라 **사건 기록**이라 PATCH 가 아닌 POST 다.
   *
   * 멱등하다 — 같은 버전에 두 번 동의하면 시각만 갱신된다. 재시도가 안전해야 하므로 그렇게 둔다.
   */
  @PostMapping("/consent")
  fun acceptConsent(
    authentication: JwtAuthenticationToken,
    @Valid @RequestBody request: AcceptConsentRequest,
  ): ProfileResponse =
    acceptConsentInPort
      .accept(
        AcceptConsentCommand(
          userRef = authentication.callerRef(),
          tosVersion = request.tosVersion,
        ),
      ).toResponse()

  /**
   * 이메일 변경 **접수**. 202 인 것이 이 API 의 요점이다.
   *
   * 200 을 주면 "바뀌었다" 는 뜻이 되는데, 이 시점에 Keycloak 은 아직 옛 주소를 안다.
   * 실제 반영은 워커가 하고 실패하면 취소된다(보상). 그래서 **접수됐다** 는 뜻의 202 를 주고,
   * 본문에 확정 값과 대기 값을 함께 담아 클라이언트가 상태를 정확히 표현하게 한다.
   *
   * PATCH(부분 갱신)가 아니라 별도 경로인 이유: 이메일은 다른 프로필 필드와 **처리 경로가 다르다**.
   * 같은 요청으로 묶으면 "displayName 은 즉시, email 은 나중" 이라는 두 시맨틱이 한 응답에 섞인다.
   */
  @PostMapping("/email-change")
  @ResponseStatus(HttpStatus.ACCEPTED)
  fun changeMyEmail(
    authentication: JwtAuthenticationToken,
    @Valid @RequestBody request: ChangeEmailRequest,
  ): EmailChangeResponse =
    changeMyEmailInPort
      .requestChange(
        ChangeMyEmailCommand(
          userRef = authentication.callerRef(),
          newEmail = request.newEmail,
        ),
      ).let { EmailChangeResponse(email = it.email, pendingEmail = it.pendingEmail) }

  /**
   * 프로필 없음 → 404.
   *
   * 401 이 아닌 이유: **인증은 성공했다.** 토큰은 유효하고 호출자가 누구인지도 안다. 없는 것은
   * IAM 쪽 자원이다. 여기서 401 을 주면 클라이언트가 재로그인을 시도하고, 다시 성공하고, 또 404 대신
   * 401 을 받아 **무한 로그인 루프**가 된다.
   */
  @ExceptionHandler(ProfileNotFoundException::class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  fun handleNotFound(e: ProfileNotFoundException): ProblemDetail =
    ProblemDetail
      .forStatusAndDetail(
        HttpStatus.NOT_FOUND,
        "이 계정에 연결된 프로필이 없습니다. 가입 절차를 완료해야 합니다.",
      ).apply {
        title = "Profile Not Found"
        setProperty("reasonCode", "profile_not_found")
      }

  /**
   * 이메일 변경이 **거절**된 경우들.
   *
   * 세 가지 사유(다른 사람이 사용 중 · 이미 진행 중 · 현재와 동일)를 전부 **409** 로 준다.
   * 셋 다 "요청 형식은 맞는데 지금 상태에서는 할 수 없다" 이고, 클라이언트가 구분해야 할 것은
   * 상태코드가 아니라 `reasonCode` 다. 상태코드를 쪼개면 FE 가 코드마다 분기하게 되고,
   * 사유가 하나 늘 때마다 그 분기가 또 늘어난다.
   */
  @ExceptionHandler(EmailAlreadyRegisteredException::class)
  @ResponseStatus(HttpStatus.CONFLICT)
  fun handleEmailInUse(e: EmailAlreadyRegisteredException): ProblemDetail =
    ProblemDetail
      .forStatusAndDetail(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다.")
      .apply {
        title = "Email Already In Use"
        setProperty("reasonCode", "email_already_in_use")
      }

  @ExceptionHandler(UserProfileDomainException.EmailChangeInProgress::class)
  @ResponseStatus(HttpStatus.CONFLICT)
  fun handleEmailChangeInProgress(e: UserProfileDomainException.EmailChangeInProgress): ProblemDetail =
    ProblemDetail
      .forStatusAndDetail(
        HttpStatus.CONFLICT,
        "이미 처리 중인 이메일 변경이 있습니다. 반영이 끝난 뒤 다시 시도해 주세요.",
      ).apply {
        title = "Email Change In Progress"
        setProperty("reasonCode", "email_change_in_progress")
      }

  @ExceptionHandler(UserProfileDomainException.EmailUnchanged::class)
  @ResponseStatus(HttpStatus.CONFLICT)
  fun handleEmailUnchanged(e: UserProfileDomainException.EmailUnchanged): ProblemDetail =
    ProblemDetail
      .forStatusAndDetail(HttpStatus.CONFLICT, "현재 이메일과 동일합니다.")
      .apply {
        title = "Email Unchanged"
        setProperty("reasonCode", "email_unchanged")
      }

  /**
   * 아직 Keycloak 신원이 없는 프로필의 이메일 변경 시도.
   *
   * **422 다.** 요청은 유효하고 충돌도 아니지만, 이 자원이 그 연산을 받을 수 있는 상태가 아니다.
   * 해법도 다르다 — 다시 시도하는 게 아니라 가입을 마쳐야 한다.
   */
  @ExceptionHandler(UserProfileDomainException.IdentityNotReady::class)
  @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
  fun handleIdentityNotReady(e: UserProfileDomainException.IdentityNotReady): ProblemDetail =
    ProblemDetail
      .forStatusAndDetail(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "가입이 아직 완료되지 않아 이메일을 변경할 수 없습니다.",
      ).apply {
        title = "Identity Not Ready"
        setProperty("reasonCode", "identity_not_ready")
      }

  /**
   * 약관 버전 불일치 → 409.
   *
   * 400 이 아니라 409 인 이유: 요청 자체는 형식·의미 모두 올바르다. 문제는 **서버의 현재 상태와
   * 충돌**한다는 것이고(그 사이 약관이 개정됨), 클라이언트가 최신 버전을 받아 다시 시도하면 성공한다.
   * `currentTosVersion` 을 응답에 실어 그 재시도를 한 번의 왕복으로 끝낼 수 있게 한다.
   */
  @ExceptionHandler(ConsentVersionMismatchException::class)
  @ResponseStatus(HttpStatus.CONFLICT)
  fun handleConsentMismatch(e: ConsentVersionMismatchException): ProblemDetail =
    ProblemDetail
      .forStatusAndDetail(
        HttpStatus.CONFLICT,
        "약관이 개정되었습니다. 최신 약관을 확인한 뒤 다시 동의해 주세요.",
      ).apply {
        title = "Consent Version Mismatch"
        setProperty("reasonCode", "consent_version_mismatch")
        setProperty("currentTosVersion", e.current)
      }

  /**
   * 동시 수정 충돌 → 409.
   *
   * ## 서버가 재시도하지 않고 사용자에게 돌려주는 이유
   * 여기서 다시 읽어 다시 적용하면 **먼저 저장된 남의 변경을 조용히 덮는다** — 낙관적 락으로 막으려던
   * lost update 를 서버가 대신 해주는 꼴이다. 무엇을 살릴지는 사람이 정해야 한다.
   *
   * 다른 409 들(`email_already_in_use` 등)과 상태코드가 같은 것은 의도적이다. 클라이언트가 분기할
   * 근거는 `reasonCode` 이고(이 컨트롤러의 다른 핸들러들과 같은 판단), 이 사유만은 **그대로 다시
   * 보내면 성공할 수 있다**는 점에서 재시도 안내가 적절하다.
   */
  @ExceptionHandler(ProfileConcurrentlyModifiedException::class)
  @ResponseStatus(HttpStatus.CONFLICT)
  fun handleConcurrentModification(e: ProfileConcurrentlyModifiedException): ProblemDetail =
    ProblemDetail
      .forStatusAndDetail(
        HttpStatus.CONFLICT,
        "다른 요청이 먼저 프로필을 변경했습니다. 최신 정보를 확인한 뒤 다시 시도해 주세요.",
      ).apply {
        title = "Profile Modified Concurrently"
        setProperty("reasonCode", "profile_modified_concurrently")
      }

  /**
   * 검증된 JWT 의 `sub` — 호출자의 Keycloak 사용자 참조.
   *
   * ⚠️ **이 값의 출처가 바뀌면 인가가 무너진다.** 요청 본문·헤더·쿼리에서 읽은 사용자 식별자를
   * 여기 대신 넣는 순간, 누구나 남의 프로필을 지목할 수 있게 된다. `Authentication` 은
   * `IamSecurityConfig` 의 디코더가 서명·`iss`·`aud` 를 검증한 뒤에만 채워진다.
   */
  private fun JwtAuthenticationToken.callerRef(): String = token.subject

  private fun MyProfileResult.toResponse(): ProfileResponse =
    ProfileResponse(
      email = email,
      pendingEmail = pendingEmail,
      displayName = displayName,
      locale = locale,
      onboardingState = onboardingState,
      userRef = userRef,
      consent =
        consent?.let {
          ConsentResponse(tosVersion = it.tosVersion, acceptedAt = it.acceptedAt, valid = it.valid)
        },
    )
}

/**
 * 부분 갱신 요청 — 모든 필드가 nullable 이고 `null` 은 "변경 안 함" 이다.
 *
 * `@Size` 는 값이 있을 때만 검사한다(Bean Validation 은 null 을 통과시킨다). blank 검사는
 * 도메인이 하므로 여기서 `@NotBlank` 를 쓰지 않는다 — 쓰면 "필드 생략" 까지 막아버려 부분 갱신이
 * 불가능해진다.
 */
data class UpdateProfileRequest(
  @field:Size(max = 255)
  val displayName: String? = null,
  @field:Size(max = 16)
  val locale: String? = null,
)

data class AcceptConsentRequest(
  @field:NotBlank(message = "약관 버전은 필수입니다")
  @field:Size(max = 32)
  val tosVersion: String,
)

data class ProfileResponse(
  val email: String,
  /** 반영 대기 중인 이메일. 클라이언트가 "변경 처리 중" 을 표시하는 근거다. */
  val pendingEmail: String?,
  val displayName: String,
  val locale: String,
  val onboardingState: String,
  val userRef: String,
  val consent: ConsentResponse?,
)

data class ConsentResponse(
  val tosVersion: String,
  val acceptedAt: Instant,
  /** 현재 약관 기준 유효 여부. **서버가 계산한다** — 클라이언트가 버전을 비교하지 않게. */
  val valid: Boolean,
)

/**
 * 이메일 변경 요청 본문.
 *
 * `@Email` 형식 검증은 여기서 한다 — 도메인은 blank 만 막는다. 형식은 표현 계층의 관심사이고,
 * 무엇이 유효한 주소인지의 최종 판정은 결국 Keycloak(과 실제 메일 발송)이 한다.
 */
data class ChangeEmailRequest(
  @field:NotBlank(message = "이메일은 필수입니다")
  @field:Email(message = "이메일 형식이 올바르지 않습니다")
  val newEmail: String,
)

/** 확정 값과 대기 값을 함께 준다 — 이유는 `EmailChangeResult` KDoc. */
data class EmailChangeResponse(
  val email: String,
  val pendingEmail: String?,
)
