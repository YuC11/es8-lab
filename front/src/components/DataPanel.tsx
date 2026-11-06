import { useEffect, useState } from 'react';
import { createIndexApi, dropIndexApi, getJob, startBulk } from '../lib/api';
import type { DataState, Health, IndexState, JobView } from '../lib/types';

interface Props {
  data: DataState | null;
  health: Health | null;
  onChange: () => void;
}

function fmtBytes(n: number) {
  if (!n) return '0 B';
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

function Row({
  title,
  badge,
  state,
  children,
}: {
  title: string;
  badge: string;
  state: IndexState | null;
  children: React.ReactNode;
}) {
  return (
    <div className="data-row">
      <div className="data-main">
        <div className="data-title">
          <code>{title}</code>
          <span className="data-badge">{badge}</span>
          {state?.exists ? (
            <span className="data-state ok">已就绪</span>
          ) : (
            <span className="data-state">未创建</span>
          )}
        </div>
        <div className="data-stats">
          {state?.exists
            ? `${state.docs.toLocaleString()} 条 · ${state.shards} 分片 · ${state.segments} 段 · ${fmtBytes(
                state.storeBytes,
              )} · ${state.fields} 个字段`
            : '空的 —— 这正是课程的起点'}
        </div>
      </div>
      <div className="data-actions">{children}</div>
    </div>
  );
}

/**
 * 环境与数据面板：把「建索引 / 灌数据」做成页面里能点的按钮。
 *
 * 它和 sh/seed.sh 走的是同一份后端代码（DatasetService），
 * 区别只是：这里每一步都看得见进度、耗时和最终体积。
 */
export default function DataPanel({ data, health, onChange }: Props) {
  const [job, setJob] = useState<JobView | null>(null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState('');

  // 轮询真实进度：不是假动画，written 来自后端 bulk 的累计写入量
  useEffect(() => {
    if (!job || job.state !== 'RUNNING') return;
    const timer = setInterval(async () => {
      try {
        const j = await getJob(job.id);
        setJob(j);
        if (j.state !== 'RUNNING') onChange();
      } catch {
        setJob(null);
      }
    }, 500);
    return () => clearInterval(timer);
  }, [job?.id, job?.state, onChange]);

  const run = async (label: string, fn: () => Promise<void>) => {
    setBusy(label);
    setErr('');
    try {
      await fn();
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setBusy('');
    }
  };

  const create = (v: 'v1' | 'v2') =>
    run(`create-${v}`, async () => {
      await createIndexApi(v);
      onChange();
    });

  const rebuild = (v: 'v1' | 'v2') =>
    run(`rebuild-${v}`, async () => {
      if (!window.confirm(`重建会删除 ${v} 里的全部数据（只影响 eslab-* 前缀索引）。继续？`)) return;
      await dropIndexApi(v);
      await createIndexApi(v);
      onChange();
    });

  const bulk = (v: 'v1' | 'v2', count: number) =>
    run(`bulk-${v}-${count}`, async () => {
      const r = await startBulk(v, count);
      if (r.skipped) {
        setErr(`已跳过写入：${r.message ?? '文档数已达目标'}`);
        onChange();
        return;
      }
      if (!r.jobId) throw new Error(r.error ?? '任务创建失败');
      setJob(await getJob(r.jobId));
    });

  const pct = job && job.total > 0 ? Math.min(100, (job.written / job.total) * 100) : 0;
  const up = health?.cluster.up;

  return (
    <div className="card data-panel">
      <div className="card-title">
        <span>环境与数据</span>
        <span className="data-env">
          {up ? (
            <>
              ES {health?.cluster.version} · {health?.cluster.health} · {health?.cluster.nodes} 节点
            </>
          ) : (
            <span style={{ color: 'var(--err)' }}>ES 未连接</span>
          )}
        </span>
      </div>

      <Row title="eslab-products-v1" badge="全动态映射" state={data?.v1 ?? null}>
        {data?.v1?.exists ? (
          <>
            <button className="btn small" disabled={!!busy} onClick={() => bulk('v1', data.target)}>
              {busy?.startsWith('bulk-v1') ? '写入中…' : `灌到 ${(data.target / 10000).toFixed(0)} 万`}
            </button>
            <button className="btn small" disabled={!!busy} onClick={() => bulk('v1', data.full)}>
              灌到 {(data.full / 10000).toFixed(0)} 万
            </button>
            <button className="btn small ghost" disabled={!!busy} onClick={() => rebuild('v1')}>
              重建
            </button>
          </>
        ) : (
          <button className="btn small primary" disabled={!!busy} onClick={() => create('v1')}>
            创建 v1
          </button>
        )}
      </Row>

      <Row title="eslab-products-v2" badge="显式 Mapping" state={data?.v2 ?? null}>
        {data?.v2?.exists ? (
          <>
            <button className="btn small" disabled={!!busy} onClick={() => bulk('v2', data.target)}>
              {busy?.startsWith('bulk-v2') ? '写入中…' : `灌到 ${(data.target / 10000).toFixed(0)} 万`}
            </button>
            <button className="btn small" disabled={!!busy} onClick={() => bulk('v2', data.full)}>
              灌到 {(data.full / 10000).toFixed(0)} 万
            </button>
            <button className="btn small ghost" disabled={!!busy} onClick={() => rebuild('v2')}>
              重建
            </button>
          </>
        ) : (
          <button className="btn small" disabled={!!busy} onClick={() => create('v2')}>
            创建 v2
          </button>
        )}
      </Row>

      {job && (
        <div className="job">
          <div className="job-head">
            <span>
              {job.state === 'RUNNING' ? '正在写入' : job.state === 'DONE' ? '写入完成' : '写入失败'}
              {job.batches > 1 && ` · 第 ${job.batchIndex}/${job.batches} 批`}
            </span>
            <span className="job-num">
              {job.written.toLocaleString()} / {job.total.toLocaleString()} 条 · {job.rate} docs/s
              {job.state === 'DONE' && job.result ? ` · 段数 ${job.result.segments}` : ''}
            </span>
          </div>
          <div className="job-track">
            <div className={`job-fill${job.state === 'FAILED' ? ' failed' : ''}`} style={{ width: `${pct}%` }} />
          </div>
          {job.error && <div className="data-err">{job.error}</div>}
        </div>
      )}

      {err && <div className="data-err">{err}</div>}

      <div className="data-note">
        这里的每一步都是一次真实请求，与 sh/seed.sh 走同一份后端代码；重建只会删除 eslab-* 前缀的索引。
      </div>
    </div>
  );
}
