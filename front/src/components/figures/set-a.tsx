import { Arrow, Box, C, Frame, Label } from './Figure';

/** 写路径：一条文档从客户端到可被搜索，中间发生什么 */
export function WritePath() {
  return (
    <Frame height={190} label="写路径：客户端 → 主分片 → 副本 → refresh → 新段" draw={(a) => (
      <>
        <Box x={24} y={44} w={112} h={56} title="客户端" sub="写一条文档" />
        <Box x={166} y={44} w={112} h={56} title="主分片" sub="写内存 buffer" tone="accent" />
        <Box x={308} y={44} w={112} h={56} title="副本分片" sub="同步成功后应答" />
        <Box x={450} y={44} w={112} h={56} title="refresh" sub="默认每秒一次" />
        <Box x={592} y={44} w={104} h={56} title="新段" sub="可被搜索" tone="ok" />
        <Arrow x1={136} y1={72} x2={164} y2={72} arrow={a} tone="accent" />
        <Arrow x1={278} y1={72} x2={306} y2={72} arrow={a} />
        <Arrow x1={420} y1={72} x2={448} y2={72} arrow={a} />
        <Arrow x1={562} y1={72} x2={590} y2={72} arrow={a} tone="ok" />
        <Box x={166} y={126} w={112} h={38} title="translog" sub="先落盘再应答" dashed />
        <Arrow x1={222} y1={100} x2={222} y2={126} arrow={a} dashed label="顺序写" labelDy={0} />
        <Label x={300} y={158} text="refresh 之前，文档只能按 _id 取，搜不到" />
      </>
    )} />
  );
}

/** refresh 与 flush 是两件事：刷新让文档可搜，刷盘才落 commit point */
export function RefreshTimeline() {
  const y = 92;
  const pts = [
    { x: 90, top: '写入', bottom: '进 buffer + translog' },
    { x: 260, top: 'refresh（1s）', bottom: 'buffer → 新段' },
    { x: 430, top: '可被搜索', bottom: 'NRT 生效' },
    { x: 600, top: 'flush', bottom: 'commit point 落盘' },
  ];
  return (
    <Frame height={180} label="时间轴：写入、refresh、可搜索、flush 四个时刻" draw={(a) => (
      <>
        <line x1={60} y1={y} x2={672} y2={y} stroke={C.border} strokeWidth="1.4" />
        {pts.map((p, i) => (
          <g key={p.x}>
            <circle cx={p.x} cy={y} r={i === 1 ? 8 : 5} fill={i === 1 ? C.accent : C.panel} stroke={i === 1 ? C.accent : C.border} strokeWidth="1.4" />
            <Label x={p.x} y={y - 20} text={p.top} anchor="middle" color={C.text} size={12.5} weight={500} />
            <Label x={p.x} y={y + 26} text={p.bottom} anchor="middle" />
          </g>
        ))}
        <Arrow x1={300} y1={y} x2={420} y2={y} arrow={a} tone="accent" />
        <Label x={360} y={y - 8} text="这一步之后才搜得到" anchor="middle" color={C.accent} />
        <Label x={60} y={160} text="refresh 不落盘：断电会丢；flush + translog 才保证不丢" />
      </>
    )} />
  );
}

/** 段合并：删除只是标记，merge 才真正回收空间 */
export function SegmentMerge() {
  return (
    <Frame height={190} label="四个小段合并成一个段，被删除的文档在合并时才消失" draw={(a) => (
      <>
        {[0, 1, 2, 3].map((i) => (
          <g key={i}>
            <Box x={40 + i * 78} y={40} w={64} h={40} title={`段 ${i + 1}`} />
            <Arrow x1={72 + i * 78} y1={80} x2={300} y2={112} arrow={a} />
          </g>
        ))}
        <Box x={300} y={96} w={130} h={52} title="merge" sub="后台自动进行" tone="accent" />
        <Arrow x1={430} y1={124} x2={520} y2={124} arrow={a} tone="ok" />
        <Box x={520} y={96} w={160} h={52} title="1 个新段" sub="已删文档不再写入" tone="ok" />
        <Label x={40} y={168} text="段越多，查询要扫的文件越多；forcemerge 是把「每分片段数」压到 1" />
      </>
    )} />
  );
}

/** 倒排索引：词项 → 文档 id，这才是「全文检索」快的原因 */
export function InvertedIndex() {
  const terms = ['蓝牙', '耳机', '降噪'];
  return (
    <Frame height={210} label="倒排索引：词项到文档 id 列表的映射" draw={(a) => (
      <>
        <Label x={40} y={36} text="词项（term）" color={C.text} weight={500} size={12.5} />
        <Label x={300} y={36} text="包含它的文档（posting list）" color={C.text} weight={500} size={12.5} />
        {terms.map((t, i) => (
          <g key={t}>
            <Box x={40} y={52 + i * 46} w={150} h={36} title={t} />
            <Arrow x1={190} y1={70 + i * 46} x2={298} y2={70 + i * 46} arrow={a} />
            <Box x={300} y={52 + i * 46} w={330} h={36} title={`doc_${i + 1}, doc_${i + 4}, doc_${i + 9} …`} />
          </g>
        ))}
        <Label x={40} y={196} text="查「降噪耳机」= 取两个 posting list 求交集，不需要逐条读原文" />
      </>
    )} />
  );
}

/** 分析器流水线：同一段文字，三种分析器切成完全不同的词项 */
export function AnalyzerPipeline() {
  return (
    <Frame height={200} label="分析器流水线：字符过滤 → 分词 → 词项过滤" draw={(a) => (
      <>
        <Box x={30} y={44} w={180} h={56} title="字符过滤" sub="char filter（可选）" />
        <Box x={270} y={44} w={180} h={56} title="分词器" sub="tokenizer：ik_smart" tone="accent" />
        <Box x={510} y={44} w={180} h={56} title="词项过滤" sub="lowercase / synonym" />
        <Arrow x1={210} y1={72} x2={268} y2={72} arrow={a} />
        <Arrow x1={450} y1={72} x2={508} y2={72} arrow={a} />
        <Label x={30} y={130} text="输出词项：" color={C.text} weight={500} size={12.5} />
        <Box x={30} y={142} w={120} h={34} title="蓝牙" />
        <Box x={160} y={142} w={120} h={34} title="耳机" />
        <Box x={290} y={142} w={120} h={34} title="降噪" />
        <Box x={420} y={142} w={150} h={34} title="（同义词展开）" dashed />
        <Label x={590} y={160} text="索引期与查询期" />
        <Label x={590} y={176} text="可以用不同分析器" />
      </>
    )} />
  );
}

/** bool 的四个子句：两个影响命中，两个影响排序 */
export function BoolTree() {
  const kids = [
    { x: 16, title: 'must', sub: '必须命中 · 算分', tone: 'accent' as const },
    { x: 186, title: 'filter', sub: '必须命中 · 不算分', tone: 'plain' as const },
    { x: 356, title: 'should', sub: '命中加分', tone: 'plain' as const },
    { x: 526, title: 'must_not', sub: '必须不命中', tone: 'err' as const },
  ];
  return (
    <Frame height={240} label="bool 查询的四个子句：must、filter、should、must_not" draw={(a) => (
      <>
        <Box x={280} y={24} w={160} h={48} title="bool" sub="只组合，不查字段" tone="accent" />
        {kids.map((k) => (
          <g key={k.title}>
            <Arrow x1={360} y1={72} x2={k.x + 75} y2={118} arrow={a} tone={k.tone === 'err' ? 'err' : 'muted'} />
            <Box x={k.x} y={118} w={150} h={52} title={k.title} sub={k.sub} tone={k.tone} />
          </g>
        ))}
        <Label x={16} y={196} text="must 与 should 参与打分；filter 只判真假，可被缓存 —— 价格、状态这类条件应当放 filter" />
        <Label x={16} y={216} text="must_not 不参与打分，也不能缓存命中集之外的东西" />
      </>
    )} />
  );
}

/** 深分页：from+size 的窗口上限，与 search_after 的游标推进 */
export function PagingWindow() {
  return (
    <Frame height={210} label="from+size 的固定窗口与 search_after 的游标推进" draw={(a) => (
      <>
        <rect x={24} y={40} width={320} height={120} rx="8" fill={C.panel} stroke={C.border} strokeWidth="1.2" />
        <Label x={40} y={64} text="from + size" color={C.text} weight={500} size={12.5} />
        <Box x={40} y={78} w={120} h={34} title="第 1 页" />
        <Box x={170} y={78} w={120} h={34} title="第 N 页" dashed />
        <Box x={40} y={120} w={250} h={26} title="窗口上限 10000" tone="err" />
        <Label x={40} y={176} text="每分片都要取出 from+size 条再排序" />

        <rect x={376} y={40} width={320} height={120} rx="8" fill={C.panel} stroke={C.border} strokeWidth="1.2" />
        <Label x={392} y={64} text="search_after" color={C.text} weight={500} size={12.5} />
        <Box x={392} y={78} w={90} h={34} title="游标" tone="accent" />
        <Arrow x1={482} y1={95} x2={540} y2={95} arrow={a} tone="accent" />
        <Box x={540} y={78} w={120} h={34} title="下一页" tone="ok" />
        <Label x={392} y={132} text="每页只取 size 条，按排序值往后翻" />
        <Label x={392} y={176} text="不受 10000 限制，但只能顺序翻" />
      </>
    )} />
  );
}

/** 相关性排序栈：BM25 只是其中一层 */
export function RelevanceStack() {
  const rows = [
    { y: 24, title: 'query 与字段权重', sub: 'title^3 / brand^2', tone: 'plain' as const },
    { y: 78, title: 'BM25 文本相似度', sub: '词频 × 逆文档频率', tone: 'accent' as const },
    { y: 132, title: '业务函数', sub: 'gauss 时间 / 销量', tone: 'plain' as const },
    { y: 186, title: 'score_mode 合成', sub: 'sum / multiply', tone: 'plain' as const },
    { y: 240, title: '最终排序', sub: '分数 + 显式 sort', tone: 'ok' as const },
  ];
  return (
    <Frame height={300} label="相关性排序栈：文本相似度只是其中一层" draw={(a) => (
      <>
        {rows.map((r, i) => (
          <g key={r.title}>
            <Box x={170} y={r.y} w={280} h={44} title={r.title} sub={r.sub} tone={r.tone} />
            {i > 0 && <Arrow x1={310} y1={rows[i - 1].y + 44} x2={310} y2={r.y - 2} arrow={a} />}
          </g>
        ))}
        <Label x={470} y={102} text="谁更相关" />
        <Label x={470} y={156} text="谁更值得看" />
        <Label x={470} y={210} text="怎么合成" />
      </>
    )} />
  );
}

/** 补全的两条路：suggester 给候选，bool_prefix 直接搜 */
export function SuggestFunnel() {
  return (
    <Frame height={220} label="补全的两条路：suggester 候选与 search_as_you_type 直搜" draw={(a) => (
      <>
        <Box x={24} y={84} w={110} h={52} title="输入" sub="蓝牙耳" />
        <Arrow x1={134} y1={110} x2={172} y2={110} arrow={a} />
        <Box x={172} y={84} w={110} h={52} title="分析" sub="分词 / 前缀" tone="accent" />
        <Arrow x1={282} y1={100} x2={352} y2={62} arrow={a} />
        <Arrow x1={282} y1={120} x2={352} y2={158} arrow={a} />
        <Box x={352} y={36} w={170} h={52} title="suggester" sub="给候选，不参与打分" />
        <Box x={352} y={132} w={170} h={52} title="bool_prefix" sub="直接命中文档" tone="ok" />
        <Arrow x1={522} y1={62} x2={576} y2={96} arrow={a} />
        <Arrow x1={522} y1={158} x2={576} y2={124} arrow={a} />
        <Box x={576} y={84} w={120} h={52} title="候选 / 结果" />
        <Label x={24} y={196} text="completion 只从候选串开头匹配；bool_prefix 支持词中间，召回更宽" />
      </>
    )} />
  );
}
