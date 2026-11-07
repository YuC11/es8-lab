#!/usr/bin/env bash
#
# audit.sh —— 核对「声明」与「实际」是否一致
#
# 为什么需要它：每关都会建索引、开别名、装模板、开 scroll/PIT。这些资源由各自的
# run() 自己收尾，但「自己收尾」是口头约定，没法验证。这里把 LabIndexes 的登记
# 当作唯一声明，逐项对比 ES 里的真实对象：
#
#   1. 活着的 eslab-* 索引，是否都在册（不在册 = 有人偷偷建了没登记）
#   2. 登记为「跑完即删 / 仅模拟」的索引，是否真的不在（在 = 没清干净）
#   3. 模板与 pipeline 是否都在册（不在册 = clean.sh 与复位面板删不到）
#   4. open_contexts 是否为 0（scroll / PIT 关没关）
#
# 用法：
#   sh/audit.sh            只读核对，有问题退出码 1
#   sh/audit.sh --after-run 跑完一遍课程后再核对，额外检查一次性资源是否清空
#
# 前置：ES 在 9200；后端可选（能连上就读它的声明，连不上退化为只用 ES 事实）

set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

ES_URL="${ES_URL:-http://127.0.0.1:9200}"
API_URL="${API_URL:-http://127.0.0.1:8787}"
AFTER_RUN=0
[ "${1:-}" = "--after-run" ] && AFTER_RUN=1

cyan "ES Lab 资源审计"
dim "  ES   : $ES_URL"
dim "  后端 : $API_URL$([ "$AFTER_RUN" = "1" ] && echo '（跑完后核对）')"

if [ "$(curl --noproxy '*' -s -o /dev/null -w '%{http_code}' -m 3 "$ES_URL" 2>/dev/null)" != "200" ]; then
  red "  ES 连不上，无法审计"
  exit 2
fi

if [ "$(curl --noproxy '*' -s -o /dev/null -w '%{http_code}' -m 3 "$API_URL/api/lab/indexes" 2>/dev/null)" != "200" ]; then
  red "  后端没起，读不到声明（先 sh/backend.sh）"
  exit 2
fi

ESLAB_ES_URL="$ES_URL" ESLAB_API_URL="$API_URL" ESLAB_AFTER_RUN="$AFTER_RUN" python3 - <<'PY'
import json
import os
import sys
import urllib.request

ES = os.environ["ESLAB_ES_URL"]
API = os.environ["ESLAB_API_URL"]
AFTER_RUN = os.environ.get("ESLAB_AFTER_RUN") == "1"

CYAN, GREEN, RED, YELLOW, DIM, RESET = "\033[36m", "\033[32m", "\033[31m", "\033[33m", "\033[90m", "\033[0m"


def get(url, method="GET", body=None, timeout=15):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return json.loads(raw) if raw else {}
        except Exception:
            return {}


def cat(path):
    out = get(ES + path)
    return out if isinstance(out, list) else []


decl = get(API + "/api/lab/indexes")
if not decl or "indices" not in decl:
    print(RED + "  后端返回的声明为空" + RESET)
    sys.exit(2)

declared_idx = {r["name"]: r for r in decl["indices"]}
declared_assets = {(a["type"], a["name"]) for a in decl.get("assets", [])}
resources = decl.get("resources", {})

live_idx = {r["index"] for r in cat("/_cat/indices/eslab-*?format=json&h=index")}
live_tpl = {r["name"] for r in cat("/_cat/templates/eslab-*?format=json&h=name")}
try:
    comp = get(ES + "/_index_template").get("index_templates", [])
    live_comp_tpl = {t["name"] for t in comp if str(t.get("name", "")).startswith("eslab-")}
except Exception:
    live_comp_tpl = set()
live_pipe = {k for k in get(ES + "/_ingest/pipeline").keys() if k.startswith("eslab-")}
live_alias = {r["alias"] for r in cat("/_cat/aliases/eslab-*?format=json&h=alias,index") if "alias" in r}

problems, warns = [], []

# 1. 活着但没登记
for name in sorted(live_idx - set(declared_idx)):
    problems.append("未登记的索引在运行：%s（有人建了没写进 LabIndexes）" % name)

# 2. 该消失的还在
for name, spec in declared_idx.items():
    life = spec.get("lifecycle", "")
    if name not in live_idx:
        continue
    if life == "DROP_AFTER_RUN":
        problems.append("一次性索引没删：%s（%s）" % (name, spec.get("owner", "")))
    elif life == "PLANNED":
        problems.append("只该模拟的索引被真的建出来了：%s（%s）" % (name, spec.get("owner", "")))

# 3. 模板 / pipeline
for name in sorted(live_tpl | live_comp_tpl):
    if ("template", name) not in declared_assets:
        problems.append("未登记的模板：%s" % name)
for name in sorted(live_pipe):
    if ("pipeline", name) not in declared_assets:
        problems.append("未登记的 pipeline：%s" % name)

# 4. 搜索上下文
try:
    stats = get(ES + "/_nodes/stats/indices/search")
    open_ctx = sum(n.get("indices", {}).get("search", {}).get("open_contexts", 0)
                   for n in stats.get("nodes", {}).values())
except Exception:
    open_ctx = -1
if open_ctx > 0:
    problems.append("还有 %d 个搜索上下文没关（scroll / PIT 泄漏）" % open_ctx)
elif open_ctx < 0:
    warns.append("读不到 open_contexts，跳过该项")

# 5. 别名指向
for a in decl.get("aliases", []):
    if a["name"] not in live_alias:
        warns.append("别名不存在：%s（相关关卡可能还没跑）" % a["name"])

# ── 报告 ──────────────────────────────────────────────────
print()
print(CYAN + "  声明与实际对齐" + RESET)
print(DIM + "    登记索引 %d 个 · 活着的 eslab-* 索引 %d 个 · 模板 %d · pipeline %d"
      % (len(declared_idx), len(live_idx), len(live_tpl | live_comp_tpl), len(live_pipe)) + RESET)

per_level = []
for level, r in sorted(resources.items()):
    parts = []
    for i in r.get("indices", []):
        mark = "✓" if i["name"] in live_idx else "·"
        parts.append("%s %s(%s)" % (mark, i["name"], i["lifecycle"]))
    for a in r.get("assets", []):
        parts.append("asset %s:%s" % (a["type"], a["name"]))
    for c in r.get("contexts", []):
        parts.append("%s" % c["kind"])
    if r.get("aliases"):
        parts.append("alias " + "/".join(r["aliases"]))
    per_level.append((level, parts))

if per_level:
    print()
    print(CYAN + "  每关声明的资源" + RESET)
    for level, parts in per_level:
        print("    %-8s %s" % (level, " · ".join(parts)))

if warns:
    print()
    print(YELLOW + "  提示" + RESET)
    for w in warns:
        print(YELLOW + "    ! " + w + RESET)

print()
if problems:
    print(RED + "  发现 %d 处不一致：" % len(problems) + RESET)
    for p in problems:
        print(RED + "    ✗ " + p + RESET)
    sys.exit(1)

print(GREEN + "  全部一致：没有未登记对象，该消失的都消失了，搜索上下文已归零" + RESET)
sys.exit(0)
PY
