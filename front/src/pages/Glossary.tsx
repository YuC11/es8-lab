import { goHome } from '../lib/router';

const TERMS: Array<{ term: string; en: string; desc: string }> = [
  {
    term: '索引',
    en: 'index',
    desc: '逻辑命名空间，也是查询的入口。物理上由一组分片组成；名字一旦创建，分片数就定死了。',
  },
  {
    term: '分片',
    en: 'shard',
    desc: '一个 Lucene 实例，承载一部分文档。文档落到哪个分片由 hash(_id) % 分片数 决定。分片数只能建索引时定。',
  },
  {
    term: '副本',
    en: 'replica',
    desc: '主分片的拷贝，能分担查询、在主分片故障时顶上。单节点集群设 0，否则分片无处分配，健康状态卡 yellow。',
  },
  {
    term: '段',
    en: 'segment',
    desc: '不可变的倒排索引文件。每次 refresh 生成新段；删除只是标记，空间要等 merge 才回收。段越多，查询要扫的越多。',
  },
  {
    term: 'refresh',
    en: 'refresh',
    desc: '把内存 buffer 落成一个可搜索的段，默认每 1 秒一次。它决定「看得见」，不保证「丢不了」。',
  },
  {
    term: 'flush',
    en: 'flush',
    desc: '把段 fsync 到磁盘并清空 translog。它决定持久化，与 refresh 是两个时间尺度。',
  },
  {
    term: 'translog',
    en: 'translog',
    desc: '写前日志。文档先写它再进内存 buffer，保证进程崩溃不丢数据；GET by _id 就是直接读它，所以是实时的。',
  },
  {
    term: '映射',
    en: 'mapping',
    desc: '每个字段的类型与索引方式。不写也能建索引——类型会在第一次写入时被动态推断，而推断经常猜错。',
  },
  {
    term: '分词器',
    en: 'analyzer',
    desc: '把文本切成词项的规则。索引期与查询期各有一套（analyzer / search_analyzer），两者不一致会直接影响召回。',
  },
  {
    term: 'doc_values',
    en: 'doc_values',
    desc: '列式的正排存储，排序与聚合靠它。text 字段默认不建，所以 sort title 会报错，要用 title.keyword。',
  },
  {
    term: '倒排索引',
    en: 'inverted index',
    desc: '词项 → 文档列表 的映射，全文检索的基础。term 查询直接查这张表，所以查的词项必须真实存在。',
  },
  {
    term: 'PIT',
    en: 'point in time',
    desc: '某一时刻的索引快照视图。深分页时配合 search_after 使用，避免翻页过程中数据变化导致漏读或重复读。',
  },
];

export default function Glossary() {
  return (
    <div className="level-page">
      <div className="crumb">术语表</div>
      <h1>12 个词，撑起整个 ES</h1>
      <p className="hook">遇到看不懂的概念时回到这里。每个词都对应课程里某一关的实际现象。</p>

      <div className="card">
        <div className="glossary">
          {TERMS.map((t) => (
            <div className="glossary-item" key={t.en}>
              <div className="glossary-term">
                {t.term}
                <code>{t.en}</code>
              </div>
              <div className="glossary-desc">{t.desc}</div>
            </div>
          ))}
        </div>
      </div>

      <div className="toolbar">
        <button className="btn small" onClick={goHome}>
          回路线图
        </button>
      </div>
    </div>
  );
}
