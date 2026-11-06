#!/usr/bin/env bash
#
# dev.sh —— 开发模式：后端 8787 + 前端 5173 一起跑，Ctrl-C 一起停
#
# 打开 http://127.0.0.1:5173 （热更新）。改前端不用重启，改后端需 Ctrl-C 后重跑。

set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

if port_busy 8787; then
  red "8787 已被占用，先执行：sh/stop.sh"
  exit 1
fi

BACK_PID=""
FRONT_PID=""

cleanup() {
  echo
  warn "  正在停止后端与前端…"
  [ -n "$FRONT_PID" ] && kill "$FRONT_PID" 2>/dev/null
  [ -n "$BACK_PID" ] && kill "$BACK_PID" 2>/dev/null
  # Spring Boot 的 forked JVM 不一定跟着 Maven 一起退出，按进程特征再收一遍
  stop_project
  echo
  green "  已停止"
}

trap 'exit 130' INT TERM
trap cleanup EXIT

cyan "ES Lab 开发模式"
dim "  后端日志 : logs/spring.log"
dim "  前端地址 : http://127.0.0.1:5173"
echo

mvn -q -f "$ROOT/backend/pom.xml" spring-boot:run >"$LOG_DIR/spring.log" 2>&1 &
BACK_PID=$!

( cd "$ROOT/front" && npm run dev ) &
FRONT_PID=$!

wait "$BACK_PID" "$FRONT_PID"
