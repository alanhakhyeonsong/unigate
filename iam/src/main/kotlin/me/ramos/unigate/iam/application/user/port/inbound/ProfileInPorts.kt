package me.ramos.unigate.iam.application.user.port.inbound

import me.ramos.unigate.iam.application.user.dto.AcceptConsentCommand
import me.ramos.unigate.iam.application.user.dto.MyProfileResult
import me.ramos.unigate.iam.application.user.dto.UpdateMyProfileCommand

/**
 * 호출자 본인의 프로필을 조회하는 InPort.
 *
 * 파라미터가 `userRef` 하나뿐이고 그 값의 출처는 **검증된 JWT 의 `sub`** 다. 웹 어댑터가
 * 요청 본문이나 경로에서 읽은 값을 넘기면 인가가 무너지므로, 그 계약을 어댑터 쪽에도 적어둔다.
 */
interface GetMyProfileInPort {
  /**
   * 프로필을 조회한다.
   *
   * @param userRef 호출자의 Keycloak 사용자 참조 (= JWT `sub`)
   * @return 프로필 정보
   * @throws me.ramos.unigate.iam.application.user.service.ProfileNotFoundException
   *   토큰은 유효하나 IAM 에 프로필이 없을 때
   */
  fun get(userRef: String): MyProfileResult
}

/** 호출자 본인의 프로필을 수정하는 InPort. */
interface UpdateMyProfileInPort {
  /**
   * 표시 이름·locale 을 부분 갱신한다. `null` 필드는 **변경하지 않는다.**
   *
   * email 은 여기서 바꿀 수 없다 — 신원 필드라 Keycloak 반영이 필요하고, 그건 outbox 를 타야 하는
   * 별도 유스케이스다(`IAM_PLATFORM_DECISION.md` §10).
   *
   * @param command 대상은 `command.userRef` 로만 정해진다
   * @return 갱신된 프로필
   */
  fun update(command: UpdateMyProfileCommand): MyProfileResult
}

/** 약관 동의를 기록하는 InPort. */
interface AcceptConsentInPort {
  /**
   * 약관에 동의한다. 재동의(개정판)도 같은 경로로 덮어쓴다.
   *
   * 요청의 `tosVersion` 이 **서버가 아는 현재 버전과 다르면 거부**한다. 그렇게 하지 않으면
   * 클라이언트가 임의 문자열을 보내 "최신 약관에 동의한 상태" 를 만들 수 있고, 동의 기록이
   * 법적 근거로서의 의미를 잃는다.
   *
   * @return 갱신된 프로필(동의 현황 포함)
   * @throws me.ramos.unigate.iam.application.user.service.ConsentVersionMismatchException
   */
  fun accept(command: AcceptConsentCommand): MyProfileResult
}
