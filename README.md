# es-lab — Elasticsearch 学习实验室

从一个**空集群**开始，亲手建索引、写文档、灌数据，一路学到分片规划。
电商商品搜索场景（默认 2 万 SKU，可一键灌到 20 万），12 章 54 关 + 4 个 A/B 实验 + 190 条可执行的变体实验。

不是考试平台：没有选择题、不判分。每关末尾的「变体实验」是 3 条能一键执行的真实请求 —— 改个参数，看 ES 真实返回；「后端实现」抽屉显示对应的 Java 源码，并高亮刚执行的那几行。

## 和其他教程的区别

| 常见教程 | 这里 |
|---|---|
| 先跑脚本灌 20 万数据，再从「Mapping 怎么设计」讲起 | 第 0 章从空集群建索引、写文档、灌到 2 万 —— 数据是你自己造的 |
| 「第 3 关依赖第 1 关」靠自己猜 | 每关顶部有前置检查：缺哪个索引、差多少条，附修复按钮 |
| 知识堆在一页长文 | 每关固定六段：要回答什么 → 手上有什么 → 先猜 → 动手 → 看到了什么 → 所以呢 |
| 原理只有一段话，读完就忘 | 「深入原理」搬到独立阅读页（`#/deep/关卡号`）：左侧目录、分节长文、表格与手绘原理图，关卡页只留一行入口 |

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vite 6 + React 18 + TypeScript（`front/`） |
| 后端 | Java 21 + Spring Boot 3.3.5（`backend/`，端口 8787） |
| ES 客户端 | 官方 `elasticsearch-java` 8.18.5，只用低层 `RestClient` |
| 存储 | 本地 Elasticsearch 8.18.5 单节点，**你手动启动**，只操作 `eslab-*` |

## 快速开始

命令都在仓库根执行。

### 第 0 步：确认环境

| 依赖 | 要求 | 自查 |
|---|---|---|
| JDK | 21+ | `java -version` |
| Maven | 3.8+ | `mvn -v` |
| Node.js | 18+ | `node -v` |
| Elasticsearch | 8.18.5，关闭 security，已装 analysis-ik | 启动后 `curl -s --noproxy '*' http://127.0.0.1:9200` |

### 第 1 步：启动 Elasticsearch

**另开一个终端，全程不要关。** ES 必须手动启动，自动化进程会被 `sandbox_init` 拦掉（见「环境限制」）。

```bash
cd /Users/yc/environment/elastic-stack/elasticsearch-8.18.5
./bin/elasticsearch
```

回到原终端验证：

```bash
curl -s --noproxy '*' http://127.0.0.1:9200 | head -3
# 期望看到 "cluster_name" 与 "version": { "number": "8.18.5" }
```

### 第 2 步：构建前端产物（首次必做）

`front/dist` 不进版本库，而页面由 8787 托管，所以第一次必须先构建：

```bash
sh sh/build.sh
# [1/2] 前端 → front/dist（node_modules 不存在时自动 npm install）
# [2/2] 后端 → backend/target/es-lab-1.0.0.jar
```

改过前端代码也要重跑，或改用 `sh sh/dev.sh` 热更新。

### 第 3 步：启动平台

```bash
sh sh/backend.sh          # → http://127.0.0.1:8787
curl -s --noproxy '*' http://127.0.0.1:8787/api/health
# 期望 "cluster": { "up": true }，"levels" 54 项，"experiments" 4 项
```

### 第 4 步：打开页面，从第 0 章开始

浏览器打开 <http://127.0.0.1:8787>，看到**课程路线图**：

1. 顶部「环境与数据」面板：ES 版本、集群状态、v1 / v2 现状。第一次打开时两个索引都是「未创建」。
2. 点「从第一关开始」，进入**第 0 章 · 从零建库**（4 关，十几秒）：

| 关 | 你会亲手做的事 |
|---|---|
| m0-1 集群里有什么 | 查集群健康、节点、ik 插件，确认「现在什么都没有」 |
| m0-2 建你的第一个索引 | `PUT /eslab-products-v1`，看清 1 分片 / 0 副本意味着什么 |
| m0-3 写一条文档再读回来 | GET 能读到、`_search` 读不到 —— 近实时第一次现身 |
| m0-4 灌到 2 万条 | bulk 分批导入，看速率、段数、体积怎么变 |

3. 第 0 章跑完索引里有 2 万条；第 1 章开始讲 Mapping，v2 由你在 m1-2 亲手建出来。

> 想跳到后面：面板点 `创建 v1` → `灌到 20 万`，或 `sh sh/seed.sh`（快进，跳过第 0 章，直接预置 20 万 × 2 索引）。

学习进度只存在你自己的浏览器里（`localStorage`），不上传、不判分，跑通了才记一笔。想从头再走一遍，点路线图右上角 **重置学习进度**（两次点击确认）—— 只清记录，不碰索引和数据。

### 第 5 步：停止与清理

```bash
sh sh/stop.sh                  # 停后端与前端（不碰 Elasticsearch）
sh sh/clean.sh                 # 预览会删什么，默认不删
sh sh/clean.sh --yes           # 执行：eslab-* 索引 + var/ + logs/ + dist/target
sh sh/clean.sh --yes --build   # 只清构建产物
```

### 改了什么，跑哪条命令

| 场景 | 命令 |
|---|---|
| 每天启动 | 第 1 步 → 第 3 步 |
| 改前端、看效果 | `sh sh/build.sh` → 刷新 8787 |
| 改前端、要热更新 | `sh sh/dev.sh`（8787 + 5173，Ctrl-C 一起停） |
| 改后端 Java | Ctrl-C → `sh sh/backend.sh`（Maven 增量编译） |
| 数据乱了想重来 | 面板点「重建」，或 `sh sh/clean.sh --yes --es` 后重走第 0 章 |
| 学习进度想清零 | 路线图右上角「重置学习进度」（不碰数据） |
| 换 20 万规模 | 面板点「灌到 20 万」，约 15 秒 |

## 目录结构

```
es-lab/
├── backend/     Spring Boot 后端——唯一与 ES 通信的进程
│   └── src/main/java/com/eslab/
│       ├── data/        数据集定义与写入（DatasetService / JobRegistry）
│       ├── levels/      54 关，一关一类（M1-M6 的十个操作型小关卡在 CoreLevels）
│       │   └── support/     LabIndexes 是全部实验索引的唯一登记处；LabData 只负责执行
│       ├── experiments/ 4 个 A/B 实验
│       └── seed/        确定性数据生成 + 命令行快进
├── front/       React 前端——纯静态产物（dist/ 由 8787 托管）
│   └── src/
│       ├── pages/       Roadmap / LevelPage / DeepPage / Glossary / DslRef
│       ├── components/  ProbeLab / DataPanel / Prereq / CodeDrawer / deep / figures
│       └── lib/         router / progress / api / types
├── sh/          全部 shell 脚本
├── docs/        文档
├── var/         运行期状态（seed.json）
└── logs/        日志（spring.log）
```

## 课程

| 章 | 主题 | 关 |
|---|---|---|
| M0 | **从零建库**（体检 → 建索引 → 写文档 → bulk 2 万） | 4 |
| M1 | 索引与 Mapping（动态映射、显式设计、字段爆炸、CRUD 与元数据巡检） | 6 |
| M2 | 分词与分析器（standard / ik_smart / ik_max_word、_analyze 调试） | 3 |
| M3 | 写入路径（bulk、_id 策略、近实时、逐项失败与重放） | 4 |
| M4 | **查询 DSL**（请求体骨架 → 逐变量递进 → 叶子查询边界 → bool → 子句参数 → 算分成本 → 字段裁剪 / 多请求读取） | 8 |
| M5 | 聚合与排序（terms 误差、嵌套聚合、.keyword、指标聚合与桶遍历） | 5 |
| M6 | 分页与性能诊断（深分页、search_after + PIT、profile、scroll、explain） | 5 |
| M7 | 索引设计与优化实战（受控分片实验、统一口径的 A/B 实验台） | 2 |
| M8 | **索引变更与零停机**（别名、reindex、_tasks 进度、forcemerge/rollover） | 4 |
| M9 | **相关性工程**（multi_match、短语、function_score、高亮折叠、补全、同义词） | 6 |
| M10 | **增量写入与并发**（_update、_seq_no 409、bulk 混合动作、按条件删改） | 4 |
| M11 | **索引工厂**（模板、dynamic_templates/flattened、ingest pipeline） | 3 |

4 个 A/B 实验：深分页、filter vs must、v1 vs v2、refresh_interval —— 多轮采样取中位数，结论写明口径边界。

每关的「深入原理」是一个独立阅读页：左侧目录随滚动高亮，正文按「现象 → 机制 → 边界 → 怎么验证」分节，配 16 张手绘 SVG 原理图（写路径、段合并、别名切换、并发冲突等）。关卡页只留一行入口，标注字数、节数、图数与预计阅读时间。

另有两个随时可查的参考页（侧栏底部）：**术语表** 12 条；**查询速查** —— 请求体骨架、响应体地图、六种查询的写法与参数、v1/v2 字段字典（类型从真实 mapping 读，点字段即可生成一条查询送进控制台）。

## 数据对照

| 索引 | Mapping | 分片 | 默认规模 | 20 万时体积 |
|---|---|---|---|---|
| `eslab-products-v1` | 全动态（standard） | 1 | 2 万 | ~88 MB |
| `eslab-products-v2` | 显式 Mapping + IK | 3 | 2 万 | ~63 MB |

同一批数据、两种设计，是 M1 / M5 / M7 多个关卡的对照素材。规模随时可在面板切换。

## 环境限制

1. **ES 由你手动启动** —— 自动化进程树启 ES 会被 `sandbox_init` 拦截。
2. **不用容器** —— 只用本地 `elastic-stack` 的 8.18.5 单节点。
3. **本机代理可能劫持 localhost** —— 手工验证加 `curl --noproxy '*'`；Java 客户端直连不受影响。

## 文档

- [架构](docs/架构.md) —— 目录职责、边界规则、ES 访问层设计
- [关卡](docs/关卡.md) —— 54 关清单与每关一句话
- [索引治理](docs/索引治理.md) —— 32 个索引的登记、生命周期与复位方式
- [实验](docs/实验.md) —— 4 个 A/B 实验的方法与结论口径
- [接口](docs/接口.md) —— 后端接口约定
- [环境限制](docs/环境限制.md) —— 踩坑记录与边界条件
- [覆盖率评估](docs/覆盖率评估.md) —— 三套口径的逐项评分、差距清单与补齐路线
