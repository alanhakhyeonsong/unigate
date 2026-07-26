package me.ramos.unigate.iam.application.user.service

import me.ramos.unigate.iam.application.user.dto.MyProfileResult
import me.ramos.unigate.iam.application.user.policy.ConsentPolicy
import me.ramos.unigate.iam.application.user.port.inbound.GetMyProfileInPort
import me.ramos.unigate.iam.application.user.port.outbound.UserProfileRepositoryPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 프로필 조회 유스케이스.
 *
 * ## 인가가 코드에 안 보이는 것이 의도다
 * "이 프로필이 호출자 것인가" 를 검사하는 `if` 가 없다. 검사할 필요가 없기 때문이다 —
 * **조회 키 자체가 호출자의 토큰에서 온다.** 남의 프로필을 지목할 수단이 API 에 존재하지 않으므로
 * IDOR(Insecure Direct Object Reference)이 성립할 자리가 없다.
 *
 * 반대로 `GET /iam/profile/{id}` 처럼 설계했다면 매 요청마다 "id 의 주인 == 토큰의 sub" 를 검사해야
 * 하고, 그 검사를 한 번 빠뜨리는 것으로 전체가 뚫린다. **검사를 잘 하는 것보다 검사가 필요 없게
 * 만드는 편이 안전하다.**
 *
 * 관리자가 남의 프로필을 보는 유스케이스가 생기면 이 클래스를 확장하지 말고 `/iam/admin` 쪽에
 * 별도로 만든다. 거기서는 인가 검사가 **명시적으로 보이는** 편이 옳다.
 */
@Service
class GetMyProfileService(
  private val userProfileRepository: UserProfileRepositoryPort,
  private val consentPolicy: ConsentPolicy,
) : GetMyProfileInPort {
  /**
   * `readOnly = true` — 쓰기가 없다는 것을 선언으로 드러내고, JPA 가 더티체킹을 위한 스냅샷을
   * 뜨지 않게 한다. 조회만 하는데 영속성 컨텍스트가 변경 감지를 준비하는 낭비를 줄인다.
   */
  @Transactional(readOnly = true)
  override fun get(userRef: String): MyProfileResult =
    userProfileRepository.loadCaller(userRef).toMyProfileResult(consentPolicy)
}
