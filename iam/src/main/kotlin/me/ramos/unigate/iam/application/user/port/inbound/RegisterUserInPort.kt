package me.ramos.unigate.iam.application.user.port.inbound

import me.ramos.unigate.iam.application.user.dto.RegisterUserCommand
import me.ramos.unigate.iam.application.user.dto.RegisterUserResult

/** 가입 유스케이스 진입점. 웹 어댑터가 이 인터페이스에만 의존한다. */
interface RegisterUserInPort {
  fun register(command: RegisterUserCommand): RegisterUserResult
}
