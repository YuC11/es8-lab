#!/usr/bin/env bash
#
# seed.sh —— 快进：跳过第 0 章，直接预置实验数据
#
#   sh/seed.sh              # 每个索引 2 万条（默认，与页面按钮一致，几秒完成）
#   sh/seed.sh 200000       # 每个索引 20 万条，用于 M5 聚合误差 / M6 深分页
#   sh/seed.sh --force      # 后端还开着也强行跑（会与关卡抢 v1/v2，数字会错乱）
#
# 正常学习路径不需要它：第 0 章会在页面里带你从空集群建索引、写文档、灌到 2 万。
# 这个脚本用于「只想看后面章节」或「重置环境」。
# 索引定义与写入逻辑和页面按钮完全一致（DatasetService），会重建 v1 / v2（先删后建），
# 并把状态写进 var/seed.json。需要 Elasticsearch 已在 9200 运行。
#
# 注意：它会写 v1/v2 两个索引，所以上面的 COUNT 是「每个索引」的条数。

set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

FORCE=0
COUNT=""
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    ''|*[!0-9]*) red "未知参数：$arg（只接受数字或 --force）"; exit 64 ;;
    *) COUNT="$arg" ;;
  esac
done
COUNT="${COUNT:-20000}"

mkdir -p "$ROOT/var"

cyan "导入实验数据"
dim "  每个索引 : $COUNT 条（v1 + v2 共 $((COUNT * 2)) 条）"
dim "  状态     : var/seed.json"

if ! curl --noproxy '*' -s -o /dev/null -m 3 http://127.0.0.1:9200; then
  red "  连不上 Elasticsearch（http://127.0.0.1:9200），先在另一个终端启动 ES"
  exit 1
fi

# 后端在跑时不许 seed：它会在另一个 JVM 里重建 v1/v2，进程内锁管不到它，
# 关卡跑到一半 v2 被删会让结论直接错掉。宁可让人先停服务。
if port_busy 8787; then
  if [ "$FORCE" = "1" ]; then
    warn "  后端仍在 8787 运行（--force）：seed 与关卡会同时写 v1/v2，数字可能错乱"
  else
    red "  后端正在 8787 运行：seed 会重建 v1/v2，与关卡并发写入必然错乱"
    red "  先执行 sh/stop.sh，或加 --force 明确承担后果"
    exit 1
  fi
fi

# 跨进程互斥：macOS 没有 flock，用 mkdir 的原子性
LOCK="$ROOT/var/seed.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
  red "  另一个 seed 正在运行（$LOCK 已存在）。确认没有 seed 进程后手动删除它"
  exit 1
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT INT TERM

echo
mvn -q -f "$ROOT/backend/pom.xml" spring-boot:run \
  -Dspring-boot.run.arguments="--seed=$COUNT" 2>&1 | tee -a "$LOG_DIR/seed.log"
