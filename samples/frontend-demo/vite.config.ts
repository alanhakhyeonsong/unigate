import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

/**
 * ⚠️ 이 파일이 아키텍처 결정이다. 그리고 **환경마다 결정이 다르다.**
 *
 * | | local | alpha |
 * |---|---|---|
 * | FE 위치 | Vite dev server(5173) | 콘솔 호스트에 정적 배포 |
 * | API 접근 | **dev proxy 로 same-origin** | **cross-origin** (게이트웨이 호스트 직접 호출) |
 * | CORS | 불필요 | **필수** (게이트웨이의 `unigate.cors.allowed-origins`) |
 * | API base | 빈 문자열(상대경로) | 게이트웨이 origin |
 *
 * 즉 로컬에서 잘 되는 것이 alpha 에서 그대로 되지 않는다 — same-origin 이라는 전제가 사라진다.
 * 그 차이를 **설정 한 곳**에 모아 두고, 코드는 `VITE_API_BASE_URL` 만 본다.
 *
 * 실행:
 *   npm run dev              → mode=development (.env.development)
 *   npm run dev:alpha        → mode=alpha       (.env.alpha)  — cross-origin 동작을 로컬에서 재현
 *   npm run build:alpha      → mode=alpha 로 정적 빌드
 */
export default defineConfig(({ mode }) => {
  // VITE_ 접두사가 없는 값도 읽으려면 세 번째 인자를 비운다.
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.VITE_DEV_PROXY_TARGET

  // 프록시 대상이 있으면 same-origin 배치, 없으면 cross-origin 배치다.
  // **둘을 동시에 켜지 않는다** — 프록시가 있는데 API base 까지 절대주소면 프록시를 우회해
  // "설정은 켰는데 안 먹는" 상태가 된다.
  const proxied = ['/api', '/iam', '/oauth2', '/login', '/logout', '/debug', '/actuator']

  return {
    plugins: [react()],
    server: {
      port: Number(env.VITE_DEV_PORT ?? 5173),
      proxy: proxyTarget
        ? Object.fromEntries(
            proxied.map((path) => [
              path,
              {
                target: proxyTarget,
                // ⚠️ Host 를 **바꾸지 않는다.**
                //
                // 그러면 게이트웨이가 만드는 OAuth redirect_uri 도 dev 서버 주소가 되고,
                // Keycloak client 에 그 주소가 등록돼 있어야 한다(`setup-realm.sh` 가 등록한다).
                // 등록이 없으면 `Invalid parameter: redirect_uri` 로 끊긴다 — 실제로 한 번 막혔다.
                //
                // true 로 두면 그 문제는 사라지지만 **로그인 후 게이트웨이 호스트에 착지해
                // FE 를 벗어난다.** 검증 흐름이 FE 안에서 끝나는 편이 낫다.
                changeOrigin: false,
                secure: false,
              },
            ]),
          )
        : undefined,
    },
  }
})
