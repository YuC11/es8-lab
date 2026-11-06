import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import { getLabIndexes } from '../lib/api';
import type { LabIndexRow, LabIndexesState } from '../lib/types';

/**
 * 实验索引总览。
 *
 * 数据来自 /api/lab/indexes —— 它把「注册表里的登记信息」和「集群里的实时状态」并排返回，
 * 所以这一块能同时回答两个问题：这个索引是谁的、该不该存在；以及它现在到底有多少条。
 *
 * 为什么值得放在路线图首屏：跑完一轮课程，集群里会留下 20 多个 eslab-* 索引，
 * 学员想看清「我到底建了什么、哪些是一次性的」，只能去控制台手敲 _cat/indices。
 */

const LIFECYCLE: Record<string, { label: string; cls: string; hint: string }> = {
  PERSISTENT: { label: '常驻', cls: 'lc-persist', hint: '建一次就复用；条数不符才重建' },
  RESET_ON_RUN: { label: '每轮重置', cls: 'lc-reset', hint: '每次运行先清空再灌' },
  RECREATE_ON_RUN: { label: '每轮重建', cls: 'lc-recreate', hint: '每次运行删掉重建，由关卡自己灌' },
  DROP_AFTER_RUN: { label: '跑完即删', cls: 'lc-drop', hint: '关卡结束就删，异常路径也删' },
  ROLLOVER: { label: '滚动族', cls: 'lc-roll', hint: '写别名指向其中一个，rollover 换索引' },
  PLANNED: { label: '仅模拟', cls: 'lc-plan', hint: '只 _simulate，永不创建' },
};

const COST: Record<string, string> = { FAST: '秒级', MEDIUM: '十几秒', SLOW: '分钟级' };

function fmtBytes(n: number) {
  if (!n) return '—';
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

function fmtNum(n: number) {
  if (n < 0) return '—';
  return n.toLocaleString('zh-CN');
}

function chapterLabel(ch: string) {
  const n = Number(ch.replace(/[^0-9]/g, ''));
  return Number.isFinite(n) ? `第 ${n} 章` : ch;
}

function Row({ row }: { row: LabIndexRow }) {
  const lc = LIFECYCLE[row.lifecycle] ?? { label: row.lifecycle, cls: '', hint: '' };
  const over = row.exists && row.targetDocs > 0 && row.docs > row.targetDocs;
  return (
    <tr className={row.exists ? '' : 'lab-index-absent'}>
      <td>
        <code>{row.name}</code>
        {row.aliases.length > 0 && (
          <span className="lab-index-alias" title={`挂在别名 ${row.aliases.join('、')}`}>
            @{row.aliases.join(',')}
          </span>
        )}
      </td>
      <td>
        <span className={`lc ${lc.cls}`} title={lc.hint}>
          {lc.label}
        </span>
      </td>
      <td className="num">
        {row.exists ? fmtNum(row.docs) : '—'}
        <span className="muted"> / {row.targetDocs > 0 ? fmtNum(row.targetDocs) : '按需'}</span>
        {over && <span className="lab-index-over" title="超过目标条数">↑</span>}
      </td>
      <td className="num">{row.exists ? row.shards : '—'}</td>
      <td className="num">{row.exists ? row.segments : '—'}</td>
      <td className="num">{row.exists ? fmtBytes(row.storeBytes) : '—'}</td>
      <td>
        <code className="muted">{row.owner}</code>
      </td>
    </tr>
  );
}

export default function LabIndexPanel() {
  const [state, setState] = useState<LabIndexesState | null>(null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setBusy(true);
    try {
      setState(await getLabIndexes());
      setErr('');
    } catch (e) {
      setErr(String((e as Error).message || e));
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const groups = useMemo(() => {
    if (!state) return [];
    const map = new Map<string, LabIndexRow[]>();
    for (const row of state.indices) {
      const list = map.get(row.chapter) ?? [];
      list.push(row);
      map.set(row.chapter, list);
    }
    return [...map.entries()]
      .sort((a, b) => Number(a[0].replace(/\D/g, '')) - Number(b[0].replace(/\D/g, '')))
      .map(([chapter, rows]) => ({ chapter, rows }));
  }, [state]);

  const total = useMemo(() => {
    const rows = state?.indices ?? [];
    return {
      count: rows.length,
      present: rows.filter((r) => r.exists).length,
      docs: rows.reduce((n, r) => n + (r.exists && r.docs > 0 ? r.docs : 0), 0),
      bytes: rows.reduce((n, r) => n + (r.exists ? r.storeBytes : 0), 0),
    };
  }, [state]);

  return (
    <details className="card lab-index-panel">
      <summary>
        <span className="lab-index-title">实验索引</span>
        <span className="lab-index-sub">
          {state
            ? `${total.count} 个已登记 · ${total.present} 个已存在 · 共 ${fmtNum(total.docs)} 条 · ${fmtBytes(
                total.bytes,
              )}`
            : busy
              ? '正在读取…'
              : '点击展开'}
        </span>
        <span className="lab-index-hint">生命周期决定它是常驻还是一次性的</span>
      </summary>

      {err && <div className="data-err">{err}</div>}

      {state && (
        <>
          <div className="lab-index-toolbar">
            <span className="muted">
              登记处：<code>levels/support/LabIndexes.java</code> —— 名字、定义、目标条数、生命周期都只有一份
            </span>
            <button className="btn small ghost" disabled={busy} onClick={() => void load()}>
              {busy ? '刷新中…' : '刷新'}
            </button>
          </div>

          <div className="lab-index-scroll">
            <table className="lab-index-table">
              <thead>
                <tr>
                  <th>索引</th>
                  <th>生命周期</th>
                  <th className="num">文档 / 目标</th>
                  <th className="num">分片</th>
                  <th className="num">段</th>
                  <th className="num">体积</th>
                  <th>归属</th>
                </tr>
              </thead>
              <tbody>
                {groups.map((g) => (
                  <Fragment key={g.chapter}>
                    <tr className="lab-index-group">
                      <td colSpan={7}>{chapterLabel(g.chapter)}</td>
                    </tr>
                    {g.rows.map((row) => (
                      <Row row={row} key={row.name} />
                    ))}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>

          <div className="lab-index-foot">
            <div>
              <span className="lab-index-label">别名</span>
              {state.aliases.map((a) => (
                <span className="lab-alias" key={a.name}>
                  <code>{a.name}</code>
                  <span className="muted"> → </span>
                  <code>{a.writeIndex || '未创建'}</code>
                  {a.docs >= 0 && <span className="muted"> · {fmtNum(a.docs)} 条</span>}
                </span>
              ))}
            </div>
            <div>
              <span className="lab-index-label">集群资产</span>
              {state.assets.map((a) => (
                <span className="lab-alias" key={a.name}>
                  <code>{a.name}</code>
                  <span className={`lc ${a.exists ? 'lc-persist' : 'lc-plan'}`}>
                    {a.exists ? '已创建' : '未创建'}
                  </span>
                </span>
              ))}
            </div>
            <div className="muted lab-index-cost">
              重置代价：秒级 = 可直接重跑；十几秒 = 要搬 2 万条；分钟级 = 要重灌 20 万条（
              {Object.entries(COST)
                .map(([k, v]) => `${k.toLowerCase()}=${v}`)
                .join(' · ')}
              ）
            </div>
          </div>
        </>
      )}
    </details>
  );
}
