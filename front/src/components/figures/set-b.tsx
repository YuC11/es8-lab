import { Arrow, Box, C, Frame, Label } from './Figure';

/** 别名：客户端只认一个名字，物理索引可以换 */
export function AliasSwap() {
  return (
    <Frame height={210} label="别名把客户端与物理索引解耦，切换时客户端无感" draw={(a) => (
      <>
        <Box x={24} y={70} w={120} h={56} title="应用" sub="只认 eslab-goods" />
        <Arrow x1={144} y1={98} x2={206} y2={98} arrow={a} />
        <Box x={206} y={70} w={150} h={56} title="别名" sub="eslab-goods" tone="accent" />
        <Arrow x1={356} y1={86} x2={430} y2={64} arrow={a} tone="ok" />
        <Arrow x1={356} y1={112} x2={430} y2={138} arrow={a} dashed />
        <Box x={430} y={40} w={170} h={50} title="eslab-goods-v4" sub="当前指向" tone="ok" />
        <Box x={430} y={126} w={170} h={50} title="eslab-goods-v3" sub="旧索引，仍可查" dashed />
        <Label x={24} y={172} text="切换 = 一次 /_aliases 请求：remove 旧指向 + add 新指向，原子生效" />
        <Label x={24} y={192} text="应用端不需要改配置、不需要重启，也看不到「指向空索引」的瞬间" />
      </>
    )} />
  );
}

/** reindex：从源分片读、批量写目标，冲突在写入侧判定 */
export function ReindexPipeline() {
  return (
    <Frame height={220} label="reindex 从源索引分批读到目标索引，冲突在写入侧判定" draw={(a) => (
      <>
        <Box x={24} y={64} w={150} h={56} title="源索引" sub="v2 · 3 分片" />
        <Arrow x1={174} y1={92} x2={222} y2={92} arrow={a} />
        <Box x={222} y={64} w={150} h={56} title="批处理" sub="1000–2000 条/批" tone="accent" />
        <Arrow x1={372} y1={92} x2={430} y2={92} arrow={a} tone="ok" />
        <Box x={430} y={64} w={160} h={56} title="目标索引" sub="新 mapping" tone="ok" />
        <Arrow x1={497} y1={120} x2={497} y2={158} arrow={a} tone="err" dashed />
        <Box x={430} y={158} w={160} h={44} title="409 冲突" sub="version_conflicts" tone="err" />
        <Label x={24} y={196} text="冲突来自目标侧已有的 _id：默认中止，conflicts=proceed 可跳过并计数" />
      </>
    )} />
  );
}

/** 模板解析：多个模板按 priority 合并出最终结果 */
export function TemplateResolution() {
  return (
    <Frame height={200} label="索引模板按 priority 合并，最终生成 settings 与 mappings" draw={(a) => (
      <>
        <Box x={24} y={72} w={150} h={54} title="新建索引" sub="eslab-goods-tpl-2026" />
        <Arrow x1={174} y1={88} x2={248} y2={62} arrow={a} />
        <Arrow x1={174} y1={110} x2={248} y2={134} arrow={a} />
        <Box x={248} y={38} w={160} h={48} title="模板 A" sub="priority 200 · 5s" tone="accent" />
        <Box x={248} y={112} w={160} h={48} title="模板 B" sub="priority 100 · 分片数" />
        <Arrow x1={408} y1={62} x2={466} y2={88} arrow={a} />
        <Arrow x1={408} y1={136} x2={466} y2={110} arrow={a} />
        <Box x={466} y={70} w={170} h={56} title="最终定义" sub="settings + mappings" tone="ok" />
        <Label x={24} y={178} text="模板只在创建索引时求值；改模板不会追溯修改已有索引" />
      </>
    )} />
  );
}

/** 动态模板的分支：命中模板走模板，否则走动态推断 */
export function DynamicTemplateBranch() {
  return (
    <Frame height={220} label="新字段先匹配动态模板，命中则按模板，否则走类型推断" draw={(a) => (
      <>
        <Box x={20} y={80} w={140} h={56} title="新字段" sub="attrs.color" />
        <Arrow x1={160} y1={108} x2={206} y2={108} arrow={a} />
        <Box x={206} y={80} w={150} h={56} title="匹配模板？" sub="按顺序，首个命中" tone="accent" />
        <Arrow x1={356} y1={94} x2={410} y2={54} arrow={a} tone="ok" />
        <Arrow x1={356} y1={108} x2={410} y2={108} arrow={a} />
        <Arrow x1={356} y1={122} x2={410} y2={162} arrow={a} dashed />
        <Box x={410} y={32} w={170} h={44} title="flattened" sub="整棵对象当字段" tone="ok" />
        <Box x={410} y={86} w={170} h={44} title="keyword" sub="字符串默认" />
        <Box x={410} y={140} w={170} h={44} title="动态推断" sub="text + keyword" dashed />
        <Label x={20} y={196} text="模板按声明顺序匹配，第一个命中就停 —— 具体规则要放在通用规则前面" />
      </>
    )} />
  );
}

/** object / nested / flattened 三种建模的差别 */
export function ObjectFlattenedNested() {
  const panels = [
    { x: 14, title: 'object（拍平）', items: ['attrs.color=黑', 'attrs.size=L'], note: '跨元素会误匹配' },
    { x: 246, title: 'nested（独立文档）', items: ['每条规格独立', '可跨字段约束'], note: '成本高，查询要 nested' },
    { x: 478, title: 'flattened（单字段）', items: ['整棵对象一个字段', 'term 直接查子键'], note: '不支持范围与排序' },
  ];
  return (
    <Frame height={240} label="object、nested、flattened 三种建模方式对比" draw={() => (
      <>
        {panels.map((p, i) => (
          <g key={p.title}>
            <rect x={p.x} y={30} width={214} height={150} rx="10" fill={C.panel} stroke={C.border} strokeWidth="1.2" />
            <Label x={p.x + 14} y={52} text={p.title} color={C.text} weight={500} size={12.5} />
            {p.items.map((t, j) => (
              <Box key={t} x={p.x + 14} y={66 + j * 44} w={186} h={34} title={t} />
            ))}
            <Label x={p.x + 14} y={168} text={p.note} color={i === 0 ? C.err : C.muted} />
          </g>
        ))}
        <Label x={14} y={206} text="只有「同一数组元素内跨字段」才值得上 nested；属性只想查得到，flattened 更省" />
        <Label x={14} y={226} text="object 的坑：颜色是黑、尺码是 L 可能来自两条不同的规格行" />
      </>
    )} />
  );
}

/** 入库前的加工链：每一步都能在 _simulate 里看到结果 */
export function IngestPipeline() {
  const steps = ['set 时间戳', 'rename 改名', 'gsub 清空白', 'script 算税价', 'remove 删内部字段'];
  return (
    <Frame height={200} label="ingest pipeline 的处理器链" draw={(a) => (
      <>
        <Box x={16} y={72} w={100} h={56} title="脏文档" sub="old_sku" />
        {steps.map((s, i) => (
          <g key={s}>
            <Arrow
              x1={i === 0 ? 116 : 120 + i * 118}
              y1={100}
              x2={128 + i * 118}
              y2={100}
              arrow={a}
            />
            <Box x={128 + i * 118} y={72} w={110} h={56} title={s.split(' ')[0]} sub={s.split(' ')[1]} tone={i === 4 ? 'err' : i === 2 ? 'accent' : 'plain'} />
          </g>
        ))}
        <Label x={16} y={168} text="写入时带 ?pipeline=id；_simulate 是它的单元测试，能看到每个字段处理后的值" />
        <Label x={16} y={188} text="处理器只作用于单条文档，跨文档的关联要在写入前完成" />
      </>
    )} />
  );
}

/** 乐观并发：两条写请求拿着同一个 _seq_no，后到的被拒 */
export function ConcurrencySeqNo() {
  return (
    <Frame height={230} label="两个客户端拿同一个 _seq_no 更新，后到者收到 409" draw={(a) => (
      <>
        <Box x={20} y={34} w={130} h={52} title="客户端 A" sub="读到 _seq_no=7" />
        <Box x={20} y={132} w={130} h={52} title="客户端 B" sub="也读到 7" />
        <Arrow x1={150} y1={60} x2={266} y2={90} arrow={a} tone="ok" />
        <Arrow x1={150} y1={158} x2={266} y2={128} arrow={a} tone="err" />
        <Box x={266} y={84} w={150} h={56} title="主分片" sub="当前 _seq_no=7" tone="accent" />
        <Arrow x1={416} y1={96} x2={500} y2={66} arrow={a} tone="ok" />
        <Arrow x1={416} y1={128} x2={500} y2={158} arrow={a} tone="err" />
        <Box x={500} y={40} w={190} h={52} title="A 成功" sub="_seq_no 变成 8" tone="ok" />
        <Box x={500} y={132} w={190} h={52} title="B 被拒 409" sub="还拿 7 来写" tone="err" />
        <Label x={20} y={206} text="不带 if_seq_no 的更新会直接覆盖 —— 这就是「丢更新」，库存场景必须带" />
      </>
    )} />
  );
}
