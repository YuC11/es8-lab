#!/usr/bin/env bash
#
# build.sh —— 构建：前端出 front/dist，后端出 backend/target/es-lab-1.0.0.jar
#
# 两个项目各自独立构建，谁也不调用谁：
#   · 前端由 Vite 打包
#   · 后端由 Maven 打包（不含前端产物，jar 里没有前端）
# 单端口体验靠「后端运行时读 front/dist」，而不是把前端塞进 jar。

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

cyan "[1/2] 前端 → front/dist"
if [ ! -d "$ROOT/front/node_modules" ]; then
  dim "  安装依赖…"
  npm --prefix "$ROOT/front" install --silent
fi
npm --prefix "$ROOT/front" run build
echo

cyan "[2/2] 后端 → backend/target"
mvn -q -f "$ROOT/backend/pom.xml" -DskipTests package
echo

green "构建完成"
[ -f "$ROOT/front/dist/index.html" ] && dim "  前端：front/dist/index.html"
JAR="$ROOT/backend/target/es-lab-1.0.0.jar"
[ -f "$JAR" ] && dim "  后端：backend/target/es-lab-1.0.0.jar  ($(du -h "$JAR" | cut -f1))"
dim "  访问：sh/backend.sh  →  http://127.0.0.1:8787"
