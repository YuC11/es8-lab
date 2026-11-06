import { useState } from 'react';
import { runExperiment } from '../lib/api';
import type { ExperimentBrief, ExperimentResult } from '../lib/types';

export default function ExperimentPanel({ experiments }: { experiments: ExperimentBrief[] }) {
  const [results, setResults] = useState<Record<string, ExperimentResult>>({});
  const [running, setRunning] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});

  const run = async (id: string) => {
    setRunning(id);
    setErrors((e) => ({ ...e, [id]: '' }));
    try {
      const r = await runExperiment(id);
      if (r.ok && r.result) setResults((prev) => ({ ...prev, [id]: r.result! }));
      else setErrors((e) => ({ ...e, [id]: r.error ?? '未知错误' }));
    } catch (e) {
      setErrors((prev) => ({ ...prev, [id]: (e as Error).message }));
    } finally {
      setRunning(null);
    }
  };

  return (
    <div>
      {experiments.map((exp) => {
        const res = results[exp.id];
        const busy = running === exp.id;
        const max = res ? Math.max(res.a.value, res.b.value, 1) : 1;
        return (
          <div className="exp" key={exp.id}>
            <div className="exp-head">
              <div>
                <div className="exp-title">{exp.title}</div>
              </div>
              <button className="btn small primary" onClick={() => run(exp.id)} disabled={busy}>
                {busy ? (
                  <>
                    <span className="spinner" />
                    跑实验中
                  </>
                ) : (
                  '运行实验'
                )}
              </button>
            </div>
            <div className="exp-q">{exp.question}</div>

            {errors[exp.id] && (
              <div className="callout error" style={{ marginBottom: 10 }}>
                <span className="tag">失败</span>
                <span>{errors[exp.id]}</span>
              </div>
            )}

            {res && (
              <>
                <div className="exp-bars">
                  {[res.a, res.b].map((side, i) => (
                    <div className="exp-bar-row" key={i}>
                      <div>
                        <div className="exp-bar-label">{side.label}</div>
                        {side.detail && <div className="exp-samples">{side.detail}</div>}
                      </div>
                      <div className="chart-track">
                        <div
                          className={`chart-fill${i === 1 ? ' alt' : ''}`}
                          style={{ width: `${Math.max(1, (side.value / max) * 100)}%` }}
                        />
                      </div>
                      <div className="chart-value">
                        {side.value} {side.unit}
                      </div>
                    </div>
                  ))}
                </div>
                <div className="exp-samples" style={{ marginBottom: 8 }}>
                  样本：A [{res.a.samples.join(', ')}] ｜ B [{res.b.samples.join(', ')}]
                </div>
                <div className="exp-conclusion">{res.conclusion}</div>
                <ul className="exp-caveats">
                  {res.caveats.map((c, i) => (
                    <li key={i}>{c}</li>
                  ))}
                </ul>
              </>
            )}
            {!res && !busy && !errors[exp.id] && (
              <div style={{ fontSize: 12.5, color: 'var(--muted-2)' }}>
                尚未运行。点击「运行实验」会真实写入/查询 ES 并多次采样，通常需要 3–15 秒。
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
