#!/usr/bin/env bash
#
# front.sh —— 只启动前端开发服务（Vite，5173），前台运行，Ctrl-C 停
#
# 需要后端已在 8787：/api 会被代理过去。
# 也可以在浏览器里用单端口模式 http://127.0.0.1:8787（先 sh/build.sh）。

set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

cyan "ES Lab 前端"
dim "  目录 : front/"
dim "  地址 : http://127.0.0.1:5173"

if [ ! -d "$ROOT/front/node_modules" ]; then
  red "  front/node_modules 不存在，先执行：npm --prefix front install"
  exit 1
fi
if ! port_busy 8787; then
  warn "  后端 8787 没在跑，页面的 /api 请求会失败。另开一个终端：sh/backend.sh"
fi

echo
cd "$ROOT/front" && exec npm run dev
