#!/usr/bin/env bash
#
# seed.sh —— 快进：跳过第 0 章，直接预置全量实验数据
#
#   sh/seed.sh            # 20 万文档（默认，约 15 秒）
#   sh/seed.sh 20000      # 2 万文档，几秒完成
#
# 正常学习路径不需要它：第 0 章会在页面里带你从空集群建索引、写文档、灌到 2 万。
# 这个脚本用于「只想看后面章节」或「重置环境」。
# 索引定义与写入逻辑和页面按钮完全一致（DatasetService），会重建 v1 / v2（先删后建），
# 并把状态写进 var/seed.json。需要 Elasticsearch 已在 9200 运行。

set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

COUNT="${1:-200000}"
case "$COUNT" in
  ''|*[!0-9]*) red "数量必须是正整数：$COUNT"; exit 64 ;;
esac

cyan "导入实验数据"
dim "  文档数 : $COUNT"
dim "  状态   : var/seed.json"

if ! curl --noproxy '*' -s -o /dev/null -m 3 http://127.0.0.1:9200; then
  red "  连不上 Elasticsearch（http://127.0.0.1:9200），先在另一个终端启动 ES"
  exit 1
fi
if port_busy 8787; then
  warn "  后端正在 8787 运行。seed 会以非 Web 模式启动，不占端口，但会和后端抢 ES 写入"
fi

echo
mvn -q -f "$ROOT/backend/pom.xml" spring-boot:run \
  -Dspring-boot.run.arguments="--seed=$COUNT" 2>&1 | tee -a "$LOG_DIR/seed.log"
