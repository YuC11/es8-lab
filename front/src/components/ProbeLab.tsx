import { useState } from 'react';
import { runProbe, type ProbeResponse } from '../lib/api';
import type { Probe } from '../lib/types';

interface Row {
  loading: boolean;
  res?: ProbeResponse;
  showRaw?: boolean;
}

/**
 * 变体实验：每张卡就是一条可以立即执行的真实请求。
 *
 * 没有选项、没有判分、没有「正确答案」—— 因为这不是考试。
 * 你改的是参数，看的是 ES 真实返回的观测值，结论自己得出。
 */
export default function ProbeLab({
  items,
  onOpenConsole,
}: {
  items: Probe[];
  onOpenConsole: (method: string, path: string, body: unknown) => void;
}) {
  const [rows, setRows] = useState<Record<number, Row>>({});

  const exec = async (i: number, p: Probe) => {
    setRows((r) => ({ ...r, [i]: { ...r[i], loading: true } }));
    try {
      const res = await runProbe({ method: p.method, path: p.path, body: p.body });
      setRows((r) => ({ ...r, [i]: { ...r[i], loading: false, res } }));
    } catch (e) {
      setRows((r) => ({
        ...r,
        [i]: {
          ...r[i],
          loading: false,
          res: { ok: false, status: 0, ms: 0, error: (e as Error).message },
        },
      }));
    }
  };

  return (
    <div>
      {items.map((p, i) => {
        const row = rows[i];
        const res = row?.res;
        const facts = res?.summary?.facts ?? [];
        return (
          <div className="probe-item" key={i}>
            <div className="probe-head">
              <span className="probe-no">{String(i + 1).padStart(2, '0')}</span>
              <div style={{ minWidth: 0 }}>
                <div className="probe-task">{p.task}</div>
                <div className="probe-req">
                  <span className="probe-method">{p.method ?? 'POST'}</span>
                  <span className="probe-path">{p.path}</span>
                </div>
              </div>
            </div>

            <div className="step-actions" style={{ marginTop: 10 }}>
              <button className="btn primary small" onClick={() => exec(i, p)} disabled={row?.loading}>
                {row?.loading ? (
                  <>
                    <span className="spinner" />
                    执行中
                  </>
                ) : res ? (
                  '再跑一次'
                ) : (
                  '执行'
                )}
              </button>
              <button
                className="btn small ghost"
                onClick={() => onOpenConsole(p.method ?? 'POST', p.path, p.body)}
              >
                在控制台打开
              </button>
              {res && (
                <span className={`probe-status ${res.ok ? 'ok' : 'err'}`}>
                  {res.status ? `HTTP ${res.status}` : '未响应'} · {Math.round(res.ms)}ms
                </span>
              )}
            </div>

            <div className="probe-watch">
              <b>看这里：</b>
              {p.watch}
            </div>

            {res && (
              <>
                <div className="probe-facts">
                  {facts.length > 0 ? (
                    facts.map((f, fi) => (
                      <span className={`fact ${res.ok ? '' : 'err'}`} key={fi}>
                        {f}
                      </span>
                    ))
                  ) : (
                    <span className="fact">{res.error ?? '没有可归纳的指标'}</span>
                  )}
                </div>
                <details
                  className="raw"
                  open={row?.showRaw}
                  onToggle={(e) =>
                    setRows((r) => ({
                      ...r,
                      [i]: { ...r[i], showRaw: (e.target as HTMLDetailsElement).open },
                    }))
                  }
                >
                  <summary>完整响应</summary>
                  <pre className="json">{JSON.stringify(res.data ?? res.error, null, 2)}</pre>
                </details>
              </>
            )}

            <details className="probe-expect">
              <summary>跑完会看到什么</summary>
              <div>{p.expect}</div>
            </details>
          </div>
        );
      })}
    </div>
  );
}
