#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.yml"

if [[ ! -f "${COMPOSE_FILE}" ]]; then
  echo "未找到 docker-compose.yml: ${COMPOSE_FILE}" >&2
  exit 1
fi

if docker compose version >/dev/null 2>&1; then
  COMPOSE_CMD=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_CMD=(docker-compose)
else
  echo "未检测到 docker compose 或 docker-compose 命令" >&2
  exit 1
fi

DEFAULT_SERVICES=(
  milvus-etcd
  milvus-minio
  milvus-standalone
  mysql8
  redis
)

if [[ $# -gt 0 ]]; then
  SERVICES=("$@")
else
  SERVICES=("${DEFAULT_SERVICES[@]}")
fi

echo "启动服务: ${SERVICES[*]}"
"${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" up -d "${SERVICES[@]}"

wait_for_service() {
  local service_name="$1"
  local timeout_seconds=300
  local waited_seconds=0
  local container_id=""

  container_id="$("${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" ps -q "${service_name}")"
  if [[ -z "${container_id}" ]]; then
    echo "服务 ${service_name} 未找到对应容器" >&2
    return 1
  fi

  while (( waited_seconds < timeout_seconds )); do
    local state=""
    local health=""
    state="$(docker inspect --format '{{.State.Status}}' "${container_id}" 2>/dev/null || true)"
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${container_id}" 2>/dev/null || true)"

    if [[ "${state}" == "running" && ( "${health}" == "healthy" || "${health}" == "none" ) ]]; then
      echo "服务 ${service_name} 已就绪 (state=${state}, health=${health})"
      return 0
    fi

    if [[ "${state}" == "exited" || "${state}" == "dead" ]]; then
      echo "服务 ${service_name} 启动失败 (state=${state}, health=${health})" >&2
      return 1
    fi

    sleep 2
    waited_seconds=$((waited_seconds + 2))
  done

  echo "等待服务 ${service_name} 超时 (${timeout_seconds}s)" >&2
  return 1
}

for service in "${SERVICES[@]}"; do
  wait_for_service "${service}"
done

echo
echo "当前服务状态："
"${COMPOSE_CMD[@]}" -f "${COMPOSE_FILE}" ps "${SERVICES[@]}"
