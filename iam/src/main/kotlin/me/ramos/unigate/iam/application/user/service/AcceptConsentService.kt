package me.ramos.unigate.iam.application.user.service

import me.ramos.unigate.iam.application.user.dto.AcceptConsentCommand
import me.ramos.unigate.iam.application.user.dto.MyProfileResult
import me.ramos.unigate.iam.application.user.policy.ConsentPolicy
import me.ramos.unigate.iam.application.user.port.inbound.AcceptConsentInPort
import me.ramos.unigate.iam.application.user.port.outbound.UserProfileRepositoryPort
import me.ramos.unigate.iam.domain.user.vo.ConsentRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 약관 동의 유스케이스.
 *
 * ## 왜 별도 엔드포인트인가 (수정에 끼워 넣지 않는 이유)
 * 동의는 필드 갱신이 아니라 **시점이 있는 사건**이다. "언제 어느 버전에 동의했는가" 가 기록의 본체이고,
 * 그 시각은 서버가 찍어야 한다. `PATCH /iam/profile` 에 `tosVersion` 을 끼워 넣으면 표시 이름을
 * 바꾸는 요청이 동의 기록을 덮어쓸 수 있게 된다.
 *
 * ## 버전을 서버가 대조하는 이유
 * 클라이언트가 보낸 문자열을 그대로 저장하면, `{"tosVersion":"v99"}` 를 보내는 것만으로
 * "최신 약관에 동의함" 상태를 만들 수 있다. 동의 기록이 법적 근거로 쓰이는 데이터라는 점에서
 * 이건 단순한 입력 검증이 아니라 **기록의 신뢰성 문제**다.
 *
 * 그렇다고 클라이언트가 버전을 아예 안 보내게 하면 안 된다. 그러면 "사용자가 화면에서 본 약관" 과
 * "서버가 기록한 약관" 이 다를 수 있다(보는 사이에 개정). 보낸 값을 **대조**하는 것이 두 요구를
 * 동시에 만족시킨다.
 */
@Service
class AcceptConsentService(
  private val userProfileRepository: UserProfileRepositoryPort,
  private val consentPolicy: ConsentPolicy,
  private val clock: Clock,
) : AcceptConsentInPort {
  @Transactional
  override fun accept(command: AcceptConsentCommand): MyProfileResult {
    if (command.tosVersion != consentPolicy.currentTosVersion) {
      throw ConsentVersionMismatchException(
        requested = command.tosVersion,
        current = consentPolicy.currentTosVersion,
      )
    }

    val profile = userProfileRepository.loadCaller(command.userRef)
    profile.acceptConsent(
      ConsentRecord(
        tosVersion = consentPolicy.currentTosVersion,
        // 동의 시각은 **서버 시계**로 찍는다. 클라이언트가 보낸 시각을 믿으면 기록을 소급 조작할 수 있다.
        acceptedAt = Instant.now(clock),
      ),
    )
    return userProfileRepository.save(profile).toMyProfileResult(consentPolicy)
  }
}
