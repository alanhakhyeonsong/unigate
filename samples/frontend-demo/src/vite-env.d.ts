/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

/**
 * 컨테이너 기동 시 생성되는 `/config.js` 가 채운다(`docker/entrypoint.sh`).
 * dev 에서는 `public/config.js` 가 같은 자리를 빈 값으로 채워, 두 환경의 코드 경로를 같게 유지한다.
 */
interface UnigateRuntimeConfig {
  readonly apiBaseUrl?: string
}

interface Window {
  readonly __UNIGATE_CONFIG__?: UnigateRuntimeConfig
}
