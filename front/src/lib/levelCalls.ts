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
    { method: 'GET', path: '/_cluster/health', note: '集群状态：节点数、活跃分片、未分配分片数' },
    { method: 'GET', path: CAT_NODES, note: '每个节点的版本、堆占用、CPU、剩余磁盘' },
    {
      method: 'GET',
      path: '/_cat/indices/eslab-*?format=json&h=index,health,docs.count,store.size,pri,rep',
      note: '只列本项目的索引（空集群时返回空数组）',
    },
    {
      method: 'GET',
      path: '/_cat/shards?format=json&h=index,shard,prirep,state,unassigned.reason',
      note: '未分配分片属于谁、原因是什么 —— yellow 的归因证据',
    },
    { method: 'GET', path: '/_cat/plugins?format=json&h=component,version', note: '确认 analysis-ik 是否已安装' },
  ],
  'm0-2': [
    {
      method: 'PUT',
      path: '/eslab-demo-defaults',
      note: '空 body 建探针索引：看「什么都不写」时 ES 给什么默认值',
      body: {},
    },
    {
      method: 'GET',
      path: '/eslab-demo-defaults/_settings?include_defaults=true',
      note: '默认 1 主分片 / 1 副本 / refresh 1s',
    },
    {
      method: 'GET',
      path: '/_cat/shards/eslab-demo-defaults?format=json&h=shard,prirep,state,unassigned.reason',
      note: '单节点上副本必然 UNASSIGNED，索引一出生就 yellow',
    },
    { method: 'DELETE', path: '/eslab-demo-defaults', note: '读完即删，不留垃圾' },
    {
      method: 'PUT',
      path: '/eslab-products-v1',
      note: '显式建 v1：1 分片 / 0 副本 / refresh 1s，一个字都不写 mappings',
      body: { settings: { number_of_shards: 1, number_of_replicas: 0, refresh_interval: '1s' } },
      cond: '索引已存在则跳过',
    },
    { method: 'GET', path: '/eslab-products-v1/_settings', note: '读回分片数、副本数、refresh_interval' },
    { method: 'GET', path: '/eslab-products-v1/_mapping', note: '还没写入，所以 mapping 是空的' },
    {
      method: 'GET',
      path: '/_cat/shards/eslab-products-v1?format=json&h=index,shard,prirep,state,docs',
      note: '分片落在哪、是主还是副本、有没有 UNASSIGNED',
    },
    {
      method: 'PUT',
      path: '/eslab-products-v1/_settings',
      note: '把主分片改成 2 —— 预期 400，主分片数不可原地修改',
      body: { index: { number_of_shards: 2 } },
    },
  ],
  'm0-3': [
    {
      method: 'DELETE',
      path: '/eslab-products-v1/_doc/SKU2026000001?refresh=false',
      note: '清场：删掉上一轮的同 _id 文档',
      cond: '不存在时返回 404，视为正常起点',
    },
    { method: 'POST', path: '/eslab-products-v1/_refresh', note: '让删除生效，保证「写入前一定搜不到」' },
    {
      method: 'PUT',
      path: '/eslab-products-v1/_doc/SKU2026000001?refresh=false',
      note: '写一条固定 _id 的文档：不立即刷新',
      body: { sku: 'SKU2026000001', title: '声阔 主动降噪头戴耳机 Q45', price: 899 },
    },
    { method: 'GET', path: '/eslab-products-v1/_doc/SKU2026000001', note: 'GET 走实时路径，立刻能读到' },
    {
      method: 'POST',
      path: '/eslab-products-v1/_count',
      note: '用 ids 查询精确数这条 _id：refresh 前应为 0',
      body: { query: { ids: { values: ['SKU2026000001'] } } },
    },
    { method: 'POST', path: '/eslab-products-v1/_refresh', note: '手动刷新，把内存缓冲写成可搜索的段' },
    {
      method: 'POST',
      path: '/eslab-products-v1/_count',
      note: '同一条查询：现在应为 1（且永远只有 1 条）',
      body: { query: { ids: { values: ['SKU2026000001'] } } },
    },
  ],
  'm0-4': [
    { method: 'GET', path: '/eslab-products-v1/_count', note: '起点条数：已达标就不重复写（幂等）' },
    {
      method: 'PUT',
      path: '/eslab-products-v1/_settings',
      note: '导入期关掉自动刷新',
      body: { index: { refresh_interval: -1 } },
      cond: 'v1 不足 2 万时才写',
    },
    { method: 'POST', path: '/_bulk', note: 'NDJSON 批量写入：action 行 + source 行交替，每批 2000 条', repeat: '分批多次' },
    {
      method: 'PUT',
      path: '/eslab-products-v1/_settings',
      note: '导完恢复刷新（finally 里执行，异常路径也恢复）',
      body: { index: { refresh_interval: '1s' } },
    },
    { method: 'POST', path: '/eslab-products-v1/_refresh', note: '手动刷新，把内存缓冲落成段' },
    { method: 'GET', path: '/eslab-products-v1/_stats/segments', note: '导入后的段数、体积' },
    {
      method: 'PUT',
      path: '/eslab-goods-batchtest',
      note: '一次性对照索引：批次大小 A/B 与导入进度都写在它上面',
      body: { settings: { number_of_shards: 1, number_of_replicas: 0, refresh_interval: '1s' } },
    },
    { method: 'POST', path: '/_bulk', note: '同样 1000 条，按每批 100 条写：10 次请求', repeat: '10 批' },
    { method: 'POST', path: '/_bulk', note: '重建后同样 1000 条，按每批 1000 条写：1 次请求' },
    { method: 'GET', path: '/_cat/indices/eslab-goods-batchtest?format=json&h=index,segments.count,store.size', note: '两组的段数与体积' },
    { method: 'DELETE', path: '/eslab-goods-batchtest', note: '删掉一次性对照索引' },
    { method: 'GET', path: '/eslab-products-v1/_count', note: '核对最终条数' },
  ],

  // ── 第 1 章 Mapping ────────────────────────────────────
  'm1-1': [
    {
      method: 'PUT',
      path: '/eslab-demo-auto',
      note: '建一个只有 settings 的临时索引（mappings 一个字都不写）',
      body: { settings: { number_of_shards: 1, number_of_replicas: 0 } },
    },
    {
      method: 'PUT',
      path: '/eslab-demo-auto/_doc/1',
      note: '第一条：price 数字、stock 整数、tags 数组、created_at 带时区、specs 嵌套对象',
      body: {
        sku: 'SKU-0001', title: '小米 无线蓝牙耳机', price: 299.0, stock: 120, status: 1,
        tags: ['数码', '音频'], created_at: '2026-10-01T10:00:00+08:00',
        specs: { color: '曜石黑', weight_g: 250 },
      },
    },
    {
      method: 'PUT',
      path: '/eslab-demo-auto/_doc/2',
      note: '第二条：price 写成字符串 "599.00"、tags 写成单值，还多出一个 promo 字段',
      body: {
        sku: 'SKU-0002', title: '机械键盘 87 键', price: '599.00', stock: '88', status: 3,
        tags: '数码', created_at: '2026-10-02', promo: true,
      },
    },
    { method: 'POST', path: '/eslab-demo-auto/_refresh', note: '不刷新就查，测到的是 1 秒可见性而不是映射' },
    { method: 'GET', path: '/eslab-demo-auto/_mapping', note: '看 ES 把每个字段猜成了什么类型、加了哪些子字段' },
    {
      method: 'POST',
      path: '/eslab-demo-auto/_search',
      note: 'term 查 sku（text 侧：词项是 sku / 0001，整串查不中）',
      dsl: 'term sku="SKU-0001"',
      body: { size: 0, track_total_hits: true, query: { term: { sku: 'SKU-0001' } } },
    },
    {
      method: 'POST',
      path: '/eslab-demo-auto/_search',
      note: '同一值改查 sku.keyword（1 条）',
      dsl: 'term sku.keyword="SKU-0001"',
      body: { size: 0, track_total_hits: true, query: { term: { 'sku.keyword': 'SKU-0001' } } },
    },
    {
      method: 'POST',
      path: '/eslab-demo-auto/_search',
      note: 'term 查 tags（text 侧）与 tags.keyword（keyword 侧）各一次',
      dsl: 'term tags="数码" / term tags.keyword="数码"',
      body: { size: 0, track_total_hits: true, query: { term: { tags: '数码' } } },
    },
    {
      method: 'POST',
      path: '/eslab-demo-auto/_search',
      note: 'match 查 title（走分词，能中）；range 查 price / stock / created_at 各一次',
      dsl: 'match title + range price≥500 / stock≥100 / created_at≥2026-10-02',
      body: { size: 0, track_total_hits: true, query: { match: { title: '蓝牙耳机' } } },
    },
    {
      method: 'POST',
      path: '/eslab-demo-auto/_search',
      note: '按 text 字段排序 —— 故意触发 fielddata 报错；再按 sku.keyword 排序成功',
      dsl: 'sort sku asc  →  sort sku.keyword asc',
      body: { size: 1, sort: [{ sku: 'asc' }] },
    },
    {
      method: 'PUT',
      path: '/eslab-demo-auto/_doc/3',
      note: 'status 已被定型成 long，再写「未知」直接 400',
      body: { sku: 'SKU-0003', status: '未知' },
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
    {
      method: 'PUT',
      path: '/eslab-products-v2/_doc/__m12_probe__?refresh=true',
      note: '写一条字段齐全的合法商品：14 个字段全部命中 mapping',
    },
    { method: 'GET', path: '/eslab-products-v2/_doc/__m12_probe__', note: '读回来：found=true、字段类型对得上' },
    { method: 'DELETE', path: '/eslab-products-v2/_doc/__m12_probe__?refresh=true', note: '删掉探针，条数回到原值' },
    {
      method: 'PUT',
      path: '/eslab-products-v2/_doc/__m12_probe___bad',
      note: '带未知字段 color_size：strict 当场 400',
      body: { sku: 'SKU-PROBE', title: '探测文档', price: 1, category: '数码影音', color_size: '黑/XL' },
    },
  ],
  'm1-3': [
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '取一条真实文档，拿到完整标题做精确匹配对照',
      body: { size: 1, sort: [{ sku: 'asc' }], query: { match: { title: '蓝牙耳机' } } },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'term 打在 text 字段上：词项不匹配，0 命中',
      dsl: 'term title="蓝牙耳机"',
      body: { size: 0, track_total_hits: true, query: { term: { title: '蓝牙耳机' } } },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'match 打在同一字段：会分词，数千命中',
      dsl: 'match title="蓝牙耳机"',
      body: { size: 0, track_total_hits: true, query: { match: { title: '蓝牙耳机' } } },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'term 打在 title.keyword 上：整串相等才命中（标题取自上一条结果）',
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
      path: '/eslab-products-v2/_search',
      note: '按 text 排序：预期 400，Fielddata is disabled',
      dsl: 'sort title asc',
      body: { size: 1, sort: [{ title: 'asc' }] },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '改按 title.keyword 排序：成功',
      dsl: 'sort title.keyword asc',
      body: { size: 1, sort: [{ 'title.keyword': 'asc' }] },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'terms 聚合打在 text 上：同样被拒',
      dsl: 'terms(field=title)',
      body: { size: 0, aggs: { t: { terms: { field: 'title' } } } },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'terms 聚合打在 title.keyword 上：返回 top-3 桶',
      dsl: 'terms(field=title.keyword, size=3)',
      body: { size: 0, aggs: { t: { terms: { field: 'title.keyword', size: 3 } } } },
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
      note: 'A 组：建索引并打开 dynamic，属性名直接挂在根上',
      body: { settings: { number_of_shards: 1, number_of_replicas: 0 }, mappings: { dynamic: true } },
    },
    { method: 'POST', path: '/_bulk', note: 'A 组写 50 / 200 / 400 个属性，每波 2 条文档', repeat: '3 波' },
    { method: 'POST', path: '/eslab-demo-explode/_refresh', note: '刷新后才能读到新字段' },
    { method: 'GET', path: '/eslab-demo-explode/_mapping', note: '字段数一轮轮往上走（每个字符串字段还多一个 keyword 子字段）' },
    { method: 'POST', path: '/_bulk', note: '再加 150 个新属性：撞上 1000 字段上限，被拒绝' },
    {
      method: 'PUT',
      path: '/eslab-demo-explode',
      note: 'B 组：属性塞进 attrs 对象（object 只是分组）',
      body: {
        settings: { number_of_shards: 1, number_of_replicas: 0, refresh_interval: '1s' },
        mappings: { dynamic: true, properties: { attrs: { type: 'object', dynamic: true } } },
      },
    },
    { method: 'POST', path: '/_bulk', note: 'B 组写 200 个属性，字段数与平铺几乎一样' },
    {
      method: 'PUT',
      path: '/eslab-demo-explode',
      note: 'C 组：attrs 设 enabled:false —— 映射里只占 1 个字段，但搜不到',
      body: {
        settings: { number_of_shards: 1, number_of_replicas: 0, refresh_interval: '1s' },
        mappings: { dynamic: true, properties: { attrs: { type: 'object', enabled: false } } },
      },
    },
    { method: 'POST', path: '/_bulk', note: 'C 组写 400 个属性：字段数仍然是 1' },
    {
      method: 'POST',
      path: '/eslab-demo-explode/_search',
      note: '在 C 组上查 attrs.attribute_1：命中 0 条（属性只能从 _source 取）',
      body: { size: 0, query: { term: { 'attrs.attribute_1': 'v1' } } },
    },
    { method: 'DELETE', path: '/eslab-demo-explode', note: '清理实验索引（finally 里执行）' },
  ],

  // ── 第 2 章 分词 ───────────────────────────────────────
  'm2-1': [
    { method: 'POST', path: '/eslab-products-v2/_analyze', note: 'standard：中文按单字切（15 个 token）', body: { analyzer: 'standard', text: '小米无线蓝牙耳机主动降噪长续航' } },
    { method: 'POST', path: '/eslab-products-v2/_analyze', note: 'ik_smart：最长匹配优先（8 个词）', body: { analyzer: 'ik_smart', text: '小米无线蓝牙耳机主动降噪长续航' } },
    { method: 'POST', path: '/eslab-products-v2/_analyze', note: 'ik_max_word：穷举成词组合（9 个词，多出「机主」）', body: { analyzer: 'ik_max_word', text: '小米无线蓝牙耳机主动降噪长续航' } },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '查询期用 ik_smart：词项与索引对齐，命中 3,788 条',
      dsl: 'match title · analyzer=ik_smart',
      body: { size: 1, track_total_hits: true, query: { match: { title: { query: '蓝牙耳机', analyzer: 'ik_smart' } } } },
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '查询期换成 standard：单字对不上词项，掉到 682 条、分数也低',
      dsl: 'match title · analyzer=standard',
      body: { size: 1, track_total_hits: true, query: { match: { title: { query: '蓝牙耳机', analyzer: 'standard' } } } },
    },
    { method: 'GET', path: '/eslab-products-v2/_mapping', note: '确认 v2 的 title 是 ik_max_word → ik_smart 这一对' },
  ],
  'm2-2': [
    {
      method: 'PUT',
      path: '/eslab-goods-analyzer-ok',
      note: 'A 组：索引期 ik_max_word + 查询期 ik_smart（正确配置）',
      body: {
        settings: { number_of_shards: 1, number_of_replicas: 0, refresh_interval: '1s' },
        mappings: { properties: { sku: { type: 'keyword' }, title: { type: 'text', analyzer: 'ik_max_word', search_analyzer: 'ik_smart' } } },
      },
    },
    {
      method: 'PUT',
      path: '/eslab-goods-analyzer-bad',
      note: 'B 组：索引期 standard（单字）+ 查询期 ik_smart（词）—— 只差这一个参数',
      body: {
        settings: { number_of_shards: 1, number_of_replicas: 0, refresh_interval: '1s' },
        mappings: { properties: { sku: { type: 'keyword' }, title: { type: 'text', analyzer: 'standard', search_analyzer: 'ik_smart' } } },
      },
    },
    { method: 'POST', path: '/_bulk', note: '两个索引各写同一批 200 条商品', repeat: '2 次' },
    {
      method: 'POST',
      path: '/eslab-goods-analyzer-bad/_analyze',
      note: '用 field 参数取索引期真实词项：单字「蓝/牙/耳/机」',
      body: { field: 'title', text: '蓝牙耳机' },
    },
    {
      method: 'POST',
      path: '/eslab-goods-analyzer-ok/_search',
      note: 'A 组查询：命中 43 条',
      dsl: 'match title="蓝牙耳机"',
      body: { size: 0, track_total_hits: true, query: { match: { title: '蓝牙耳机' } } },
    },
    {
      method: 'POST',
      path: '/eslab-goods-analyzer-bad/_search',
      note: 'B 组同一查询：命中 0 条 —— 词项交集为空',
      dsl: 'match title="蓝牙耳机"',
      body: { size: 0, track_total_hits: true, query: { match: { title: '蓝牙耳机' } } },
    },
    {
      method: 'PUT',
      path: '/eslab-goods-analyzer-bad/_mapping',
      note: '① 原地改 search_analyzer=standard：acknowledged，查询立刻能命中 67 条',
      body: { properties: { title: { type: 'text', analyzer: 'standard', search_analyzer: 'standard' } } },
    },
    {
      method: 'PUT',
      path: '/eslab-goods-analyzer-bad/_mapping',
      note: '② 想改索引期 analyzer=ik_max_word：400，Cannot update parameter [analyzer]',
      body: { properties: { title: { type: 'text', analyzer: 'ik_max_word', search_analyzer: 'standard' } } },
    },
    { method: 'DELETE', path: '/eslab-goods-analyzer-ok', note: '清理 A 组索引（finally 里执行）' },
    { method: 'DELETE', path: '/eslab-goods-analyzer-bad', note: '清理 B 组索引（finally 里执行）' },
  ],
  'm3-1': [
    { method: 'PUT', path: '/eslab-demo-write', note: '同一个索引定义（refresh_interval=-1），两侧都用它', body: { settings: { number_of_shards: 1, number_of_replicas: 0, refresh_interval: -1 } } },
    { method: 'POST', path: '/eslab-demo-write/_doc', note: '逐条 PUT：每条一次 HTTP 往返 + 一次 translog 提交', repeat: '200 次 × 4 轮（1 轮预热）' },
    { method: 'POST', path: '/_bulk', note: '一次 _bulk 打包 200 条：一次提交', repeat: '4 轮（1 轮预热）' },
    { method: 'POST', path: '/eslab-demo-write/_refresh', note: '刷新后核对条数' },
    { method: 'GET', path: '/eslab-demo-write/_count', note: '两侧都必须是 200 条' },
    { method: 'DELETE', path: '/eslab-demo-write', note: '清理对照索引' },
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
    { method: 'PUT', path: '/eslab-demo-refresh', note: '建索引：1 分片、refresh_interval=1s', body: { settings: { number_of_shards: 1, number_of_replicas: 0, refresh_interval: '1s' } } },
    {
      method: 'POST',
      path: '/eslab-demo-refresh/_doc',
      note: '路径 ①：默认写入，16ms 返回但立即查询 0 条',
      body: { sku: 'RT-1', title: '近实时测试 商品一' },
    },
    {
      method: 'POST',
      path: '/eslab-demo-refresh/_search',
      note: '立即查询 + 轮询到可见：约 928ms 后才查到',
      body: { size: 0, track_total_hits: true, query: { term: { sku: 'RT-1' } } },
      repeat: '每 20ms 一次，最多 3s',
    },
    {
      method: 'POST',
      path: '/eslab-demo-refresh/_doc?refresh=wait_for',
      note: '路径 ②：本次请求等到刷新完成，约 1s、返回即可查',
      body: { sku: 'RT-2', title: '近实时测试 商品二' },
    },
    {
      method: 'POST',
      path: '/eslab-demo-refresh/_doc?refresh=true',
      note: '路径 ③：强制刷新，只多几毫秒，但每次写都落一个新段',
      body: { sku: 'RT-3', title: '近实时测试 商品三' },
    },
    { method: 'POST', path: '/_bulk?refresh=false', note: '200 条批量：中位 18ms', repeat: '3 轮 + 1 轮预热' },
    { method: 'POST', path: '/_bulk?refresh=wait_for', note: '200 条批量：中位 905ms —— 等到下一次刷新', repeat: '3 轮 + 1 轮预热' },
    { method: 'POST', path: '/_bulk?refresh=true', note: '200 条批量：中位 26ms，代价在段数上', repeat: '3 轮 + 1 轮预热' },
    { method: 'DELETE', path: '/eslab-demo-refresh', note: '删索引，固定 _id 的样例文档随之消失' },
  ],
  'm4-1': [
    { method: 'POST', path: '/eslab-products-v2/_search', note: '① 默认：不写 track_total_hits，total 只算到 10000', body: { size: 0 } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '② 精确计数：同一个空查询，只多一个参数', body: { size: 0, track_total_hits: true } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '③ 返回文档：有 hits 才有 max_score', body: { size: 2, track_total_hits: true } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '④ 裁剪字段：_source 只留 title 与 price', body: { size: 1, track_total_hits: true, _source: ['title', 'price'] } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '⑤ 显式排序：max_score 变成 null，hits[].sort 带回排序值', body: { size: 2, track_total_hits: false, _source: ['title', 'price'], sort: [{ price: 'asc' }, { sku: 'asc' }] } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '⑥ 聚合：size:0 + aggs，只拿桶不拿文档', body: { size: 0, track_total_hits: false, aggs: { by_category: { terms: { field: 'category', size: 3 } } } } },
  ],
  'm4-2': [
    { method: 'POST', path: '/eslab-products-v2/_search', note: '① 空请求体：等价 match_all，但 total 被截在 10000', body: {} },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '①b 只加 track_total_hits:true：同一个查询变精确', body: { track_total_hits: true } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '② 加 size:0：只要统计，不要文档', body: { size: 0, track_total_hits: true } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '③ 加第一个条件 term(category)：7,130 条', body: { size: 3, track_total_hits: true, sort: [{ sku: 'asc' }], _source: ['sku'], query: { term: { category: '数码影音' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '④ 只把查询类型换成 match(title)：3,788 条，前 3 条 _id 全换', body: { size: 3, track_total_hits: true, sort: [{ sku: 'asc' }], _source: ['sku'], query: { match: { title: '蓝牙耳机' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '⑤ 再换成 range(price)：12,053 条', body: { size: 3, track_total_hits: true, sort: [{ sku: 'asc' }], _source: ['sku'], query: { range: { price: { gte: 100, lte: 500 } } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '⑥ 包成 bool(must+filter)：2,305 条 = 两个条件的交集', body: { size: 3, track_total_hits: true, sort: [{ sku: 'asc' }], _source: ['sku'], query: { bool: { must: [{ match: { title: '蓝牙耳机' } }], filter: [{ range: { price: { gte: 100, lte: 500 } } }] } } } },
  ],
  'm4-3': [
    { method: 'POST', path: '/eslab-products-v2/_search', note: '先取一条真实文档的完整标题（做整串相等的对照）', body: { size: 1, sort: [{ sku: 'asc' }], _source: ['title'] } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'match 打 text：3,788 条，正常路径', body: { size: 0, track_total_hits: true, query: { match: { title: '蓝牙耳机' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'term 打 keyword：7,130 条，精确匹配', body: { size: 0, track_total_hits: true, query: { term: { category: '数码影音' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'term 打 text：0 条且不报错', body: { size: 0, track_total_hits: true, query: { term: { title: '蓝牙耳机' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'match 打 keyword：同样命中，但不分词', body: { size: 0, track_total_hits: true, query: { match: { category: '数码影音' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'range 数值区间：12,053 条', body: { size: 0, track_total_hits: true, query: { range: { price: { gte: 100, lte: 500 } } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'term 精确等于数值：0 条，scaled_float 存的是整数分', body: { size: 0, track_total_hits: true, query: { term: { price: 299 } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'term 查 title.keyword 整串：只有 1–2 条', body: { size: 0, track_total_hits: true, query: { term: { 'title.keyword': '<上一步取到的标题>' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'match 打数值字段：能命中，但语义模糊', body: { size: 0, track_total_hits: true, query: { match: { status: '1' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'term 查不存在的字段：0 条，不报错', body: { size: 0, query: { term: { no_such_field: 'x' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'term 直接打 object：0 条，要写 specs.color', body: { size: 0, query: { term: { specs: 'x' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '字符串传给数值字段：这一类才 400', body: { size: 0, query: { term: { price: 'abc' } } }, cond: '预期 400' },
  ],
  'm4-4': [
    { method: 'POST', path: '/eslab-products-v2/_search', note: '基准：价格条件在 filter —— 568 条，max_score 只来自文本', body: { size: 3, track_total_hits: true, query: { bool: { must: [{ match: { title: '蓝牙耳机' } }], filter: [{ term: { category: '数码影音' } }, { range: { price: { gte: 100, lte: 800 } } }, { term: { status: 1 } }], must_not: [{ term: { brand: '二手' } }] } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '同一个价格条件挪进 must：命中不变，max_score 抬高', body: { size: 3, track_total_hits: true, query: { bool: { must: [{ match: { title: '蓝牙耳机' } }, { range: { price: { gte: 100, lte: 800 } } }], filter: [{ term: { category: '数码影音' } }, { term: { status: 1 } }], must_not: [{ term: { brand: '二手' } }] } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '挪进 should：不再过滤，741 条', body: { size: 3, track_total_hits: true, query: { bool: { must: [{ match: { title: '蓝牙耳机' } }], filter: [{ term: { category: '数码影音' } }, { term: { status: 1 } }], should: [{ range: { price: { gte: 100, lte: 800 } } }], must_not: [{ term: { brand: '二手' } }] } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '挪进 must_not：语义反转，只剩 173 条', body: { size: 3, track_total_hits: true, query: { bool: { must: [{ match: { title: '蓝牙耳机' } }], filter: [{ term: { category: '数码影音' } }, { term: { status: 1 } }], must_not: [{ range: { price: { gte: 100, lte: 800 } } }] } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '只有 should：默认 MSM=1，8,839 条', body: { size: 3, track_total_hits: true, query: { bool: { should: [{ term: { tags: '爆款' } }, { range: { rating: { gte: 4.5 } } }] } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '加 minimum_should_match:2：降到 1,246 条', body: { size: 3, track_total_hits: true, query: { bool: { should: [{ term: { tags: '爆款' } }, { range: { rating: { gte: 4.5 } } }], minimum_should_match: 2 } } } },
  ],
  'm4-5': [
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'operator: or（默认）：6,112 条', body: { size: 3, track_total_hits: true, _source: ['sku', 'title'], query: { match: { title: '蓝牙 降噪 耳机' } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'minimum_should_match: 2：2,060 条', body: { size: 3, track_total_hits: true, _source: ['sku', 'title'], query: { match: { title: { query: '蓝牙 降噪 耳机', minimum_should_match: '2' } } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'operator: and：212 条', body: { size: 3, track_total_hits: true, _source: ['sku', 'title'], query: { match: { title: { query: '蓝牙 降噪 耳机', operator: 'and' } } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'boost: 1（基准）：10,105 条，记下 Top3 顺序', body: { size: 3, track_total_hits: true, _source: ['sku', 'title'], query: { bool: { should: [{ term: { tags: '爆款' } }, { term: { category: '数码影音' } }] } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'boost: 5：命中仍是 10,105，最高分从 2.844 抬到 10.131', body: { size: 3, track_total_hits: true, _source: ['sku', 'title'], query: { bool: { should: [{ term: { tags: { value: '爆款', boost: 5 } } }, { term: { category: '数码影音' } }] } } } },
  ],
  'm4-6': [
    { method: 'POST', path: '/eslab-products-v2/_cache/clear?request=true', note: '先清 request cache，后面的 hit/miss 才有对照意义' },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'A 组：谓词在 must（算分）。size:0 才能进 request cache', dsl: 'bool.must + size:0', body: { size: 0, track_total_hits: true, query: { bool: { must: [{ range: { price: { gte: 100, lte: 800 } } }, { term: { status: 1 } }] } } }, repeat: '交替 5 轮 + 1 轮预热' },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'B 组：同一谓词在 filter（不算分）', dsl: 'bool.filter + size:0', body: { size: 0, track_total_hits: true, query: { bool: { filter: [{ range: { price: { gte: 100, lte: 800 } } }, { term: { status: 1 } }] } } }, repeat: '交替 5 轮 + 1 轮预热' },
    { method: 'GET', path: '/_nodes/stats/indices/request_cache', note: '读 hit/miss 计数：两组第二次请求都会命中' },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '对照 _score：must 有分（1.0 左右），filter 恒为 0', body: { size: 1, query: { bool: { filter: [{ range: { price: { gte: 100, lte: 800 } } }] } } } },
  ],
  'm5-1': [
    { method: 'POST', path: '/eslab-products-v2/_search', note: '三个 terms 聚合：类目 / 品牌默认 shard_size / 品牌 shard_size=100', body: { size: 0, track_total_hits: true, aggs: { by_category: { terms: { field: 'category', size: 5 } }, by_brand: { terms: { field: 'brand', size: 5 } }, brand_exact: { terms: { field: 'brand', size: 5, shard_size: 100 } } } } },
    { method: 'POST', path: '/eslab-products-v2/_count', note: '拿类目 Top1 的 key 回查精确计数，与桶里的 doc_count 比对', body: { query: { term: { category: '数码影音' } } } },
    { method: 'POST', path: '/eslab-products-v2/_count', note: '同样核对品牌 Top1', body: { query: { term: { brand: '声阔' } } } },
    { method: 'GET', path: '/eslab-products-v2/_count', note: '精确总数：用来校验「桶和 + other = total」' },
  ],
  'm5-2': [
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'title（text）排序：400 Fielddata is disabled', body: { size: 3, track_total_hits: false, _source: ['title'], sort: [{ title: 'asc' }] }, cond: '预期 400' },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'title（text）聚合：同样 400', body: { size: 0, track_total_hits: false, aggs: { t: { terms: { field: 'title', size: 3 } } } }, cond: '预期 400' },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'title.keyword 排序：成功', body: { size: 3, track_total_hits: false, _source: ['title'], sort: [{ 'title.keyword': 'asc' }] } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'title.keyword 聚合：成功，返回 top3 桶', body: { size: 0, track_total_hits: false, aggs: { t: { terms: { field: 'title.keyword', size: 3 } } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'description（text）：排序失败', body: { size: 3, track_total_hits: false, _source: ['title'], sort: [{ description: 'asc' }] }, cond: '预期 400' },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'description 聚合：同样失败', body: { size: 0, track_total_hits: false, aggs: { t: { terms: { field: 'description', size: 3 } } } }, cond: '预期 400' },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'price（scaled_float）：排序成功', body: { size: 3, track_total_hits: false, _source: ['title'], sort: [{ price: 'asc' }] } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'price 聚合：成功', body: { size: 0, track_total_hits: false, aggs: { t: { terms: { field: 'price', size: 3 } } } } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'category（keyword）：排序成功', body: { size: 3, track_total_hits: false, _source: ['title'], sort: [{ category: 'asc' }] } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'category 聚合：成功', body: { size: 0, track_total_hits: false, aggs: { t: { terms: { field: 'category', size: 3 } } } } },
    { method: 'GET', path: '/eslab-products-v2/_mapping', note: '读回五个字段的真实类型与子字段' },
  ],
  'm5-3': [
    { method: 'POST', path: '/eslab-products-v2/_search', note: '类目→品牌→价格统计 + 按月均价（min_doc_count:0 + extended_bounds 补空桶）', body: { size: 0, query: { term: { status: 1 } }, aggs: { by_category: { terms: { field: 'category', size: 4 }, aggs: { top_brands: { terms: { field: 'brand', size: 2 }, aggs: { price_stats: { stats: { field: 'price' } } } }, price_p95: { percentiles: { field: 'price', percents: [50, 95] } } } }, by_month: { date_histogram: { field: 'created_at', calendar_interval: 'month', min_doc_count: 0, extended_bounds: { max: '2026-12-31' } }, aggs: { avg_price: { avg: { field: 'price' } } } } } } },
    { method: 'POST', path: '/eslab-products-v2/_count', note: '核对第 1 个「类目×品牌」桶：数码影音 + 声阔 + status:1', body: { query: { bool: { filter: [{ term: { category: '数码影音' } }, { term: { brand: '声阔' } }, { term: { status: 1 } }] } } }, repeat: '8 个桶逐个核对' },
  ],
  'm6-1': [
    { method: 'POST', path: '/eslab-products-v2/_search', note: '四个档位各 3 轮：from=0 / 1000 / 5000 / 9000，每片候选 = from+size', dsl: 'from + size=20，固定 price,sku 排序', body: { from: 0, size: 20, sort: [{ price: 'asc' }, { sku: 'asc' }], _source: ['sku', 'title', 'price'] }, repeat: '4 档 × 3 轮，前置换热 2 次' },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '9980 + 20 = 10000：边界「小于等于」通过', body: { from: 9980, size: 20 } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: '9990 + 20 = 10010：被拒，抓 root_cause', body: { from: 9990, size: 20 }, cond: '预期 400' },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'total 三态①：省略 → 10000 / gte', body: { from: 9000, size: 20, sort: [{ price: 'asc' }, { sku: 'asc' }] } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'total 三态②：显式 false → 字段直接缺失', body: { from: 9000, size: 20, track_total_hits: false, sort: [{ price: 'asc' }, { sku: 'asc' }] } },
    { method: 'POST', path: '/eslab-products-v2/_search', note: 'total 三态③：true → 20000 / eq', body: { from: 9000, size: 20, track_total_hits: true, sort: [{ price: 'asc' }, { sku: 'asc' }] } },
  ],
  'm6-2': [
    { method: 'POST', path: '/eslab-products-v2/_pit?keep_alive=1m', note: '开一个 PIT：整个翻页过程共用同一份快照' },
    {
      method: 'POST',
      path: '/_search',
      note: '定位：from=0 / 5000 / 9900 各查一次，每片候选 = from+size',
      dsl: 'pit + from + size=100 + sort price, _shard_doc',
      repeat: '3 个起点 × 5 轮',
    },
    {
      method: 'POST',
      path: '/_search',
      note: '游标翻页：只带上一页最后一条的 sort 值，不带 from',
      dsl: 'pit + search_after + size=100',
      repeat: '12 页 × 5 轮',
    },
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: '越界对照：from=15000 超过 10000 窗口，返回 400',
      body: { from: 15000, size: 100, sort: [{ price: 'asc' }, { sku: 'asc' }], _source: ['sku'] },
    },
    { method: 'DELETE', path: '/_pit', note: '关闭 PIT，返回 num_freed；open_contexts 回到基线' },
    {
      method: 'POST',
      path: '/_search',
      note: '用刚关闭的 PIT 再查一次，确认 404 —— 上下文是真的释放了',
      cond: '预期 404',
    },
  ],
  'm6-3': [
    {
      method: 'POST',
      path: '/eslab-products-v2/_search',
      note: 'profile: true，拿到 query 树；collector 与 fetch 分别在另外两段（fetch 在分片级）',
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
    { method: 'POST', path: '/_reindex?wait_for_completion=false', note: '确保 v3 有 2 万条（不足则从 v2 搬），幂等' },
    { method: 'GET', path: '/_cat/aliases/eslab-goods?format=json&h=alias,index,is_write_index', note: '归位前先读一次真实指向（可能已被别的关卡切走）' },
    { method: 'POST', path: '/_aliases', note: '归位：remove 旧指向 + add v3，同一次请求', body: { actions: [{ remove: { index: '*', alias: 'eslab-goods' } }, { add: { index: 'eslab-goods-v3', alias: 'eslab-goods' } }] } },
    { method: 'POST', path: '/_aliases', note: '切到 v4：应用侧一个字都不用改', body: { actions: [{ remove: { index: 'eslab-goods-v3', alias: 'eslab-goods' } }, { add: { index: 'eslab-goods-v4', alias: 'eslab-goods' } }] } },
    { method: 'POST', path: '/_aliases', note: '复原 v3：保证下一关起点确定', body: { actions: [{ remove: { index: 'eslab-goods-v4', alias: 'eslab-goods' } }, { add: { index: 'eslab-goods-v3', alias: 'eslab-goods' } }] } },
    { method: 'POST', path: '/_aliases', note: '读多写一：临时别名同时指向 v3 与 v4', body: { actions: [{ add: { index: 'eslab-goods-v3', alias: 'eslab-goods-readall' } }, { add: { index: 'eslab-goods-v4', alias: 'eslab-goods-readall' } }] } },
    { method: 'POST', path: '/eslab-goods-readall/_search', note: '读多目标别名：40,000 条 = v3 + v4', body: { size: 0, track_total_hits: true } },
    { method: 'PUT', path: '/eslab-goods-readall/_doc/__m81_probe__', note: '写多目标别名：被拒（无法确定写哪个）', body: { sku: '__m81_probe__', title: '写多目标别名的探针' }, cond: '预期 400' },
    { method: 'POST', path: '/_aliases', note: 'finally：删掉临时读别名，避免污染后续断言', body: { actions: [{ remove: { index: 'eslab-goods-v3', alias: 'eslab-goods-readall' } }, { remove: { index: 'eslab-goods-v4', alias: 'eslab-goods-readall' } }] } },
  ],
  'm8-2': [
    { method: 'POST', path: '/_reindex?wait_for_completion=false', note: '确保 v3 与 v4 都是 2 万条（不足则重建）' },
    { method: 'GET', path: '/_cat/aliases/eslab-goods?format=json&h=alias,index,is_write_index', note: '进入本关先看指向，不在 v3 就归位' },
    { method: 'GET', path: '/eslab-goods-v3/_count', note: '① 校验：旧索引条数' },
    { method: 'GET', path: '/eslab-goods-v4/_count', note: '① 校验：新索引条数，必须相等才允许切' },
    { method: 'POST', path: '/eslab-goods-v3/_search', note: '② 抽样：按 sku 升序取 3 条 _id 与内容', body: { size: 3, sort: [{ sku: 'asc' }], _source: ['sku', 'title', 'price'] } },
    { method: 'POST', path: '/eslab-goods-v4/_mget', note: '② 抽样比对：同 _id 在 v4 取回，逐字比对 title/price', body: { ids: ['<上一步的 3 个 _id>'] } },
    { method: 'POST', path: '/_aliases', note: '③ 切换：remove v3 + add v4，一次请求完成', body: { actions: [{ remove: { index: 'eslab-goods-v3', alias: 'eslab-goods' } }, { add: { index: 'eslab-goods-v4', alias: 'eslab-goods' } }] } },
    { method: 'POST', path: '/_aliases', note: '④ 回滚：切回 v3，验证仍可读', body: { actions: [{ remove: { index: 'eslab-goods-v4', alias: 'eslab-goods' } }, { add: { index: 'eslab-goods-v3', alias: 'eslab-goods' } }] } },
    { method: 'POST', path: '/_aliases', note: '④ 再切 v4 作为结束状态', body: { actions: [{ remove: { index: 'eslab-goods-v3', alias: 'eslab-goods' } }, { add: { index: 'eslab-goods-v4', alias: 'eslab-goods' } }] } },
    { method: 'GET', path: '/eslab-goods-v4/_mapping', note: '读回 new mapping：字段数 15、selling_point 存在' },
  ],
  'm8-3': [
    { method: 'DELETE', path: '/eslab-goods-v5', note: '每次从空索引开始，否则进度瞬间达成', cond: '不存在则跳过' },
    { method: 'PUT', path: '/eslab-goods-v5', note: '重建目标索引', dsl: 'v5 建索引' },
    { method: 'POST', path: '/_reindex?wait_for_completion=false&requests_per_second=4000', note: '异步重建，立刻返回 task id', body: { source: { index: 'eslab-goods-v4' }, dest: { index: 'eslab-goods-v5' }, max_docs: 20000 } },
    { method: 'GET', path: '/_tasks/{taskId}', note: '轮询任务状态：completed / created / batches', repeat: '每 250ms' },
    { method: 'GET', path: '/eslab-goods-v5/_count', note: '完成后确认 2 万条都到了' },
  ],
  'm8-4': [
    { method: 'GET', path: '/_cat/segments/eslab-goods-v3?format=json&h=shard,segment,docs.count', note: '按分片统计段数（不是索引级汇总）' },
    { method: 'POST', path: '/eslab-goods-v3/_forcemerge?max_num_segments=1', note: '合并：max_num_segments=1 是「每片 1 段」' },
    { method: 'POST', path: '/eslab-goods-v3/_forcemerge?max_num_segments=1', note: '再合一次：段数应与第一次相同（幂等）' },
    { method: 'PUT', path: '/eslab-goods-live-000001/_doc/SEG-0?refresh=true', note: '造碎段：每条 refresh=true 写一次，落一个新段', repeat: '10 条' },
    { method: 'POST', path: '/eslab-goods-live-000001/_forcemerge?max_num_segments=1', note: '11 段 → 1 段，真实下降' },
    { method: 'GET', path: '/_cat/aliases/eslab-goods-live?format=json&h=alias,index,is_write_index', note: 'rollover 前快照：哪一行是写目标' },
    { method: 'POST', path: '/eslab-goods-live/_rollover', note: '条件满足才滚：-000001 → -000002', body: { conditions: { max_docs: 4000 } } },
    { method: 'GET', path: '/_cat/aliases/eslab-goods-live?format=json&h=alias,index,is_write_index', note: 'rollover 后快照：写目标已挪，旧索引 is_write_index=false' },
    { method: 'PUT', path: '/eslab-goods-live/_doc/ROLL-1?refresh=true', note: '写一条验证它落到新索引', body: { sku: 'ROLL-1', title: '滚动索引验证商品' } },
  ],
  'm9-1': [
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'best_fields：最高分 + 0.3×其余字段', dsl: 'best_fields', body: { size: 3, track_total_hits: true, query: { multi_match: { query: '小米 降噪', fields: ['title^3', 'description', 'brand^2'], type: 'best_fields', tie_breaker: 0.3, minimum_should_match: '70%' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'most_fields：各字段分数直接相加，分数虚高', dsl: 'most_fields', body: { size: 3, track_total_hits: true, query: { multi_match: { query: '小米 降噪', fields: ['title^3', 'description', 'brand^2'], type: 'most_fields', minimum_should_match: '70%' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'cross_fields：多字段当成一个大字段', dsl: 'cross_fields', body: { size: 3, track_total_hits: true, query: { multi_match: { query: '小米 降噪', fields: ['title^3', 'description', 'brand^2'], type: 'cross_fields', minimum_should_match: '70%' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'most_fields + tie_breaker：会退化成 best_fields', dsl: 'most_fields + tb', body: { size: 3, track_total_hits: true, query: { multi_match: { query: '小米 降噪', fields: ['title^3', 'description', 'brand^2'], type: 'most_fields', tie_breaker: 0.3, minimum_should_match: '70%' } } } },
  ],
  'm9-2': [
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'match：两个词出现即可，3,788 条', body: { size: 0, track_total_hits: true, query: { match: { title: '蓝牙耳机' } } }, repeat: '5 轮 took 中位' },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'match_phrase：必须相邻同序，1,301 条', body: { size: 0, track_total_hits: true, query: { match_phrase: { title: '蓝牙耳机' } } }, repeat: '5 轮' },
    { method: 'POST', path: '/eslab-goods-search/_search', note: '颠倒词序 + slop 0：139 条（不是 0）', body: { size: 0, track_total_hits: true, query: { match_phrase: { title: { query: '耳机 蓝牙', slop: 0 } } } }, repeat: '5 轮' },
    { method: 'POST', path: '/eslab-goods-search/_search', note: '同一个逆序放宽到 slop 3：1,348 条', body: { size: 0, track_total_hits: true, query: { match_phrase: { title: { query: '耳机 蓝牙', slop: 3 } } } }, repeat: '5 轮' },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'prefix：要求「以它开头」，本机 0 条', body: { size: 0, track_total_hits: true, query: { prefix: { 'title.keyword': '蓝牙耳机' } } }, repeat: '5 轮' },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'wildcard 前后通配：是「包含」，1,301 条', body: { size: 0, track_total_hits: true, query: { wildcard: { 'title.keyword': '*蓝牙耳机*' } } }, repeat: '5 轮' },
  ],
  'm9-3': [
    { method: 'POST', path: '/eslab-goods-search/_search', note: '基线：纯 BM25，Top1 得分约 10.820', body: { size: 10, track_total_hits: true, query: { multi_match: { query: '蓝牙耳机', fields: ['title^2', 'description'], type: 'best_fields' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: '只加上新（30 天窗口）：得分塌到接近 0', body: { size: 1, track_total_hits: true, query: { function_score: { query: { multi_match: { query: '蓝牙耳机', fields: ['title^2', 'description'], type: 'best_fields' } }, functions: [{ gauss: { created_at: { origin: 'now-30d', scale: '30d', decay: 0.5 } }, weight: 2 }], score_mode: 'sum', boost_mode: 'multiply' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: '同一个函数把窗口放宽到 365 天：约 19.5', body: { size: 1, track_total_hits: true, query: { function_score: { query: { multi_match: { query: '蓝牙耳机', fields: ['title^2', 'description'], type: 'best_fields' } }, functions: [{ gauss: { created_at: { origin: 'now-365d', scale: '365d', decay: 0.5 } }, weight: 2 }], score_mode: 'sum', boost_mode: 'multiply' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: '只加销量 log1p：最高分约 30.275', body: { size: 1, track_total_hits: true, query: { function_score: { query: { multi_match: { query: '蓝牙耳机', fields: ['title^2', 'description'], type: 'best_fields' } }, functions: [{ field_value_factor: { field: 'sales', modifier: 'log1p', factor: 0.1 } }], score_mode: 'sum', boost_mode: 'multiply' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: '只加在售 filter：该文档 status≠1，分数不变', body: { size: 1, track_total_hits: true, query: { function_score: { query: { multi_match: { query: '蓝牙耳机', fields: ['title^2', 'description'], type: 'best_fields' } }, functions: [{ filter: { term: { status: 1 } }, weight: 1.5 }], score_mode: 'sum', boost_mode: 'multiply' } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: '三个全开：命中数不变，Top10 只重合 1 条', body: { size: 10, track_total_hits: true, query: { function_score: { query: { multi_match: { query: '蓝牙耳机', fields: ['title^2', 'description'], type: 'best_fields' } }, functions: [{ gauss: { created_at: { origin: 'now-30d', scale: '30d', decay: 0.5 } }, weight: 2 }, { field_value_factor: { field: 'sales', modifier: 'log1p', factor: 0.1 } }, { filter: { term: { status: 1 } }, weight: 1.5 }], score_mode: 'sum', boost_mode: 'multiply' } } } },
    { method: 'GET', path: '/eslab-goods-search/_explain/<top1 _id>', note: '看 product of 结构：每个函数各贡献多少' },
  ],
  'm9-4': [
    { method: 'POST', path: '/eslab-goods-search/_search', note: '高亮：match title，total 3,788，5 条全带 <em>', body: { size: 5, track_total_hits: true, query: { match: { title: '蓝牙耳机' } }, highlight: { fields: { title: { number_of_fragments: 1 }, description: { fragment_size: 40 } } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: '0 高亮对照：查 description、只开 title 高亮 → 5/5 无高亮', body: { size: 5, track_total_hits: true, query: { match: { description: '降噪' } }, highlight: { fields: { title: { number_of_fragments: 1 } } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'collapse：返回 5 条 5 个品牌，total 仍是 20,000', body: { size: 5, track_total_hits: true, query: { match_all: {} }, collapse: { field: 'brand' }, sort: [{ sales: { order: 'desc' } }], _source: ['title', 'brand', 'sales', 'price'] } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'collapse + inner_hits：每组再带一条最贵的', body: { size: 3, track_total_hits: true, query: { match_all: {} }, collapse: { field: 'brand', inner_hits: { name: 'top_by_price', size: 1, sort: [{ price: { order: 'desc' } }] } }, sort: [{ sales: { order: 'desc' } }], _source: ['title', 'brand'] } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: '品牌基数：cardinality 算出 30 个，不是折叠返回的 5 条', body: { size: 0, aggs: { brands: { cardinality: { field: 'brand' } } } } },
  ],
  'm9-5': [
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'term suggester：中文错字给 0 个候选', body: { size: 0, suggest: { term_sug: { text: '蓝牙耳几', term: { field: 'title', suggest_mode: 'popular', size: 3 } } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'phrase suggester：同样 0 个候选', body: { size: 0, suggest: { phrase_sug: { text: '蓝牙耳几', phrase: { field: 'title', size: 2, gram_size: 2, direct_generator: [{ field: 'title', suggest_mode: 'always' }] } } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'completion「小米」：5 个候选（串首匹配）', body: { size: 0, suggest: { comp: { prefix: '小米', completion: { field: 'title_suggest', size: 5 } } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'completion「蓝牙」：0 个候选（不在串首）', body: { size: 0, suggest: { comp: { prefix: '蓝牙', completion: { field: 'title_suggest', size: 5 } } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'bool_prefix「蓝牙耳」：3,788 条（词中也能补全）', body: { size: 0, track_total_hits: true, query: { multi_match: { query: '蓝牙耳', type: 'bool_prefix', fields: ['title_sayt', 'title_sayt._2gram', 'title_sayt._3gram'] } } } },
    { method: 'POST', path: '/eslab-goods-search/_search', note: 'match_phrase_prefix「蓝牙耳」：1,301 条（要求词头开始）', body: { size: 0, track_total_hits: true, query: { match_phrase_prefix: { title: '蓝牙耳' } } } },
    { method: 'POST', path: '/_analyze', note: '中英对照：english 还原词干 earphon', body: { analyzer: 'english', text: 'Bluetooth Earphones' } },
    { method: 'POST', path: '/_analyze', note: 'standard 不还原：earphones', body: { analyzer: 'standard', text: 'Bluetooth Earphones' } },
    { method: 'POST', path: '/eslab-goods-search/_reload_search_analyzers', note: '内联规则：reload 只重读文件，规则不会变' },
  ],
  'm9-6': [
    { method: 'POST', path: '/_aliases', note: '归位 v4（没有同义词的版本），保证「切换前 0 条」可复现', body: { actions: [{ remove: { index: '*', alias: 'eslab-goods' } }, { add: { index: 'eslab-goods-v4', alias: 'eslab-goods' } }] } },
    { method: 'POST', path: '/eslab-goods/_search', note: '切换前：TWS 命中 0 条', body: { size: 0, track_total_hits: true, query: { match: { title: 'TWS' } } } },
    { method: 'POST', path: '/_reindex?wait_for_completion=false', note: '建 v6（查询期 synonym_graph）并从别名搬 2 万条', repeat: '按需重建' },
    { method: 'POST', path: '/eslab-goods-v6/_analyze', note: '查询期展开：蓝牙 / 真 / tws / 耳机 / 无线', body: { analyzer: 'goods_syn_search', text: 'TWS' } },
    { method: 'POST', path: '/eslab-goods-v6/_analyze', note: '索引期分析器（field=title）：只有 tws，没有展开', body: { field: 'title', text: 'TWS' } },
    { method: 'POST', path: '/eslab-goods-syn-idx/_analyze', note: '索引期同义词索引：field=title 也展开', body: { field: 'title', text: 'TWS' } },
    { method: 'POST', path: '/_aliases', note: '原子切换：v4 → v6', body: { actions: [{ remove: { index: 'eslab-goods-v4', alias: 'eslab-goods' } }, { add: { index: 'eslab-goods-v6', alias: 'eslab-goods' } }] } },
    { method: 'POST', path: '/eslab-goods/_search', note: '切换后：TWS 命中 1,301 条，查询语句一个字没改', body: { size: 0, track_total_hits: true, query: { match: { title: 'TWS' } } } },
    { method: 'POST', path: '/eslab-goods-v6/_reload_search_analyzers', note: '内联规则 reload：有 detail，但读不到文件' },
  ],
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
  // ── M1–M6 核心操作小关卡（独立 eslab-goods-core，每关先重建 30 条样例）──
  'm1-5': [
    { method: 'PUT', path: '/eslab-goods-core/_doc/core-crud', note: '固定 ID 创建：_version=1，4 个字段', body: { sku: 'CORE-CRUD', title: '蓝牙耳机 CRUD 样例', stock: 1, price: 199 } },
    { method: 'POST', path: '/eslab-goods-core/_update/core-crud', note: '局部更新 stock：合并语义，其余字段保留', body: { doc: { stock: 2 } } },
    { method: 'GET', path: '/eslab-goods-core/_doc/core-crud', note: '读回 _source 与 _version' },
    { method: 'PUT', path: '/eslab-goods-core/_doc/core-crud?refresh=true', note: '整条覆盖：只带 stock，其余字段全丢', body: { stock: 3 } },
    { method: 'DELETE', path: '/eslab-goods-core/_doc/core-crud', note: '删除后 GET 得到 404' },
  ],
  'm1-6': [
    { method: 'GET', path: '/eslab-goods-core/_mapping', note: '字段类型与 analyzer' },
    { method: 'GET', path: '/eslab-goods-core/_settings', note: '分片数 / 副本数' },
    { method: 'POST', path: '/eslab-goods-core/_field_caps', note: '类型 / 可搜索 / 可聚合', body: { fields: ['title', 'price', 'sku', 'brand'] } },
    { method: 'POST', path: '/eslab-products-v2/_field_caps', note: '同一字段在 v2 里的能力（对照）', body: { fields: ['title', 'price'] } },
  ],
  'm2-3': [
    { method: 'POST', path: '/eslab-goods-core/_analyze', note: '按 title 的 ik_max_word 切词', body: { field: 'title', text: '蓝牙耳机 主动降噪' } },
    { method: 'POST', path: '/_analyze', note: '同一串用 standard 对照', body: { analyzer: 'standard', text: '蓝牙耳机 主动降噪' } },
    { method: 'POST', path: '/eslab-goods-core/_search', note: 'match 命中', body: { size: 0, query: { match: { title: '蓝牙耳机 主动降噪' } } } },
    { method: 'POST', path: '/eslab-goods-core/_search', note: 'term 打单字「蓝」：0 命中', body: { size: 0, query: { term: { title: '蓝' } } } },
  ],
  'm3-4': [
    { method: 'POST', path: '/_bulk', note: 'NDJSON：create 冲突（409）+ index 成功（201），整体仍 200、errors=true' },
    { method: 'POST', path: '/_bulk', note: '把 create 换成 index 重放：errors=false', cond: '重放' },
    { method: 'GET', path: '/eslab-goods-core/_count', note: '最终文档数 31' },
  ],
  'm4-7': [
    { method: 'POST', path: '/eslab-goods-core/_search', note: '_source 裁到 2 个字段', body: { size: 1, _source: ['sku', 'title'], query: { match_all: {} } } },
    { method: 'POST', path: '/eslab-goods-core/_search', note: 'docvalue_fields 取 3 个字段', body: { size: 1, _source: false, docvalue_fields: ['sku', 'price', 'created_at'], query: { match_all: {} } } },
    { method: 'POST', path: '/eslab-goods-core/_search', note: 'stored_fields：mapping 未 store，返回空', body: { size: 1, stored_fields: ['sku', 'title'], query: { match_all: {} } } },
    { method: 'POST', path: '/eslab-goods-core/_search', note: '对 text 要 docvalue：400', body: { size: 1, _source: false, docvalue_fields: ['title'], query: { match_all: {} } } },
  ],
  'm4-8': [
    { method: 'POST', path: '/_msearch', note: 'NDJSON：第 1 条正常、第 2 条对 text 排序报错，整体 200' },
    { method: 'POST', path: '/eslab-goods-core/_mget', note: '三条 id，含一条不存在的', body: { ids: ['core-0', 'core-1', 'missing'] } },
  ],
  'm5-4': [
    { method: 'POST', path: '/eslab-goods-core/_search', note: 'filter+stats / range / cardinality / terms', body: { size: 0, aggs: { in_stock: { filter: { range: { stock: { gt: 20 } } }, aggs: { price_stats: { stats: { field: 'price' } } } } } } },
  ],
  'm5-5': [
    { method: 'POST', path: '/eslab-goods-core/_search', note: 'composite size=2 第 1 页', body: { size: 0, aggs: { all: { composite: { size: 2, sources: [{ brand: { terms: { field: 'brand' } } }] } } } } },
    { method: 'POST', path: '/eslab-goods-core/_search', note: '带 after_key 取下一页，直到游标消失', repeat: '× 4 页' },
    { method: 'POST', path: '/eslab-goods-core/_search', note: 'terms(size=50) 取精确桶数对照', body: { size: 0, aggs: { all: { terms: { field: 'brand', size: 50 } } } } },
  ],
  'm6-4': [
    { method: 'POST', path: '/eslab-goods-core/_search?scroll=1m', note: '打开 scroll 上下文，size=10', body: { size: 10, sort: ['_doc'], query: { match_all: {} } } },
    { method: 'POST', path: '/_search/scroll', note: '用 _scroll_id 取下一批', repeat: '× 3 批' },
    { method: 'DELETE', path: '/_search/scroll', note: '关闭上下文' },
    { method: 'POST', path: '/eslab-goods-core/_pit?keep_alive=1m', note: '打开 PIT 快照' },
    { method: 'POST', path: '/_search', note: 'PIT + search_after 翻页（sort=price,_shard_doc）', repeat: '× 3 页' },
    { method: 'DELETE', path: '/_pit', note: '关闭 PIT' },
  ],
  'm6-5': [
    { method: 'POST', path: '/eslab-goods-core/_search', note: '取 match 的最高分文档', body: { size: 1, query: { match: { title: '蓝牙耳机' } } } },
    { method: 'POST', path: '/eslab-goods-core/_explain/core-0', note: '重放同一 query，分数应一致', body: { query: { match: { title: '蓝牙耳机' } } } },
    { method: 'POST', path: '/eslab-goods-core/_explain/core-0', note: '换成 term：matched=false', body: { query: { term: { title: '蓝牙耳机' } } } },
  ],
};

export function callsOf(levelId: string): CallSpec[] {
  return LEVEL_CALLS[levelId] ?? [];
}
