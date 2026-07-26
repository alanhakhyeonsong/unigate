package me.ramos.unigate.iam.adapter.iamIn

import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * local 전용 진단 프로브 — **Virtual Thread 가 실제로 켜졌는지 눈으로 확인**하기 위한 것.
 *
 * 게이트웨이의 `SessionProbeConfig`·`AuthProbeConfig` 와 같은 성격이다(기능이 아니라 학습·검증 목적).
 *
 * ## 왜 필요한가
 * `spring.threads.virtual.enabled=true` 는 **설정만 보고는 실제 적용 여부를 알 수 없다.** 잘못 적용돼도
 * 애플리케이션은 정상 기동하고 요청도 처리되며, 다만 **부하가 걸려야** 차이가 드러난다. 그때 확인하는 것은
 * 너무 늦다. `Thread.isVirtual()`(JDK 21)로 지금 즉시 확인한다.
 *
 * ## 읽는 법 — **이름이 아니라 `virtual` 필드를 봐야 한다**
 * 실제 측정값이 그 이유를 보여준다:
 *
 * ```
 * threadName      = "tomcat-handler-1"
 * virtual         = true
 * threadToString  = "VirtualThread[#61,tomcat-handler-1]/runnable@ForkJoinPool-1-worker-1"
 * ```
 *
 * VT 라고 이름이 비어 있지 않다 — **Tomcat 이 `tomcat-handler-N` 이라는 이름을 붙인다.** 플랫폼 스레드
 * 시절 이름(`http-nio-8090-exec-N`)과 다를 뿐 "이름이 있다"는 점은 같아서, **이름만으로는 구분할 수 없다.**
 * 그래서 `isVirtual()` 이 필요하고, 이 프로브가 존재하는 이유이기도 하다.
 *
 * `toString()` 의 `/runnable@ForkJoinPool-1-worker-1` 부분이 **캐리어 스레드**다. VT 는 블로킹 시
 * 이 캐리어를 반납하고, 재개할 때 다른 캐리어에 올라탈 수 있다.
 *
 * `@Profile("local")` 이라 다른 프로파일에는 이 라우트 자체가 존재하지 않는다.
 */
@RestController
@Profile("local")
class ThreadProbeController {
  @GetMapping("/debug/thread")
  fun currentThreadInfo(): Map<String, Any> {
    val thread = Thread.currentThread()
    return mapOf(
      // VT 는 기본적으로 이름이 빈 문자열이다. 그 자체가 단서다.
      "threadName" to thread.name.ifEmpty { "(unnamed — virtual thread 의 기본값)" },
      "virtual" to thread.isVirtual,
      "threadToString" to thread.toString(),
      "javaVersion" to Runtime.version().toString(),
    )
  }
}
