#!/usr/bin/env bash
#
# unigate alpha 로컬 직접 배포 스크립트 (CI/CD 없음).
#   1) gradle bootJar  2) docker build  3) docker push  4) helm upgrade
#
# 사전 조건:
#   - 컨테이너 레지스트리 로그인: docker login <container-registry>
#   - 대상 k8s 컨텍스트 선택: kubectl config use-context <alpha-context>
#   - deploy/helm/unigate/values-alpha.secret.yaml 작성 (커밋 금지)
#   - 공유 PostgreSQL 에 'unigate' DB 사전 생성, Keycloak 전용 realm 'unigate' 준비
#
# 사용법:
#   ./deploy/deploy-alpha.sh [namespace]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# TODO: 배포 대상 확정 시 실제 값으로 교체 (registry / namespace)
MODULE="gateway"
REGISTRY="${UNIGATE_REGISTRY:-<container-registry>/unigate}"
CHART_DIR="deploy/helm/unigate"
NAMESPACE="${1:-${UNIGATE_NAMESPACE:-unigate}}"

GIT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo nogit)"
TAG="$(date +%Y%m%d%H%M%S)-${GIT_SHA}"
IMAGE="${REGISTRY}:${TAG}"

SECRET_VALUES="${CHART_DIR}/values-alpha.secret.yaml"
if [[ ! -f "${SECRET_VALUES}" ]]; then
  echo "ERROR: ${SECRET_VALUES} 가 없습니다. values-alpha.secret.yaml.example 를 복사해 채우세요." >&2
  exit 1
fi

echo "==> [1/4] bootJar"
./gradlew ":${MODULE}:bootJar"

echo "==> [2/4] docker build ${IMAGE}"
docker build -f docker/server.dockerfile --build-arg MODULE_NAME="${MODULE}" -t "${IMAGE}" .

echo "==> [3/4] docker push ${IMAGE}"
docker push "${IMAGE}"

echo "==> [4/4] helm upgrade --install (ns=${NAMESPACE}, tag=${TAG})"
helm upgrade --install unigate "${CHART_DIR}" \
  -f "${CHART_DIR}/values-alpha.yaml" \
  -f "${SECRET_VALUES}" \
  --set global.image.tag="${TAG}" \
  --namespace "${NAMESPACE}" --create-namespace

echo "==> 완료: ${IMAGE}"
