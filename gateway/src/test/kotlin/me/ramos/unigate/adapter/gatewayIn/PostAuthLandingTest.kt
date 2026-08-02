package me.ramos.unigate.adapter.gatewayIn

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import me.ramos.unigate.config.SecurityConfig
import java.net.URI

/**
 * 계층: L1 단위. **로그인·로그아웃 이후 어디로 착지하는가**만 본다.
 *
 * ## 왜 이 테스트가 필요했나
 * alpha 에서 로그인은 성공하는데 FE 가 안 떴다. 착지가 게이트웨이 루트(`/`)였고 거기엔 FE 가 없다.
 * 기동도 정상, 로그도 깨끗, 세션도 정상 발급 — **증상이 "화면이 안 뜬다" 하나뿐**이라 원인을
 * Keycloak 의 `redirectUris` 로 오해하기 쉬웠다.
 *
 * 이 함정은 **로컬에서 절대 재현되지 않는다.** 로컬은 Vite dev proxy 로 same-origin 이라
 * `/` 가 곧 FE 이기 때문이다([26](../../../../../../../../docs/learning/26-bff-spa-integration.md) §5).
 * 그래서 "설정이 없을 때 기본값을 유지한다" 와 "있을 때 그리로 간다" 를 **양쪽 다** 고정한다.
 * 한쪽만 고정하면 회귀를 못 잡는다 — 기본값만 보면 FE 분리 배포가, 설정값만 보면 로컬이 깨진다.
 */
class PostAuthLandingTest :
  BehaviorSpec({
    given("FE 를 분리하지 않은 배포 (unigate.frontend.base-uri 미설정)") {
      `when`("로그인 착지를 정하면") {
        then("건드리지 않는다 — 기본값 `/` 가 유지된다") {
          // null 을 돌려야 setLocation 을 호출하지 않는다. 여기서 빈 URI 를 만들면
          // Location 헤더가 빈 문자열이 되어 브라우저가 제자리로 돌아온다(로그인 루프처럼 보인다).
          postLoginLocation("").shouldBeNull()
        }
      }

      `when`("로그아웃 착지를 정하면") {
        then("게이트웨이 자신(`{baseUrl}/`)으로 간다") {
          SecurityConfig.postLogoutRedirectUri("") shouldBe "{baseUrl}/"
        }
      }

      `when`("미인증 상태로 로그아웃하면") {
        then("`{baseUrl}` 이 아니라 실제 경로 `/` 로 간다") {
          // 이 경로는 Keycloak 을 거치지 않아 **`{baseUrl}` 을 치환해 줄 주체가 없다.**
          // 위 값을 그대로 쓰면 브라우저가 `/%7BbaseUrl%7D/` 로 가서 404 다.
          SecurityConfig.logoutFallbackUri("") shouldBe URI.create("/")
        }
      }
    }

    given("FE 를 다른 호스트에 둔 배포") {
      // 실제 호스트는 커밋 대상에 넣지 않는다(CLAUDE.md §8). 형태만 같으면 검증에 충분하다.
      val feBase = "https://console.example.test/"

      `when`("로그인 착지를 정하면") {
        then("FE 절대 URI 가 된다") {
          postLoginLocation(feBase) shouldBe URI.create(feBase)
        }
      }

      `when`("로그아웃 착지를 정하면") {
        then("같은 FE URI 가 된다 — 로그인과 로그아웃이 갈리면 절반만 어긋난다") {
          // 한쪽만 FE 를 가리키면 "로그인하면 FE, 로그아웃하면 게이트웨이 404" 가 된다.
          SecurityConfig.postLogoutRedirectUri(feBase) shouldBe feBase
        }
      }

      `when`("미인증 상태로 로그아웃하면") {
        then("역시 FE 로 간다 — 인증 여부로 착지가 갈리면 안 된다") {
          // 여기가 갈리면 "세션 만료 뒤 로그아웃 버튼" 만 게이트웨이로 튄다. 사용자에게는
          // 재현 조건이 보이지 않아 "가끔 로그아웃하면 404" 로만 관찰된다.
          SecurityConfig.logoutFallbackUri(feBase) shouldBe URI.create(feBase)
        }
      }
    }

    given("공백만 들어간 설정값") {
      // 환경변수를 `UNIGATE_FRONTEND_BASE_URI=" "` 처럼 넣는 실수. 기동은 정상이라 안 드러난다.
      `when`("착지를 정하면") {
        then("미설정과 똑같이 취급한다") {
          postLoginLocation("   ").shouldBeNull()
          SecurityConfig.postLogoutRedirectUri("   ") shouldBe "{baseUrl}/"
        }
      }
    }
  })
