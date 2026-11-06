#!/usr/bin/env bash
#
# _common.sh —— 所有 sh/ 脚本的公共前置（只 source，不单独执行）
#
# 职责有三件：
#   1. 定位仓库根，并导出 ESLAB_ROOT 给后端（后端是 backend/ 下的独立项目，
#      运行时需要靠它跨出去读 front/dist 与 backend/src 源码）
#   2. 保证 logs/ 存在
#   3. 提供「精确匹配本项目进程」的能力，避免误杀同机上的其它 Java / Vite

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ESLAB_ROOT="$ROOT"
LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"

# 进程匹配串：故意写得足够长，只命中本项目
#   后端  java ... com.eslab.EsLabApplication
#   Maven mvn -f <root>/backend/pom.xml spring-boot:run
#   前端  node <root>/front/node_modules/.bin/vite
PATTERN_APP="com\.eslab\.EsLabApplication"
PATTERN_MVN="es-lab/backend/pom\.xml"
PATTERN_VITE="es-lab/front/node_modules"

cyan()  { printf '\033[36m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }
warn()  { printf '\033[33m%s\033[0m\n' "$*"; }
dim()   { printf '\033[90m%s\033[0m\n' "$*"; }

project_pids() {
  { pgrep -f "$PATTERN_APP"
    pgrep -f "$PATTERN_MVN"
    pgrep -f "$PATTERN_VITE"
  } 2>/dev/null | sort -u
}

# 先 TERM 让 Spring 走优雅关闭，1 秒后仍在的用 KILL 收掉
stop_project() {
  local pids
  pids="$(project_pids | tr '\n' ' ')"
  if [ -z "${pids// /}" ]; then
    green "  没有本项目的后端 / 前端进程在运行"
    return 0
  fi
  dim "  正在停止：$pids"
  # shellcheck disable=SC2086
  kill $pids 2>/dev/null
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    [ -z "$(project_pids)" ] && break
    sleep 0.2
  done
  local left
  left="$(project_pids | tr '\n' ' ')"
  if [ -n "${left// /}" ]; then
    # shellcheck disable=SC2086
    kill -9 $left 2>/dev/null
    sleep 0.3
  fi
  green "  已停止"
}

# 后端是否已经在 8787 上（避免 dev.sh 重复起）
port_busy() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}
