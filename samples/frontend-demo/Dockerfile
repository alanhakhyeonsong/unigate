# 샘플 FE 콘솔 이미지 — build stage(node) → runtime stage(nginx).
#
# ⚠️ 빌드 컨텍스트는 **이 디렉토리**다(저장소 루트가 아니다).
#    루트의 `.dockerignore` 는 `*` 로 전부 제외하는 화이트리스트 방식이라 FE 소스가
#    컨텍스트에 들어오지 못한다. 컨텍스트를 좁히면 그 규칙과 충돌하지 않고,
#    FE 관련 파일이 FE 디렉토리 안에 모여 응집도도 올라간다.
#
#    docker build -f samples/frontend-demo/Dockerfile -t <image> samples/frontend-demo

# ── build stage ────────────────────────────────────────────────────────────────
# ⚠️ `--platform=$BUILDPLATFORM` — 빌드 머신의 **네이티브 아키텍처**로 돌린다.
#
# 최종 이미지는 amd64(클러스터 노드)여야 하지만, 이 스테이지까지 amd64 로 강제하면
# Apple Silicon 에서 npm ci 와 vite build 가 통째로 QEMU 에뮬레이션으로 돌아 몇 배 느려진다.
# 이 스테이지의 산출물은 정적 파일(HTML/JS/CSS)이라 **아키텍처와 무관**하므로,
# 네이티브로 만들어 amd64 런타임 스테이지에 복사해도 아무 문제가 없다.
FROM --platform=$BUILDPLATFORM node:22-alpine AS build

WORKDIR /build

# package*.json 을 **먼저** 복사해 의존성 레이어를 소스와 분리한다.
# 소스만 바뀌었을 때 npm ci 를 다시 돌리지 않기 위해서다.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# ⚠️ `build:alpha` 가 아니라 기본 `build` 다.
#    mode=alpha 로 빌드하면 `.env.alpha` 의 게이트웨이 주소가 번들에 박혀 **이미지가 환경에
#    종속된다.** 주소는 런타임에 `/config.js` 로 주입하므로(entrypoint.sh) 빌드는 주소를
#    모르는 상태로 끝나야 한다.
RUN npm run build

# ── runtime stage ──────────────────────────────────────────────────────────────
# unprivileged 변형을 쓴다. 표준 nginx 이미지는 root 로 시작해 워커만 강등하므로
# `runAsNonRoot: true` 인 파드에서 기동조차 못 한다.
FROM nginxinc/nginx-unprivileged:1.27-alpine

# 기본 설정을 통째로 대체한다(이유는 nginx.conf 머리말 참조 — readOnlyRootFilesystem).
COPY --chown=nginx:nginx docker/nginx.conf /etc/nginx/nginx.conf
COPY --from=build --chown=nginx:nginx /build/dist /usr/share/nginx/html
COPY --chown=nginx:nginx docker/entrypoint.sh /entrypoint.sh

USER root
RUN chmod +x /entrypoint.sh
USER nginx

EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]
