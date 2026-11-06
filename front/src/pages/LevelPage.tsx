import { useEffect, useMemo, useRef, useState } from 'react';
import { runLevel, type LevelRunResponse } from '../lib/api';
import type { DataState, Health, Level, Module } from '../lib/types';
import { goDeep, goHome, goLevel } from '../lib/router';
import { markDone, setLast } from '../lib/progress';
import { deepStats, fmtNum } from '../lib/deep';
import ResultView from '../components/ResultView';
import ProbeLab from '../components/ProbeLab';
import CallList from '../components/CallList';
import { callsOf } from '../lib/levelCalls';
import CodeDrawer from '../components/CodeDrawer';
import ExperimentPanel from '../components/ExperimentPanel';
import PrereqBanner, { checkPrereq } from '../components/Prereq';

interface Props {
  module: Module;
  level: Level;
  indexInModule: number;
  health: Health | null;
  data: DataState | null;
  onOpenConsole: (method: string, path: string, body?: unknown) => void;
  onDataChange: () => void;
}

function fmtBytes(n: number) {
  if (!n) return '0 B';
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

export default function LevelPage({
  module,
  level,
  indexInModule,
  health,
  data,
  onOpenConsole,
  onDataChange,
}: Props) {
  const [run, setRun] = useState<LevelRunResponse | null>(null);
  const [running, setRunning] = useState(false);
  const [codeOpen, setCodeOpen] = useState(false);
  const topRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setRun(null);
    setCodeOpen(false);
    setLast(level.id);
    topRef.current?.scrollIntoView({ block: 'start' });
  }, [level.id]);

  const ready = useMemo(() => checkPrereq(level.prereq, health, data), [level.prereq, health, data]);
  const calls = useMemo(() => callsOf(level.id), [level.id]);
  const stats = useMemo(() => deepStats(level.deep ?? []), [level.deep]);

  const doRun = async () => {
    setRunning(true);
    setRun(null);
    try {
      const r = await runLevel(level.id);
      setRun(r);
      if (r.ok) {
        markDone(level.id);
        onDataChange();
      }
    } catch (e) {
      setRun({ ok: false, id: level.id, error: (e as Error).message, elapsedMs: 0 });
    } finally {
      setRunning(false);
    }
  };

  const indices = [data?.v1, data?.v2].filter(Boolean);
  const nextId = level.next;

  return (
    <div className="level-page" ref={topRef}>
      <div className="crumb">
        {module.name} · 第 {indexInModule + 1} 关 / 共 {module.levels.length} 关
        <span className="crumb-dim"> · {level.id}</span>
      </div>
      <h1>{level.title}</h1>
      {level.objective && (
        <div className="obj">
          <span className="obj-tag">这一关要回答</span>
          <span>{level.objective}</span>
        </div>
      )}
      <p className="hook">{level.hook}</p>

      <PrereqBanner prereq={level.prereq} health={health} data={data} />

      {/* ② 你现在手上有什么 —— 直接回答「我这是在哪」 */}
      <div className="hand">
        <span className="hand-title">你现在手上有什么</span>
        {indices.length === 0 || !indices.some((i) => i!.exists) ? (
          <span className="hand-item muted">还没有任何实验索引</span>
        ) : (
          indices.map((i) =>
            i!.exists ? (
              <span className="hand-item" key={i!.index}>
                <code>{i!.index}</code>
                {i!.docs.toLocaleString()} 条 · {i!.shards} 分片 · {fmtBytes(i!.storeBytes)}
              </span>
            ) : (
              <span className="hand-item muted" key={i!.index}>
                <code>{i!.index}</code>未创建
              </span>
            ),
          )
        )}
      </div>

      {/* ③ 讲解：brief 留在原地，深入原理搬到独立阅读页 */}
      <div className="card">
        <div className="card-title">
          <span>讲解</span>
        </div>
        <div className="prose">
          <p>{level.brief}</p>
        </div>
        {stats.chars > 0 && (
          <div className="deep-entry">
            <div className="deep-entry-main">
              <div className="deep-entry-title">深入原理</div>
              <div className="deep-entry-sub">
                {fmtNum(stats.chars)} 字 · {stats.sections} 节 · {stats.figures} 张原理图 · 约{' '}
                {stats.minutes} 分钟
              </div>
            </div>
            <button className="btn small primary" onClick={() => goDeep(level.id)}>
              阅读全文 →
            </button>
          </div>
        )}
      </div>

      {/* ④ 先猜 → 动手 */}
      <div className="card">
        <div className="card-title">
          <span>动手</span>
          <span className="title-note">
            {run?.ok ? `上次运行 ${run.elapsedMs} ms` : ''}
          </span>
        </div>

        {level.predict && (
          <div className="predict">
            <span className="predict-tag">先猜一下</span>
            <span>{level.predict}</span>
            <span className="predict-hint">不用写下来，跑完对照结果即可</span>
          </div>
        )}

        {level.steps.map((s, i) => (
          <div className="step" key={i}>
            <div className="step-num">{i + 1}</div>
            <div className="step-body">
              <div className="step-label">{s.label}</div>
              <div className="step-desc">{s.desc}</div>
              <div className="step-actions">
                {s.kind === 'level' ? (
                  <>
                    <button className="btn primary small" onClick={doRun} disabled={running || !ready.ok}>
                      {running ? (
                        <>
                          <span className="spinner" />
                          执行中
                        </>
                      ) : (
                        '运行本关'
                      )}
                    </button>
                    {calls.length > 0 && (
                      <span className="step-hint">后端会按顺序发 {calls.length} 条请求，清单在下方</span>
                    )}
                  </>
                ) : (
                  <>
                    <button
                      className="btn small"
                      onClick={() => onOpenConsole(s.method ?? 'POST', s.path ?? '/_search', s.body)}
                    >
                      在控制台执行
                    </button>
                    {s.path && <code className="step-path">{s.path}</code>}
                  </>
                )}
              </div>
              {s.kind === 'level' && <CallList calls={calls} onOpenConsole={onOpenConsole} />}
            </div>
          </div>
        ))}
      </div>

      {/* ⑤ 观察 */}
      {run && !run.ok && (
        <div className="callout error">
          <span className="tag">执行失败</span>
          <span>
            {run.error}
            {run.hint ? (
              <>
                <br />
                {run.hint}
              </>
            ) : null}
          </span>
        </div>
      )}
      {run && run.ok && run.result && (
        <div className="card">
          <div className="card-title">
            <span>你看到了什么</span>
            <span className="title-note">数字来自后端刚执行的那次请求</span>
          </div>
          <ResultView result={run.result} />
        </div>
      )}

      {/* ⑥ 所以呢 */}
      <div className={`takeaway${run?.ok ? '' : ' pending'}`}>
        <span className="takeaway-tag">{run?.ok ? '所以呢' : '跑完再看'}</span>
        <span>{run?.ok ? level.takeaway : '先执行上面的「运行本关」，这里会给出这一关的结论。'}</span>
      </div>

      <div className="callout pitfall">
        <span className="tag">陷阱</span>
        <span>{level.pitfall}</span>
      </div>

      {level.id === 'm7-2' && health && (
        <div className="card">
          <div className="card-title">
            <span>优化实验台（4 个 A/B 对照）</span>
          </div>
          <ExperimentPanel experiments={health.experiments} />
        </div>
      )}

      <div className="card">
        <div className="card-title">
          <span>变体实验 · 改一个参数看结果</span>
          <span className="title-note">每条都是一次真实请求</span>
        </div>
        <ProbeLab items={level.probes} onOpenConsole={onOpenConsole} />
      </div>

      <div className="toolbar">
        <button className="btn small" onClick={() => setCodeOpen(true)}>
          查看本关后端实现
        </button>
        <button className="btn small ghost" onClick={() => onOpenConsole('POST', '/_search', undefined)}>
          打开控制台
        </button>
        <button className="btn small ghost" onClick={goHome}>
          回路线图
        </button>
      </div>

      <div className="next-nav">
        {nextId ? (
          <button className="btn primary" onClick={() => goLevel(nextId)}>
            下一关：{nextId} →
          </button>
        ) : (
          <span className="next-end">这是最后一关。回路线图看看整体进度。</span>
        )}
      </div>

      {codeOpen && <CodeDrawer levelId={level.id} onClose={() => setCodeOpen(false)} />}
    </div>
  );
}
