/**
 * 每关「运行本关」时后端实际会发出的 ES 请求。
 *
 * 为什么放在前端：后端把每关封装成一个 run() 方法，请求是过程而不是返回值，
 * 页面只能拿到「结果」。这份清单是把过程显式写出来的地方，让人点按钮之前
 * 就知道会发生什么，而不是跑完才从 JSON 里猜。
 *
 * 维护约定：改 backend/src/main/java/com/eslab/levels/*.java 里的请求时，
 * 同步改这里对应的条目（path 必须与实际发出的完全一致）。
 * 没有 body 的 POST/PUT/DELETE 不提供「在控制台执行」，因为照抄会跑不通。
 */

export type Method = 'GET' | 'POST' | 'PUT' | 'DELETE';

export interface CallSpec {
  method: Method;
  path: string;
  /** 这条请求要做什么，一句话 */
  note: string;
  /** 请求体：有值时可以在控制台原样复现 */
  body?: unknown;
  /** 同一路径出现多次时用来区分：一行 DSL 摘要 */
  dsl?: string;
  /** 多次请求的省略写法，如「× 20 次」 */
  repeat?: string;
  /** 条件执行说明，如「索引已存在则跳过」 */
  cond?: string;
}

/** true 表示这条能被控制台原样复现 */
export const isReplayable = (c: CallSpec) => c.method === 'GET' || c.body !== undefined;

const CAT_NODES = '/_cat/nodes?format=json&h=name,version,heap.percent,cpu,disk.avail';
const CAT_INDICES = '/_cat/indices/eslab-*?format=json&h=index,status,docs.count,store.size,pri,rep';

export const LEVEL_CALLS: Record<string, CallSpec[]> = {
  // ── 第 0 章 从零建库 ────────────────────────────────────
  'm0-1': [
    { method: 'GET', path: '/_cluster/health', note: '集群状态：节点数、活跃分片、未分配分片' },
    { method: 'GET', path: CAT_NODES, note: '每个节点的版本、堆占用、CPU、剩余磁盘' },
    { method: 'GET', path: CAT_INDICES, note: '当前有哪些实验索引（空集群时返回空数组）' },
    { method: 'GET', path: '/_cat/plugins?format=json&h=component,version', note: '确认 analysis-ik 是否已安装' },
  ],
  'm0-2': [
    {
      method: 'PUT',
      path: '/eslab-products-v1',
      note: '只给 settings，一个字都不写 mappings',
      body: { settings: { number_of_shards: 1, number_of_replicas: 0, refresh_interval: '1s' } },
      cond: '索引已存在则跳过这一步',
    },
    { method: 'GET', path: '/eslab-products-v1/_settings', note: '读回分片数、副本数、refresh_interval' },
    {
      method: 'GET',
      path: '/_cat/shards/eslab-products-v1?format=json&h=index,shard,prirep,state,docs',
      note: '分片落在哪、是主还是副本、有没有 UNASSIGNED',
    },
    { method: 'GET', path: '/eslab-products-v1/_count', note: '此刻文档数（刚建应该是 0）' },
  ],
  'm0-3': [
    {
      method: 'PUT',
      path: '/eslab-products-v1/_doc/SKU2026000001',
      note: '写一条文档，指定 _id',
      body: { sku: 'SKU2026000001', title: '声阔 主动降噪头戴耳机 Q45', price: 899 },
    },
    { method: 'GET', path: '/eslab-products-v1/_doc/SKU2026000001', note: '按 _id 直取：文档已经在段里了' },
    {
      method: 'POST',
      path: '/eslab-products-v1/_search',
      note: '但搜不到它 —— 还没 refresh',
      body: { size: 0, query: { match_all: {} } },
    },
    { method: 'POST', path: '/eslab-products-v1/_refresh', note: '手动刷新，把内存缓冲写成可搜索的段' },
    {
      method: 'POST',
      path: '/eslab-products-v1/_search',
      note: '再搜一次，命中 1 条',
      body: { size: 0, query: { match_all: {} } },
    },
  ],
  'm0-4': [
    { method: 'POST', path: '/_bulk', note: 'NDJSON 批量写入：action 行 + source 行交替，每批 2000 条', repeat: '分批多次' },
    { method: 'GET', path: '/eslab-products-v1/_stats/segments', note: '导入后的段数、体积、文档数' },
    { method: 'GET', path: '/eslab-products-v1/_count', note: '核对最终条数' },
  ],

  // ── 第 1 章 Mapping ────────────────────────────────────
  'm1-1': [
    {
      method: 'PUT',
      path: '/eslab-demo-auto',
      note: '建一个只有 settings 的临时索引',
      body: { settings: { number_of_shards: 1, number_of_replicas: 0 } },
    },
    {
      method: 'PUT',
      path: '/eslab-demo-auto/_doc/1',
      note: '第一条文档：price 是数字、tags 是数组',
      body: { sku: 'SKU-0001', title: '小米 无线蓝牙耳机', price: 299.0, tags: ['数码', '音频'], status: 1, created_at: '2026-10-01T10:00:00+08:00' },
    },
    {
      method: 'PUT',
      path: '/eslab-demo-auto/_doc/2',
      note: '第二条文档：price 传成字符串 "599.00"，tags 传成单值',
      body: { sku: 'SKU-0002', title: '机械键盘', price: '599.00', tags: '数码', status: 3, created_at: '2026-10-02' },
    },
    { method: 'GET', path: '/eslab-demo-auto/_mapping', note: '看 ES 把每个字段猜成了什么类型' },
    {
      method: 'POST',
      path: '/eslab-demo-auto/_search',
      note: 'term 查 tags（被猜成 text，词项是"数""码"）',
      dsl: 'term tags="数码"',
      body: { size: 0, query: { term: { tags: '数码' } } },
    },
    {
      method: 'POST',
      path: '/eslab-demo-auto/_search',
      note: 'term 查 sku（被猜成 text，整串查不中）',
      dsl: 'term sku="SKU-0001"',
      body: { size: 0, query: { term: { sku: 'SKU-0001' } } },
    },
    {
      method: 'POST',
      path: '/eslab-demo-auto/_search',
      note: 'match 查 title（走分词，能中）',
      dsl: 'match title="蓝牙耳机"',
      body: { size: 0, query: { match: { title: '蓝牙耳机' } } },
    },
    {
      method: 'POST',
      path: '/eslab-demo-auto/_search',
      note: '按 title 排序 —— 故意触发 fielddata 报错',
      dsl: 'sort title asc',
      body: { size: 1, sort: [{ title: 'asc' }] },
    },
    { method: 'DELETE', path: '/eslab-demo-auto', note: '删掉临时索引，不留垃圾' },
  ],
  'm1-2': [
    {
      method: 'PUT',
      path: '/eslab-products-v2',
      note: '14 个字段逐个定类型、3 分片、dynamic: strict',
      cond: 'v2 已存在则跳过',
    },
    { method: 'POST', path: '/_bulk', note: '把 v1 的 2 万条复制进 v2（同一条数据，两套 Mapping）', repeat: '分批多次' },
    { method: 'GET', path: '/eslab-products-v1/_mapping', note: 'v1 的 mapping：ES 自动猜的' },
    { method: 'GET', path: '/eslab-products-v2/_mapping', note: 'v2 的 mapping：人定的' },
    { method: 'GET', path: '/eslab-products-v2/_settings', note: '分片数、副本数、refresh_interval' },
  ],
  'm1-3': [
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '取一条真实文档，拿到完整标题做精确匹配对照',
      body: { size: 1, query: { match: { title: '蓝牙耳机' } } },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'term 打在 text 字段上：词项不匹配，0 命中',
      dsl: 'term title="蓝牙耳机"',
      body: { size: 0, query: { term: { title: '蓝牙耳机' } } },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'match 打在同一字段：会分词，有命中',
      dsl: 'match title="蓝牙耳机"',
      body: { size: 0, track_total_hits: true, query: { match: { title: '蓝牙耳机' } } },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'term 打在 title.keyword 上：整串完全相等才命中（标题取自上一条结果，无法原样复现）',
      dsl: 'term title.keyword="<完整标题>"',
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'term 打在 tags 上：keyword 字段整值匹配',
      dsl: 'term tags="新品"',
      body: { size: 0, track_total_hits: true, query: { term: { tags: '新品' } } },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_analyze',
      note: '看 ik_smart 把这句话切成哪些词',
      body: { analyzer: 'ik_smart', text: '蓝牙耳机' },
    },
  ],
  'm1-4': [
    {
      method: 'PUT',
      path: '/eslab-demo-explode',
      note: '建索引并打开 dynamic（默认就是 true）',
      body: { settings: { number_of_shards: 1, number_of_replicas: 0 }, mappings: { dynamic: true } },
    },
    { method: 'POST', path: '/_bulk', note: '第 N 波属性写入，每波新增一批字段', repeat: '连续多轮' },
    { method: 'POST', path: '/eslab-demo-explode/_refresh', note: '刷新后才能读到新字段' },
    { method: 'GET', path: '/eslab-demo-explode/_mapping', note: '字段数一轮轮往上走' },
    { method: 'GET', path: '/eslab-demo-explode/_count', note: '文档数基本没变，字段数却在爆炸' },
    { method: 'POST', path: '/_bulk', note: '继续加字段直到撞上 index.mapping.total_fields.limit', repeat: '触发上限' },
    {
      method: 'PUT',
      path: '/eslab-products-v2/_doc/__probe__',
      note: '往 strict 索引塞未知字段，直接被拒',
      body: { sku: 'SKU-PROBE', title: '探测文档', unknown_field_xyz: 1 },
    },
    { method: 'DELETE', path: '/eslab-demo-explode', note: '清理实验索引' },
  ],

  // ── 第 2 章 分词 ───────────────────────────────────────
  'm2-1': [
    { method: 'POST', path: '/eslab-products-v2/_analyze', note: 'standard：中文按单字切', body: { analyzer: 'standard', text: '小米无线蓝牙耳机主动降噪长续航' } },
    { method: 'POST', path: '/eslab-products-v2/_analyze', note: 'ik_smart：最粗粒度，少而准', body: { analyzer: 'ik_smart', text: '小米无线蓝牙耳机主动降噪长续航' } },
    { method: 'POST', path: '/eslab-products-v2/_analyze', note: 'ik_max_word：最细粒度，多而全', body: { analyzer: 'ik_max_word', text: '小米无线蓝牙耳机主动降噪长续航' } },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '查询期用 ik_smart 分词',
      dsl: 'match title · analyzer=ik_smart',
      body: { size: 0, query: { match: { title: { query: '蓝牙耳机', analyzer: 'ik_smart' } } } },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '查询期换成 standard，命中数会变',
      dsl: 'match title · analyzer=standard',
      body: { size: 0, query: { match: { title: { query: '蓝牙耳机', analyzer: 'standard' } } } },
    },
  ],
  'm2-2': [
    { method: 'POST', path: '/eslab-products-v2/_search', note: '正常查询：查询期沿用索引期分词器', dsl: 'match title', body: { size: 0, query: { match: { title: '蓝牙耳机' } } } },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '强行用 standard 分词：词项与索引期对不上',
      dsl: 'match title · analyzer=standard',
      body: { size: 0, query: { match: { title: { query: '蓝牙耳机', analyzer: 'standard' } } } },
    },
    { method: 'POST', path: '/eslab-products-v2/_analyze', note: '索引期切出来的词', body: { analyzer: 'ik_smart', text: '蓝牙耳机' } },
    { method: 'POST', path: '/eslab-products-v2/_analyze', note: '查询期切出来的词，两边对照', body: { analyzer: 'standard', text: '蓝牙耳机' } },
  ],

  // ── 第 3 章 写入 ───────────────────────────────────────
  'm3-1': [
    { method: 'PUT', path: '/eslab-demo-write', note: '建索引，准备做单条写入对照', body: { settings: { number_of_shards: 1, number_of_replicas: 0 } } },
    { method: 'POST', path: '/eslab-demo-write/_doc', note: '每条文档一次 HTTP 往返（N+1 的写入版）', repeat: '× 300 次' },
    { method: 'DELETE', path: '/eslab-demo-write', note: '清空重来，换成 bulk' },
    { method: 'PUT', path: '/eslab-demo-write', note: '同结构再建一次', body: { settings: { number_of_shards: 1, number_of_replicas: 0 } } },
    { method: 'POST', path: '/_bulk', note: '一次请求带 N 条，NDJSON', repeat: '分批多次' },
    { method: 'GET', path: '/eslab-demo-write/_refresh', note: '刷新后统计条数' },
    { method: 'GET', path: '/eslab-demo-write/_count', note: '核对两种写法的条数一致' },
    { method: 'DELETE', path: '/eslab-demo-write', note: '清理' },
  ],
  'm3-2': [
    { method: 'PUT', path: '/eslab-demo-id-auto', note: 'A 组：不指定 _id 的索引', body: { settings: { number_of_shards: 1, number_of_replicas: 0 } } },
    { method: 'POST', path: '/_bulk', note: 'A 组：由 ES 生成 _id', repeat: '× 3000 条' },
    { method: 'PUT', path: '/eslab-demo-id-biz', note: 'B 组：带业务 _id 的索引', body: { settings: { number_of_shards: 1, number_of_replicas: 0 } } },
    { method: 'POST', path: '/_bulk', note: 'B 组：_id 由业务主键决定', repeat: '× 3000 条' },
    { method: 'GET', path: '/eslab-demo-id-auto/_stats/indexing', note: 'A 组索引耗时（自动 id 需要额外生成与校验）' },
    { method: 'GET', path: '/eslab-demo-id-biz/_stats/indexing', note: 'B 组索引耗时，与 A 组对照' },
    { method: 'DELETE', path: '/eslab-demo-id-auto', note: '清理' },
    { method: 'DELETE', path: '/eslab-demo-id-biz', note: '清理' },
  ],
  'm3-3': [
    {
      method: 'PUT',
      path: '/eslab-demo-refresh',
      note: '先删后建：refresh_interval 保持默认 1s，sku 走精确匹配',
      body: {
        settings: { number_of_shards: 1, number_of_replicas: 0, refresh_interval: '1s' },
        mappings: { properties: { sku: { type: 'keyword' }, title: { type: 'text', analyzer: 'ik_max_word' } } },
      },
    },
    { method: 'POST', path: '/eslab-demo-refresh/_doc', note: '写入 RT-1，立刻按 sku 搜', body: { sku: 'RT-1', title: '近实时测试 商品一' } },
    { method: 'POST', path: '/eslab-demo-refresh/_search', note: '查不到：还在内存缓冲里，没成段', body: { size: 0, query: { term: { sku: 'RT-1' } } } },
    { method: 'POST', path: '/eslab-demo-refresh/_doc?refresh=wait_for', note: '写入 RT-2 并等可见，再搜', body: { sku: 'RT-2', title: '近实时测试 商品二' } },
    { method: 'POST', path: '/_bulk?refresh=false', note: 'bulk 不带 refresh：一批写完再统一刷新', repeat: '批量导入' },
    { method: 'POST', path: '/_bulk?refresh=wait_for', note: 'bulk 每批都等可见，计时对照', repeat: '批量导入' },
    { method: 'DELETE', path: '/eslab-demo-refresh', note: '清理' },
  ],

  // ── 第 4 章 查询 ───────────────────────────────────────
  'm4-1': [
    { method: 'POST', path: '/eslab-products-v2/_search', note: '不写 track_total_hits：total 只算到 10000 就停', dsl: 'size:0', body: { size: 0 } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '加上 track_total_hits: true：精确计数', dsl: 'track_total_hits: true', body: { size: 0, track_total_hits: true } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'size: 2：真的有文档返回，max_score 才有值', dsl: 'size:2', body: { size: 2, track_total_hits: true, query: { match: { title: '蓝牙耳机' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '裁剪返回字段：单条文档只留 title 与 price', dsl: '_source', body: { size: 1, _source: ['title', 'price'] } },
  ],
  'm4-2': [
    { method: 'POST', path: '/eslab-products-v2/_search', note: '① 空请求体：等价 match_all，返回默认 10 条', dsl: '{}', body: {} },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '② 加 size: 0：只要统计，不要文档', dsl: 'size:0', body: { size: 0 } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '③ 加第一个条件：term 查 keyword 字段', dsl: 'term category', body: { size: 0, track_total_hits: true, query: { term: { category: '数码影音' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '④ 换成 match：只改查询类型，会分词', dsl: 'match title', body: { size: 0, track_total_hits: true, query: { match: { title: '蓝牙耳机' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '⑤ 换成 range：数值区间，两端都包含', dsl: 'range price', body: { size: 0, track_total_hits: true, query: { range: { price: { gte: 100, lte: 200 } } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '⑥ 包进 bool：must 放文本、filter 放价格', dsl: 'bool must+filter', body: { size: 0, track_total_hits: true, query: { bool: { must: [{ match: { title: '蓝牙耳机' } }], filter: [{ range: { price: { gte: 100, lte: 500 } } }] } } } },
  ],
  'm4-3': [
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'match 打在 text 上：正常全文检索', dsl: 'match title', body: { size: 0, track_total_hits: true, query: { match: { title: '蓝牙耳机' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'term 打在 keyword 上：精确整串，最快', dsl: 'term category', body: { size: 0, track_total_hits: true, query: { term: { category: '数码影音' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'term 打在 text 上：不分析，静默 0 命中', dsl: 'term title', body: { size: 0, track_total_hits: true, query: { term: { title: '蓝牙耳机' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'match 打在 keyword 上：能查但不分词', dsl: 'match category', body: { size: 0, track_total_hits: true, query: { match: { category: '数码影音' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'range：数值区间，走 BKD 树', dsl: 'range price 100~500', body: { size: 0, track_total_hits: true, query: { range: { price: { gte: 100, lte: 500 } } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'term 打在数值上：浮点精确等于极易 0 命中', dsl: 'term price=299', body: { size: 0, track_total_hits: true, query: { term: { price: 299 } } } },
  ],
  'm4-4': [
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '一次请求里放齐 must / filter / should / must_not',
      body: {
        size: 3,
        track_total_hits: true,
        query: {
          bool: {
            must: [{ match: { title: '蓝牙耳机' } }],
            filter: [{ term: { category: '数码影音' } }, { range: { price: { gte: 100, lte: 800 } } }, { term: { status: 1 } }],
            should: [{ term: { tags: '爆款' } }, { range: { rating: { gte: 4.5 } } }],
            must_not: [{ term: { brand: '二手' } }],
          },
        },
      },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '对照组：把同一条价格条件从 filter 挪进 must，看 _score 被抬高',
      dsl: 'bool.must [match, range]',
      body: {
        size: 3,
        track_total_hits: true,
        query: {
          bool: {
            must: [{ match: { title: '蓝牙耳机' } }, { range: { price: { gte: 100, lte: 800 } } }],
            filter: [{ term: { category: '数码影音' } }],
          },
        },
      },
    },
  ],
  'm4-5': [
    { method: 'POST', path: '/eslab-products-v2/_search', note: '默认 operator: or —— 三个词命中任意一个即算', dsl: 'operator: or', body: { size: 0, track_total_hits: true, query: { match: { title: '蓝牙 降噪 耳机' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'minimum_should_match: 2 —— 三词里至少中两个', dsl: 'msm: 2', body: { size: 0, track_total_hits: true, query: { match: { title: { query: '蓝牙 降噪 耳机', minimum_should_match: '2' } } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'operator: and —— 三个词必须全中，最容易搜不到', dsl: 'operator: and', body: { size: 0, track_total_hits: true, query: { match: { title: { query: '蓝牙 降噪 耳机', operator: 'and' } } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'boost: 5 —— 命中数不变，只有分数被抬高', dsl: 'boost: 5', body: { size: 1, track_total_hits: true, query: { bool: { should: [{ term: { tags: { value: '爆款', boost: 5 } } }, { term: { category: '数码影音' } }] } } } },
  ],
  'm4-6': [
    { method: 'GET', path: '/_nodes/stats/indices/request_cache', note: '先读一次 request cache 命中数' },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'A 组：同一谓词放 must —— 每个命中都要算分',
      dsl: 'bool.must [range, term]',
      repeat: '× 5 轮计时',
      body: {
        size: 20,
        track_total_hits: false,
        query: { bool: { must: [{ range: { price: { gte: 100, lte: 800 } } }, { term: { status: 1 } }] } },
      },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'B 组：同谓词放 filter —— 不算分、可缓存',
      dsl: 'bool.filter [range, term]',
      repeat: '× 5 轮计时',
      body: {
        size: 20,
        track_total_hits: false,
        query: { bool: { filter: [{ range: { price: { gte: 100, lte: 800 } } }, { term: { status: 1 } }] } },
      },
    },
    { method: 'GET', path: '/_nodes/stats/indices/request_cache', note: '再看命中数，差值就是 filter 省下的' },
  ],

  // ── 第 5 章 聚合与排序 ─────────────────────────────────
  'm5-1': [
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'terms 聚合取 top5 类目，注意 doc_count_error_upper_bound',
      body: { size: 0, aggs: { by_category: { terms: { field: 'category', size: 5 } } } },
    },
    { method: 'GET', path: '/eslab-products-v2/_count', note: '总数：分片间只取近似 top5，加起来不等于它' },
  ],
  'm5-2': [
    { method: 'GET', path: '/eslab-products-v2/_mapping', note: '看 title 有没有 keyword 子字段' },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '直接按 text 排序 —— fielddata 报错',
      dsl: 'sort title',
      body: { size: 3, _source: ['title', 'price'], sort: [{ title: 'asc' }] },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '改按 title.keyword 排序，通过',
      dsl: 'sort title.keyword',
      body: { size: 3, _source: ['title', 'price'], sort: [{ 'title.keyword': 'asc' }] },
    },
  ],
  'm5-3': [
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '一次请求算完：类目 → 品牌 → 价格统计，再挂一个月度直方图',
      body: {
        size: 0,
        query: { term: { status: 1 } },
        aggs: {
          by_category: {
            terms: { field: 'category', size: 4 },
            aggs: {
              top_brands: { terms: { field: 'brand', size: 2 }, aggs: { price_stats: { stats: { field: 'price' } } } },
              price_p95: { percentiles: { field: 'price', percents: [50, 95] } },
            },
          },
          by_month: {
            date_histogram: { field: 'created_at', calendar_interval: 'month', min_doc_count: 1 },
            aggs: { avg_price: { avg: { field: 'price' } } },
          },
        },
      },
    },
  ],

  // ── 第 6 章 分页 ───────────────────────────────────────
  'm6-1': [
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'from=9990：超过 10000 窗口，直接报错',
      dsl: 'from=9990 size=20',
      body: { from: 9990, size: 20 },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'from 依次 0 / 1000 / 5000 / 9000，每档计时',
      dsl: 'from=0/1000/5000/9000',
      repeat: '× 4 档',
      body: { from: 5000, size: 20, track_total_hits: false },
    },
  ],
  'm6-2': [
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '对照组：from+size 取第 901 条起',
      body: { from: 900, size: 100, sort: [{ price: 'asc' }, { sku: 'asc' }] },
    },
    { method: 'POST', path: '/eslab-products-v2/_pit?keep_alive=1m', note: '开一个 PIT：给这次翻页一个固定的索引快照' },
    { method: 'POST', path: '/_search', note: '带 pit + search_after 往后翻，每页都只读一页', repeat: '多页' },
    { method: 'DELETE', path: '/_pit', note: '关掉 PIT，释放快照' },
  ],
  'm6-3': [
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'profile: true，拿到每个查询节点的耗时',
      body: {
        size: 10,
        profile: true,
        query: {
          bool: {
            must: [{ match: { title: '蓝牙耳机' } }],
            filter: [{ term: { category: '数码影音' } }, { range: { price: { gte: 100 } } }],
          },
        },
        sort: [{ price: 'asc' }],
      },
    },
    { method: 'GET', path: '/eslab-products-v2/_stats/segments,store,docs', note: '段数、体积、文档数 —— 查询代价的物理来源' },
    { method: 'GET', path: '/_nodes/stats/indices/search', note: '节点级查询统计，核对 open_contexts' },
  ],

  // ── 第 7 章 容量与优化 ─────────────────────────────────
  'm7-1': [
    { method: 'GET', path: '/eslab-products-v1/_stats/docs,store,segments', note: 'v1：单分片的体积与段数' },
    { method: 'GET', path: '/eslab-products-v1/_settings', note: 'v1 的分片数' },
    { method: 'GET', path: '/eslab-products-v2/_stats/docs,store,segments', note: 'v2：3 分片的体积与段数' },
    { method: 'GET', path: '/eslab-products-v2/_settings', note: 'v2 的分片数' },
    {
      method: 'POST',
      path: '/eslab-products-v1/_search',
      note: '同样取 100 条，两索引各计时',
      body: { size: 100, track_total_hits: false, _source: ['sku', 'title', 'price'] },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'v2 上同一条查询，对照耗时',
      body: { size: 100, track_total_hits: false, _source: ['sku', 'title', 'price'] },
    },
  ],
  'm7-2': [
    {
      method: 'GET',
      path: '/_cat/indices/eslab-*?format=json&h=index,health,pri,rep,docs.count,store.size,segments.count',
      note: '先看两个索引的现状',
    },
    { method: 'GET', path: '/eslab-products-v2/_count', note: '确认实验数据规模' },
  ],
  // ── 第 8 章 索引变更与零停机 ─────────────────────────────
  'm8-1': [
    { method: 'PUT', path: '/eslab-goods-v3', note: '建新索引，结构与 v2 相同，只是换了个名字', dsl: 'v3 建索引' },
    { method: 'POST', path: '/_reindex', note: '把 v2 的 2 万条搬到 v3', body: { source: { index: 'eslab-products-v2' }, dest: { index: 'eslab-goods-v3' }, max_docs: 20000 } },
    { method: 'POST', path: '/_aliases', note: '把别名挂到 v3，写索引标记为 true', body: { actions: [{ add: { index: 'eslab-goods-v3', alias: 'eslab-goods', is_write_index: true } }] } },
    { method: 'GET', path: '/_cat/aliases/eslab-goods?format=json&h=alias,index,is_write_index', note: '确认别名指向与写索引' },
    { method: 'POST', path: '/eslab-goods/_search', note: '经别名查询，与直连 v3 对照', body: { size: 0, track_total_hits: true } },
  ],
  'm8-2': [
    { method: 'PUT', path: '/eslab-goods-v4', note: '新 Mapping：多一个 selling_point，ignore_above 从 256 改 128', dsl: 'v4 建索引' },
    { method: 'POST', path: '/_reindex', note: '从 v2 搬 2 万条到 v4', body: { source: { index: 'eslab-products-v2' }, dest: { index: 'eslab-goods-v4' }, max_docs: 20000 } },
    { method: 'GET', path: '/eslab-goods-v4/_count', note: '切换前必须确认目标索引有数据' },
    { method: 'POST', path: '/_aliases', note: '一次请求完成 remove 旧指向 + add 新指向', body: { actions: [{ remove: { index: 'eslab-goods-v3', alias: 'eslab-goods' } }, { add: { index: 'eslab-goods-v4', alias: 'eslab-goods', is_write_index: true } }] } },
    { method: 'GET', path: '/_cat/aliases/eslab-goods?format=json&h=alias,index,is_write_index', note: '切换后别名只剩 v4 一行' },
  ],
  'm8-3': [
    { method: 'DELETE', path: '/eslab-goods-v5', note: '每次从空索引开始，否则进度瞬间达成', cond: '不存在则跳过' },
    { method: 'PUT', path: '/eslab-goods-v5', note: '重建目标索引', dsl: 'v5 建索引' },
    { method: 'POST', path: '/_reindex?wait_for_completion=false&requests_per_second=4000', note: '异步重建，立刻返回 task id', body: { source: { index: 'eslab-goods-v4' }, dest: { index: 'eslab-goods-v5' }, max_docs: 20000 } },
    { method: 'GET', path: '/_tasks/{taskId}', note: '轮询任务状态：completed / created / batches', repeat: '每 250ms' },
    { method: 'GET', path: '/eslab-goods-v5/_count', note: '完成后确认 2 万条都到了' },
  ],
  'm8-4': [
    { method: 'GET', path: '/_cat/indices/eslab-goods-v3?format=json&h=index,docs.count,segments.count', note: '先记下合并前的段数' },
    { method: 'POST', path: '/eslab-goods-v3/_forcemerge?max_num_segments=1', note: '每分片合并成 1 段，3 分片结果仍是 3 段' },
    { method: 'POST', path: '/eslab-goods-live/_rollover', note: '超过 4000 条就滚动，新建 -000002 并迁移写别名', body: { conditions: { max_docs: 4000 } } },
    { method: 'PUT', path: '/eslab-goods-live/_doc/ROLL-1?refresh=true', note: '写一条验证文档确实落到新索引', body: { sku: 'ROLL-1', title: '滚动索引验证商品' } },
    { method: 'GET', path: '/_cat/aliases/eslab-goods-live?format=json&h=alias,index,is_write_index', note: '写索引已变成 -000002' },
  ],

  // ── 第 9 章 相关性工程 ───────────────────────────────────
  'm9-1': [
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'best_fields：最高分 + 0.3×其余字段', dsl: 'best_fields', body: { size: 3, track_total_hits: true, query: { multi_match: { query: '小米 降噪', fields: ['title^3', 'description', 'brand^2'], type: 'best_fields', tie_breaker: 0.3, minimum_should_match: '70%' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'most_fields：各字段分数直接相加，分数虚高', dsl: 'most_fields', body: { size: 3, track_total_hits: true, query: { multi_match: { query: '小米 降噪', fields: ['title^3', 'description', 'brand^2'], type: 'most_fields', minimum_should_match: '70%' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'cross_fields：多字段当成一个大字段', dsl: 'cross_fields', body: { size: 3, track_total_hits: true, query: { multi_match: { query: '小米 降噪', fields: ['title^3', 'description', 'brand^2'], type: 'cross_fields', minimum_should_match: '70%' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'most_fields + tie_breaker：会退化成 best_fields', dsl: 'most_fields + tb', body: { size: 3, track_total_hits: true, query: { multi_match: { query: '小米 降噪', fields: ['title^3', 'description', 'brand^2'], type: 'most_fields', tie_breaker: 0.3, minimum_should_match: '70%' } } } },
  ],
  'm9-2': [
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'match：词出现即可，不看顺序', dsl: 'match', body: { size: 0, track_total_hits: true, query: { match: { title: '蓝牙耳机' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'match_phrase：必须相邻且同序', dsl: 'phrase', body: { size: 0, track_total_hits: true, query: { match_phrase: { title: '蓝牙耳机' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: '颠倒词序：slop 0 为 0 条，slop 3 才命中', dsl: 'slop', body: { size: 0, track_total_hits: true, query: { match_phrase: { title: { query: '降噪 蓝牙', slop: 3 } } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'prefix：前缀已知，可用倒排剪枝', dsl: 'prefix', body: { size: 0, track_total_hits: true, query: { prefix: { 'title.keyword': '小米' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'wildcard 前导通配：无法用前缀剪枝', dsl: 'wildcard', body: { size: 0, track_total_hits: true, query: { wildcard: { 'title.keyword': '*耳机*' } } } },
  ],
  'm9-3': [
    { method: 'POST', path: '/eslab-goods-search/_search', note: '基线：纯 BM25 的 Top10', dsl: 'baseline', body: { size: 10, track_total_hits: true, query: { multi_match: { query: '蓝牙耳机', fields: ['title^2', 'description'], type: 'best_fields' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: '叠加时间衰减、销量对数、在售加权', dsl: 'function_score', body: { size: 10, track_total_hits: true, query: { function_score: { query: { multi_match: { query: '蓝牙耳机', fields: ['title^2', 'description'] } }, functions: [{ gauss: { created_at: { origin: 'now-30d', scale: '30d', decay: 0.5 } }, weight: 2 }, { field_value_factor: { field: 'sales', modifier: 'log1p', factor: 0.1 } }, { filter: { term: { status: 1 } }, weight: 1.5 }], score_mode: 'sum', boost_mode: 'multiply' } } } },
    { method: 'GET', path: '/eslab-goods-search/_explain/{docId}', note: '看这一分是谁贡献的：function score, product of', repeat: '取 Top1 的 _id' },
  ],
  'm9-4': [
    { method: 'POST', path: '/eslab-goods-search/_search', note: '高亮：命中的词被包上 em 标记', dsl: 'highlight', body: { size: 5, query: { match: { title: '蓝牙耳机' } }, highlight: { fields: { title: { number_of_fragments: 1 }, description: { fragment_size: 40 } } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: '按品牌折叠：每品牌只留销量最高的一条', dsl: 'collapse', body: { size: 5, track_total_hits: true, query: { match_all: {} }, collapse: { field: 'brand' }, sort: [{ sales: 'desc' }] } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: '折叠 + inner_hits：每组再带一条最贵的', dsl: 'inner_hits', body: { size: 3, track_total_hits: true, query: { match_all: {} }, collapse: { field: 'brand', inner_hits: { name: 'top_by_price', size: 1, sort: [{ price: 'desc' }] } }, sort: [{ sales: 'desc' }] } },
  ],
  'm9-5': [
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'completion：只从候选串开头匹配，_score 恒为 0', dsl: 'completion', body: { size: 0, suggest: { comp: { prefix: '小米', completion: { field: 'title_suggest', size: 5 } } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: '同一个输入换成中段词「蓝牙」，候选变成 0', dsl: 'completion 中段', body: { size: 0, suggest: { comp: { prefix: '蓝牙', completion: { field: 'title_suggest', size: 5 } } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'bool_prefix：词中间也能补全', dsl: 'bool_prefix', body: { size: 0, track_total_hits: true, query: { multi_match: { query: '蓝牙耳', type: 'bool_prefix', fields: ['title_sayt', 'title_sayt._2gram', 'title_sayt._3gram'] } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'match_phrase_prefix 对照：要求从词头开始', dsl: 'phrase_prefix', body: { size: 0, track_total_hits: true, query: { match_phrase_prefix: { title: '蓝牙耳' } } } },
  ],
  'm9-6': [
    { method: 'PUT', path: '/eslab-goods-v6', note: '新索引：查询期分析器带 synonym_graph', dsl: 'v6 建索引' },
    { method: 'POST', path: '/eslab-goods-v6/_analyze', note: '显式指定 analyzer 才能看到展开', body: { analyzer: 'goods_syn_search', text: 'TWS' } },
    { method: 'POST', path: '/_reindex', note: '从别名指向的 v4 搬 2 万条到 v6', body: { source: { index: 'eslab-goods' }, dest: { index: 'eslab-goods-v6' }, max_docs: 20000 } },
    { method: 'POST', path: '/_aliases', note: '原子切换：v4 → v6', body: { actions: [{ remove: { index: 'eslab-goods-v4', alias: 'eslab-goods' } }, { add: { index: 'eslab-goods-v6', alias: 'eslab-goods', is_write_index: true } }] } },
    { method: 'POST', path: '/eslab-goods/_search', note: '切换前 0 条，切换后 1,303 条', cond: '前后各跑一次', body: { size: 0, track_total_hits: true, query: { match: { title: 'TWS' } } } },
  ],

  // ── 第 10 章 增量写入与并发 ───────────────────────────────
  'm10-1': [
    { method: 'PUT', path: '/eslab-goods-ops', note: '重建实验索引（ops 会被复位）', dsl: 'ops 建索引' },
    { method: 'POST', path: '/_bulk', note: '2010 条基础数据：2000 条商品 + 10 条固定 id 的运营文档', repeat: '分批' },
    { method: 'POST', path: '/eslab-goods-ops/_update/ops-1', note: 'doc 是合并：只改 stock，其他字段保留', body: { doc: { stock: 42 } } },
    { method: 'POST', path: '/eslab-goods-ops/_update/ops-1', note: 'script 是读改写：库存减 5、版本 +1', body: { script: { source: 'ctx._source.stock += params.d; ctx._source.version += 1', params: { d: -5 } } } },
    { method: 'POST', path: '/eslab-goods-ops/_update/ops-99', note: 'upsert：不存在就用 doc 建一条', body: { doc: { sku: 'OPS-99', title: 'upsert 出来的新商品', stock: 1 }, doc_as_upsert: true } },
  ],
  'm10-2': [
    { method: 'GET', path: '/eslab-goods-ops/_doc/ops-2', note: '先拿到 _seq_no 与 _primary_term 作为凭证' },
    { method: 'POST', path: '/eslab-goods-ops/_update/ops-2?if_seq_no={seq}&if_primary_term={term}', note: '带凭证更新，成功推进版本', body: { doc: { stock: 101 } } },
    { method: 'POST', path: '/eslab-goods-ops/_update/ops-2?if_seq_no={旧值}&if_primary_term={term}', note: '拿旧凭证再写：409 version_conflict_engine_exception', body: { doc: { stock: 999 } } },
    { method: 'POST', path: '/eslab-goods-ops/_update/ops-2?retry_on_conflict=10', note: '并发自增 100 次，库存精确 +100', repeat: '4 线程 × 25 次', body: { script: { source: 'ctx._source.stock += 1' } } },
  ],
  'm10-3': [
    { method: 'POST', path: '/_bulk', note: '初始 2000 条，显式 _id 为 P-0…P-1999', repeat: '分批' },
    { method: 'POST', path: '/_bulk', note: '混合动作：50 update / 30 delete / 5 create 冲突 / 10 index', dsl: '95 个动作' },
    { method: 'POST', path: '/eslab-goods-ops/_update_by_query?conflicts=proceed&refresh=true', note: '扫描 + 逐条改：价格低于 100 的乘 1.1', body: { query: { range: { price: { lt: 100 } } }, script: { source: 'ctx._source.price = Math.round(ctx._source.price * 1.1 * 100) / 100.0' } } },
    { method: 'GET', path: '/eslab-goods-ops/_count', note: '2000 − 30 + 10 = 1980' },
  ],
  'm10-4': [
    { method: 'POST', path: '/eslab-goods-ops/_delete_by_query?conflicts=proceed&refresh=true', note: '删除下架(4)与清仓(5)：约 400 条', body: { query: { terms: { status: [4, 5] } } } },
    { method: 'POST', path: '/eslab-goods-ops/_delete_by_query?conflicts=proceed&refresh=true', note: '同样请求再跑一次，应当删 0 条', dsl: '第二次' },
    { method: 'PUT', path: '/eslab-goods-ops/_doc/ops-3?op_type=create&refresh=true', note: '已存在 → 409，不覆盖', body: { sku: 'OPS-3', stock: 777 } },
    { method: 'PUT', path: '/eslab-goods-ops/_doc/ops-3?refresh=true', note: '默认 PUT 是覆盖写，版本 +1', body: { sku: 'OPS-3', stock: 888 } },
  ],

  // ── 第 11 章 索引工厂 ────────────────────────────────────
  'm11-1': [
    { method: 'PUT', path: '/_index_template/eslab-goods-template', note: '模板：匹配 eslab-goods-tpl-*，1 分片、refresh 5s', dsl: 'priority 200' },
    { method: 'POST', path: '/_index_template/_simulate/eslab-goods-template', note: '模拟模板本身，无副作用' },
    { method: 'POST', path: '/_index_template/_simulate_index/eslab-goods-tpl-2026', note: '模拟某个索引名最终会得到的 settings' },
    { method: 'PUT', path: '/eslab-goods-tpl-2026', note: '空 body 建索引，全部来自模板', body: {} },
    { method: 'GET', path: '/eslab-goods-tpl-2026/_settings', note: '改模板后它仍是 5s，模板不追溯' },
  ],
  'm11-2': [
    { method: 'PUT', path: '/eslab-goods-dyn', note: '带 dynamic_templates：attrs 映射成 flattened', dsl: 'match attrs' },
    { method: 'PUT', path: '/eslab-goods-dyn-ctrl', note: '对照组：裸 dynamic，让 ES 自己猜' },
    { method: 'POST', path: '/_bulk', note: '两个索引各 50 条，每条 24 个 attrs.* 字段', repeat: '× 2' },
    { method: 'GET', path: '/eslab-goods-dyn/_mapping', note: 'attrs 只有一个 type=flattened' },
    { method: 'POST', path: '/eslab-goods-dyn-ctrl/_search', note: 'term 打在 text 上：0 条，静默不报错', body: { size: 0, track_total_hits: true, query: { term: { 'attrs.color': '黑色' } } } },
  ],
  'm11-3': [
    { method: 'PUT', path: '/_ingest/pipeline/eslab-goods-enrich', note: '五个处理器：set / rename / gsub / script / remove', dsl: 'pipeline 定义' },
    { method: 'POST', path: '/_ingest/pipeline/_simulate', note: '模拟一份文档，看每个字段处理后的值', dsl: '不写入' },
    { method: 'PUT', path: '/eslab-goods-ingest/_doc/INGEST-1?pipeline=eslab-goods-enrich&refresh=true', note: '带 pipeline 写入：改名、算税价、删内部字段', body: { old_sku: 'SKU-INGEST-1', title: '  蓝牙   耳机  主动降噪 ', price: 899, internal_note: '内部备注' } },
    { method: 'GET', path: '/eslab-goods-ingest/_doc/INGEST-1', note: '读回来看处理结果：price_with_tax=1015.87' },
  ],
};

export function callsOf(levelId: string): CallSpec[] {
  return LEVEL_CALLS[levelId] ?? [];
}
