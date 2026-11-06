#!/usr/bin/env bash
#
# stop.sh —— 停掉本项目的后端 / 前端进程（不碰 Elasticsearch）
#
# 只按进程特征精确匹配本项目，不会影响同机其它 Java 或 Vite。

set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

cyan "停止 ES Lab 进程"
stop_project

for p in 8787 5173; do
  if port_busy "$p"; then
    warn "  端口 $p 仍被占用（可能不是本项目进程，未处理）"
  else
    dim "  端口 $p 已释放"
  fi
done
dim "  Elasticsearch（9200）未触碰"
