#!/bin/sh
set -eu

# 런타임 설정 주입 — **이미지를 환경에 종속시키지 않기 위한 장치**다.
#
# 빌드 시점에 게이트웨이 주소를 번들에 박으면 환경마다 이미지를 다시 구워야 하고,
# "같은 이미지를 승격한다" 는 배포 원칙이 깨진다. 그래서 주소는 기동 시 주입한다.
#
# 쓰는 위치가 /tmp 인 이유: 파드가 readOnlyRootFilesystem 으로 뜨므로
# /usr/share/nginx/html 아래에는 쓸 수 없다. nginx 가 /config.js 를 이 경로로 alias 한다.

: "${API_BASE_URL:=}"

if [ -z "${API_BASE_URL}" ]; then
  # 빈 값도 유효한 구성이다(게이트웨이와 same-origin 배치). 다만 대개는 주입 누락이므로
  # 조용히 넘어가지 않고 로그로 남긴다 — 증상이 "로그인 버튼만 안 먹는다" 라 추적이 어렵다.
  echo "WARN: API_BASE_URL 이 비어 있습니다. same-origin 배치가 아니라면 주입 누락입니다." >&2
fi

cat > /tmp/config.js <<EOF
window.__UNIGATE_CONFIG__ = { apiBaseUrl: "${API_BASE_URL}" };
EOF

echo "INFO: runtime config 생성 완료 (apiBaseUrl=${API_BASE_URL:-<empty>})"

exec nginx -c /etc/nginx/nginx.conf -g 'daemon off;'
