import { useEffect, useMemo, useState } from 'react';
import { getFields } from '../lib/api';
import type { FieldDict, FieldInfo } from '../lib/types';
import { goHome, goLevel } from '../lib/router';
import { buildQuery } from '../lib/fieldQuery';

interface Props {
  onOpenConsole: (method: string, path: string, body?: unknown) => void;
}

/** 请求体顶层能出现的键：只有 query 是「查什么」，其余都是「怎么给」 */
const BODY_KEYS = [
  { key: 'query', desc: '查什么。唯一的必写项；不写等价于 match_all。' },
  { key: 'size', desc: '返回几条文档，默认 10。size: 0 表示只要统计、不要文档。' },
  { key: 'from', desc: '跳过前几条。from + size 超过 10000 会被拒绝（见 m6-1）。' },
  { key: '_source', desc: '只返回这些字段，可显著减少传输与序列化开销。' },
  { key: 'sort', desc: '排序。text 字段必须改用 .keyword 子字段（见 m5-2）。' },
  { key: 'aggs', desc: '聚合：分桶（terms/range）与统计（avg/percentiles）。' },
  { key: 'track_total_hits', desc: 'true 精确计数；false 不返回；不写只保证算到 10000。' },
];

/** 响应体的固定骨架：读懂它，才知道结果是「对」还是「安静地错了」 */
const RESP_KEYS = [
  { path: 'took', desc: 'ES 内部耗时（毫秒），不含网络往返' },
  { path: 'timed_out', desc: '是否因为超时提前返回' },
  { path: '_shards.total / successful', desc: '打到的分片副本数 / 成功数' },
  { path: 'hits.total.value', desc: '命中总数' },
  { path: 'hits.total.relation', desc: 'eq = 精确值；gte = 只是下限（默认只算到 10000）' },
  { path: 'hits.max_score', desc: '本页最高分。size: 0 时为 null，不是错误' },
  { path: 'hits.hits[]._score', desc: '单条文档的相关性分' },
  { path: 'hits.hits[]._source', desc: '原始文档，可被 _source 裁剪' },
];

/** 六种最常用的查询：写法、参数、适用字段 */
const QUERIES = [
  {
    name: 'match_all',
    fields: '任意',
    code: '{"query": {"match_all": {}}}',
    params: 'boost',
    note: '全部命中，分数恒为 1.0。空请求体就是它。',
  },
  {
    name: 'term',
    fields: 'keyword / 数值 / 布尔',
    code: '{"query": {"term": {"category": "数码影音"}}}',
    params: 'value / boost',
    note: '不分词，整串精确匹配。打在 text 上会静默返回 0 条。',
  },
  {
    name: 'match',
    fields: 'text',
    code: '{"query": {"match": {"title": "蓝牙耳机"}}}',
    params: 'query / operator / minimum_should_match / analyzer',
    note: '先分词再匹配，算相关性分。默认 operator: or。',
  },
  {
    name: 'range',
    fields: '数值 / 日期',
    code: '{"query": {"range": {"price": {"gte": 100, "lte": 200}}}}',
    params: 'gt / gte / lt / lte / format',
    note: '区间查询，数值字段走 BKD 树。gte 的 e 是 equal（包含）。',
  },
  {
    name: 'bool',
    fields: '组合其他查询',
    code: '{"query": {"bool": {"must": [{"match": {"title": "耳机"}}], "filter": [{"range": {"price": {"gte": 100, "lte": 500}}}]}}}',
    params: 'minimum_should_match / boost',
    note: '不查字段，只组合。四个子句可只用其一：must 算分、filter 不算分且可缓存、should 加分、must_not 排除。',
  },
  {
    name: 'constant_score',
    fields: '只要「是否命中」',
    code: '{"query": {"constant_score": {"filter": {"term": {"status": 1}}}}}',
    params: 'boost',
    note: '跳过评分器分配，分数固定为 boost 值。',
  },
];

export default function DslRef({ onOpenConsole }: Props) {
  const [fields, setFields] = useState<FieldDict | null>(null);
  const [which, setWhich] = useState<'v2' | 'v1'>('v2');
  const [err, setErr] = useState('');

  useEffect(() => {
    getFields()
      .then(setFields)
      .catch((e: Error) => setErr(e.message));
  }, []);

  const list = useMemo(() => (fields ? fields[which] : []), [fields, which]);
  const hasKeywordFields = list.filter((f) => f.hasKeyword).map((f) => f.name);

  return (
    <div className="dsl-ref">
      <div className="roadmap-hero">
        <div className="crumb">参考 · 查询速查</div>
        <h1>查询速查</h1>
        <p className="hook">
          这里是「怎么写」的对照表，不是教程。为什么这么设计，看{' '}
          <button className="btn-link" onClick={() => goLevel('m4-1')}>
            m4-1 一个 _search 请求由什么组成
          </button>
          ；想从零学起，走{' '}
          <button className="btn-link" onClick={() => goLevel('m4-2')}>
            m4-2 从空请求体到 bool
          </button>
          。
        </p>
        <div className="roadmap-cta">
          <button className="btn ghost" onClick={goHome}>
            回路线图
          </button>
        </div>
      </div>

      {err && (
        <div className="callout error">
          <span className="tag">读不到字段</span>
          <span>{err}（后端未启动时只有这份表，字段字典会空着）</span>
        </div>
      )}

      <section className="card">
        <div className="card-title">
          <span>请求体骨架</span>
          <span className="dsl-hint">只有 query 是必写的</span>
        </div>
        <pre className="dsl-code">{`POST /eslab-products-v2/_search
{
  "query":   { ... },        // 查什么
  "size":    0,              // 怎么给：几条
  "from":    0,              // 怎么给：从第几条
  "_source": ["title"],      // 怎么给：要哪些字段
  "sort":    [{ "price": "asc" }],
  "aggs":    { ... },
  "track_total_hits": true
}`}</pre>
        <div className="table-wrap" style={{ marginTop: 12 }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>键</th>
                <th>作用</th>
              </tr>
            </thead>
            <tbody>
              {BODY_KEYS.map((k) => (
                <tr key={k.key}>
                  <td>{k.key}</td>
                  <td>{k.desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card">
        <div className="card-title">
          <span>响应体地图</span>
          <span className="dsl-hint">max_score 为 null 不是错误</span>
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>路径</th>
                <th>含义</th>
              </tr>
            </thead>
            <tbody>
              {RESP_KEYS.map((r) => (
                <tr key={r.path}>
                  <td>{r.path}</td>
                  <td>{r.desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card">
        <div className="card-title">
          <span>六种查询</span>
          <span className="dsl-hint">点「试」送进底部控制台</span>
        </div>
        <div className="dsl-queries">
          {QUERIES.map((q) => (
            <div className="dsl-query" key={q.name}>
              <div className="dsl-query-head">
                <code className="dsl-query-name">{q.name}</code>
                <span className="dsl-query-fields">{q.fields}</span>
                <button
                  className="btn-link dsl-try"
                  onClick={() =>
                    onOpenConsole(
                      'POST',
                      '/eslab-products-v2/_search',
                      JSON.parse(q.code),
                    )
                  }
                >
                  试
                </button>
              </div>
              <pre className="dsl-code small">{q.code}</pre>
              <div className="dsl-query-note">{q.note}</div>
              <div className="dsl-query-params">参数：{q.params}</div>
            </div>
          ))}
        </div>
      </section>

      <section className="card">
        <div className="card-title">
          <span>字段字典</span>
          <div className="dsl-switch">
            <button
              className={`btn-link${which === 'v2' ? ' on' : ''}`}
              onClick={() => setWhich('v2')}
            >
              v2（显式 Mapping）
            </button>
            <span className="sidebar-sep">·</span>
            <button
              className={`btn-link${which === 'v1' ? ' on' : ''}`}
              onClick={() => setWhich('v1')}
            >
              v1（动态映射）
            </button>
          </div>
        </div>
        {list.length === 0 ? (
          <div className="dsl-empty">
            这个索引还没建。先到路线图的「数据工坊」创建，或走第 0 章。
          </div>
        ) : (
          <>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>字段</th>
                    <th>类型</th>
                    <th>可聚合</th>
                    <th>用途</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {list.map((f) => {
                    const q = buildQuery(f);
                    return (
                      <tr key={f.name}>
                        <td>{f.name}</td>
                        <td>
                          {f.type}
                          {f.hasKeyword && <span className="dsl-sub"> + .keyword</span>}
                        </td>
                        <td>{f.aggregatable ? '✓' : '—'}</td>
                        <td className="dsl-role">{f.role || '—'}</td>
                        <td>
                          {q ? (
                            <button
                              className="btn-link"
                              title="按字段类型生成一条最合适的查询"
                              onClick={() => onOpenConsole('POST', `/eslab-products-${which}/_search`, q)}
                            >
                              试
                            </button>
                          ) : (
                            <span className="dsl-sub">—</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            {hasKeywordFields.length > 0 && (
              <div className="dsl-foot">
                {which === 'v1'
                  ? 'v1 里 text 字段带 .keyword 子字段，排序与聚合必须用它（见 m1-3）。'
                  : 'v2 里 category / brand / tags 本身就是 keyword，直接查字段名，不要再加 .keyword。'}
              </div>
            )}
          </>
        )}
      </section>
    </div>
  );
}
