#!/usr/bin/env bash
#
# clean.sh —— 清除本实验产生的资源（ES 索引 / 本地数据 / 构建产物）
#
#   sh/clean.sh                      # 预览要删什么（默认不删）
#   sh/clean.sh --yes                # 执行清理（默认三档全清）
#   sh/clean.sh --yes --es           # 只删 eslab-* 索引
#   sh/clean.sh --yes --data         # 只删 var/ 状态与 logs/ 日志
#   sh/clean.sh --yes --build        # 只删 front/dist、backend/target、vite 缓存
#   sh/clean.sh --yes --all --with-services   # 全清 + 停掉本项目的后端 / 前端进程
#
# 安全边界（硬编码，不提供关闭开关）：
#   · ES 只允许删除 eslab- 前缀的索引，其它索引一律拒绝并在日志中记录
#   · 文件只允许删除 $ROOT 下的白名单路径，绝不触碰 elastic-stack 目录、front/src、backend/src
#   · 未带 --yes 时只做预览，不做任何删除
#
# 性能：索引按「整体删除」而非 _delete_by_query（后者要逐条标记删除，20 万文档是分钟级），
#      逐个删除即可，十几个索引不到 1 秒。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ES_URL="${ES_URL:-http://127.0.0.1:9200}"
PREFIX="eslab-"
TS="$(date +%Y%m%d-%H%M%S)"
LOG="$ROOT/logs/cleanup-$TS.log"

LEVELS=()
DRY=1
YES=0
WITH_SERVICES=0
PARALLEL=4

cyan()  { printf '\033[36m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }
warn()  { printf '\033[33m%s\033[0m\n' "$*"; }
dim()   { printf '\033[90m%s\033[0m\n' "$*"; }

usage() {
  sed -n '3,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

for arg in "$@"; do
  case "$arg" in
    --dry-run)       DRY=1 ;;
    --yes|-y)        YES=1; DRY=0 ;;
    --es)            LEVELS+=("es") ;;
    --data)          LEVELS+=("data") ;;
    --build)         LEVELS+=("build") ;;
    --all)           LEVELS+=("es" "data" "build") ;;
    --with-services) WITH_SERVICES=1 ;;
    --parallel=*)    PARALLEL="${arg#*=}" ;;
    --help|-h)       usage ;;
    *) red "未知参数：$arg（--help 查看用法）"; exit 64 ;;
  esac
done

if [ ${#LEVELS[@]} -eq 0 ]; then
  LEVELS=("es" "data" "build")
fi

mkdir -p "$(dirname "$LOG")"
log() { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*" >>"$LOG"; }

cyan "ES Lab 清理"
dim "  项目根目录 : $ROOT"
dim "  ES 地址    : $ES_URL"
dim "  清理范围   : ${LEVELS[*]}"
dim "  日志       : $LOG"
echo
log "=== cleanup start scope=${LEVELS[*]} dry=$DRY yes=$YES ==="

# ── 工具函数 ─────────────────────────────────────────────────
# 用 node 解析 JSON（避免依赖 jq），失败时返回空数组
json_field() {
  local field="$1"
  node -e '
    let s = "";
    process.stdin.on("data", d => s += d).on("end", () => {
      try {
        const arr = JSON.parse(s);
        const f = process.argv[1];
        for (const r of arr) console.log(String(r[f]));
      } catch { /* 空输入或非 JSON，静默返回空 */ }
    });
  ' "$field"
}

es_reachable() {
  [ "$(curl --noproxy '*' -s -o /dev/null -w '%{http_code}' -m 3 "$ES_URL" 2>/dev/null)" = "200" ]
}

# ── 1. ES 索引 ───────────────────────────────────────────────
# 模板与 pipeline 没有索引实体，_cat/indices 看不到，必须单独清。
# 顺序上先清资产再删索引：否则删完索引后模板还在，下一次建索引又会套上。
clean_assets() {
  local assets=(
    "_index_template/eslab-goods-template"
    "_ingest/pipeline/eslab-goods-enrich"
  )
  printf '  集群资产（模板 / 管道）：\n'
  local a code
  for a in "${assets[@]}"; do
    if [ "$DRY" = "1" ]; then
      printf '    - 将删除 %s\n' "$a"
      log "will delete asset $a"
      continue
    fi
    code=$(curl --noproxy '*' -s -o /dev/null -w '%{http_code}' -X DELETE -m 30 "$ES_URL/$a")
    case "$code" in
      200) green "    ✓ 已删除 $a"; log "deleted asset $a" ;;
      404) dim   "    - 不存在 $a" ;;
      *)   red   "    ✗ 删除失败 $a (HTTP $code)"; log "FAILED asset $a HTTP=$code" ;;
    esac
  done
  return 0
}

clean_es() {
  cyan "[1/3] ES 索引"
  if ! es_reachable; then
    warn "  连不上 $ES_URL，跳过 ES 部分（本地文件仍会继续清理）"
    log "ES unreachable, skip"
    return 2
  fi

  local list
  list="$(curl --noproxy '*' -s -m 10 "$ES_URL/_cat/indices/${PREFIX}*?h=index,docs.count,store.size&format=json" | json_field index)"

  # 模板与 pipeline 没有索引实体，_cat/indices 看不到，必须单独清 —— 且要在删索引之前清，
  # 否则删完索引后模板还在，下一次建索引又会套上。
  clean_assets

  if [ -z "$list" ]; then
    green "  没有 ${PREFIX}* 索引，无需清理"
    log "no indices matched ${PREFIX}*"
    return 0
  fi

  # 二次校验前缀：即使上游返回了意外索引名，也不会误删
  local safe=() rejected=()
  while IFS= read -r idx; do
    [ -z "$idx" ] && continue
    case "$idx" in
      "${PREFIX}"*) safe+=("$idx") ;;
      *) rejected+=("$idx") ;;
    esac
  done <<<"$list"

  for bad in "${rejected[@]:-}"; do
    [ -n "$bad" ] || continue
    red "  拒绝删除非 ${PREFIX} 前缀索引：$bad"
    log "REJECT $bad"
  done

  printf '  待删除 %d 个索引：\n' "${#safe[@]}"
  for idx in "${safe[@]}"; do printf '    - %s\n' "$idx"; log "will delete index $idx"; done

  if [ "$DRY" = "1" ]; then
    dim "  （预览模式，未执行删除）"
    return 0
  fi

  local started
  started=$(node -e 'console.log(Date.now())')
  local failed=0

  # 串行删除。索引删除是整体 unlink，单个通常 <50ms，十几个索引加起来不到 1 秒；
  # 之前用 xargs -P 并发，在 macOS 的 BSD xargs 上会报
  # "command line cannot be assembled, too long" 而一个都没删掉 —— 不值得为这点时间换可移植性。
  local idx code
  for idx in "${safe[@]}"; do
    code=$(curl --noproxy '*' -s -o /dev/null -w '%{http_code}' -X DELETE -m 60 "$ES_URL/$idx")
    case "$code" in
      200) green "    ✓ 已删除 $idx"; log "deleted $idx" ;;
      404) dim   "    - 已不存在 $idx"; log "gone $idx" ;;
      *)   red   "    ✗ 删除失败 $idx (HTTP $code)"
           log "FAILED $idx HTTP=$code"
           failed=$((failed + 1)) ;;
    esac
  done

  # 删除后复核：确认没有残留
  local left
  left="$(curl --noproxy '*' -s -m 10 "$ES_URL/_cat/indices/${PREFIX}*?h=index&format=json" | json_field index)"
  if [ -z "$left" ]; then
    green "  复核通过：${PREFIX}* 已全部清除"
  else
    warn "  仍有残留：$(echo "$left" | tr '\n' ' ')"
  fi

  local elapsed
  elapsed=$(node -e "console.log(((Date.now()-$started)/1000).toFixed(2))")
  dim "  耗时 ${elapsed}s"
  log "es cleanup done in ${elapsed}s"
  return 0
}

# ── 2. 本地数据与日志 ────────────────────────────────────────
clean_data() {
  cyan "[2/3] 本地数据与日志"
  local targets=()

  [ -f "$ROOT/var/seed.json" ] && targets+=("$ROOT/var/seed.json")

  for f in "$ROOT/logs"/*.log; do
    [ -e "$f" ] || continue
    case "$f" in
      "$LOG") continue ;;  # 不删本次清理日志
    esac
    targets+=("$f")
  done

  if [ ${#targets[@]} -eq 0 ]; then
    green "  没有需要清理的本地文件"
    return 0
  fi

  printf '  待删除 %d 个文件：\n' "${#targets[@]}"
  for t in "${targets[@]}"; do printf '    - %s\n' "${t#$ROOT/}"; log "will delete file $t"; done

  if [ "$DRY" = "1" ]; then
    dim "  （预览模式，未执行删除）"
    return 0
  fi

  local n=0
  for t in "${targets[@]}"; do
    # 路径白名单校验：必须在项目根目录内，且不能是项目根本身
    case "$t" in
      "$ROOT"/*) ;;
      *) red "    跳过越界路径：$t"; log "SKIP out-of-root $t"; continue ;;
    esac
    if rm -f -- "$t" 2>/dev/null; then
      n=$((n + 1))
    else
      warn "    删除失败：$t"
      log "FAILED file $t"
    fi
  done
  green "  已删除 $n 个文件"
  log "data cleanup: $n files"
  return 0
}

# ── 3. 构建产物与前端缓存 ────────────────────────────────────
clean_build() {
  cyan "[3/3] 构建产物与缓存"
  local dirs=()
  [ -d "$ROOT/front/dist" ] && dirs+=("$ROOT/front/dist")
  [ -d "$ROOT/backend/target" ] && dirs+=("$ROOT/backend/target")
  [ -d "$ROOT/front/node_modules/.vite" ] && dirs+=("$ROOT/front/node_modules/.vite")

  if [ ${#dirs[@]} -eq 0 ]; then
    green "  没有需要清理的构建产物"
    return 0
  fi

  for d in "${dirs[@]}"; do printf '    - %s\n' "${d#$ROOT/}"; log "will delete dir $d"; done

  if [ "$DRY" = "1" ]; then
    dim "  （预览模式，未执行删除）"
    return 0
  fi

  for d in "${dirs[@]}"; do
    case "$d" in
      "$ROOT/front/dist"|"$ROOT/backend/target"|"$ROOT/front/node_modules/.vite") ;;
      *) red "    跳过非白名单目录：$d"; continue ;;
    esac
    rm -rf -- "$d" && green "    ✓ 已删除 ${d#$ROOT/}" && log "deleted dir $d"
  done
  return 0
}

# ── 4. 可选：停掉本项目进程 ──────────────────────────────────
clean_services() {
  cyan "[附] 停止本项目进程"
  local pids
  pids=$( { pgrep -f "com\.eslab\.EsLabApplication"
            pgrep -f "es-lab/backend/pom\.xml"
            pgrep -f "es-lab/front/node_modules"
          } 2>/dev/null | sort -u | tr '\n' ' ')
  if [ -z "${pids// /}" ]; then
    green "  没有本项目的后端 / 前端进程在运行"
    return 0
  fi
  if [ "$DRY" = "1" ]; then
    dim "  将停止：$pids"
    return 0
  fi
  # shellcheck disable=SC2086
  kill $pids 2>/dev/null
  sleep 1
  # shellcheck disable=SC2086
  kill -9 $pids 2>/dev/null
  green "  已停止：$pids"
  log "killed pids $pids"
  return 0
}

# ── 执行 ─────────────────────────────────────────────────────
rc=0
for lv in "${LEVELS[@]}"; do
  case "$lv" in
    es)    clean_es    || rc=$? ;;
    data)  clean_data  || rc=$? ;;
    build) clean_build || rc=$? ;;
  esac
  echo
done

[ "$WITH_SERVICES" = "1" ] && { clean_services; echo; }

if [ "$DRY" = "1" ]; then
  warn "以上为预览。确认无误后执行：sh/clean.sh --yes ${LEVELS[*]/#/--}"
  log "=== dry-run end ==="
  exit 0
fi

green "清理完成。日志：${LOG#$ROOT/}"
log "=== cleanup end rc=$rc ==="
exit "$rc"
