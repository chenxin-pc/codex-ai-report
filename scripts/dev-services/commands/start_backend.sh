#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "未找到 .env 文件: ${ENV_FILE}" >&2
  echo "请先从 .env.example 复制并填写必要配置。" >&2
  exit 1
fi

cd "${ROOT_DIR}"

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

echo "已加载 ${ENV_FILE}，启动后端服务..."
mvn -q spring-boot:run
