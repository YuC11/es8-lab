#!/usr/bin/env bash
#
# backend.sh —— 只启动后端（Spring Boot，8787），前台运行，Ctrl-C 停
#
# 后端不负责构建前端：页面要能打开，需要 front/dist 已存在（sh/build.sh）。

set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

cyan "ES Lab 后端"
dim "  仓库根 : $ROOT"
dim "  日志   : logs/spring.log"

if [ ! -d "$ROOT/front/dist" ]; then
  warn "  未找到 front/dist，页面会 404。先执行：sh/build.sh"
fi
if port_busy 8787; then
  red "  8787 已被占用，先执行：sh/stop.sh"
  exit 1
fi

echo
# 注意：这里必须显式 -f backend/pom.xml。Maven 的 forked JVM 工作目录是 backend/，
# 靠 ESLAB_ROOT 把仓库根传进去，否则后端会去 backend/ 下找 front/dist。
mvn -q -f "$ROOT/backend/pom.xml" spring-boot:run 2>&1 | tee -a "$LOG_DIR/spring.log"
