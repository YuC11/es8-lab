import { useEffect, useState } from 'react';
import { getFields, sendConsole, type ConsoleResponse } from '../lib/api';
import type { FieldInfo } from '../lib/types';
import { buildQuery, TEMPLATES } from '../lib/fieldQuery';

export interface Prefill {
  method: string;
  path: string;
  body: string;
}

interface Props {
  open: boolean;
  onToggle: () => void;
  prefill: Prefill | null;
  onPrefillConsumed: () => void;
}

const DEFAULT_BODY = `{
  "size": 3,
  "query": { "match": { "title": "蓝牙耳机" } }
}`;

export default function Console({ open, onToggle, prefill, onPrefillConsumed }: Props) {
  const [method, setMethod] = useState('POST');
  const [path, setPath] = useState('/eslab-products-v2/_search');
  const [body, setBody] = useState(DEFAULT_BODY);
  const [resp, setResp] = useState<ConsoleResponse | null>(null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [fields, setFields] = useState<FieldInfo[]>([]);

  useEffect(() => {
    if (!prefill) return;
    setMethod(prefill.method);
    setPath(prefill.path);
    setBody(prefill.body);
    setErr('');
    setResp(null);
    onPrefillConsumed();
  }, [prefill, onPrefillConsumed]);

  // 字段清单只在展开时取一次：收起状态下不占请求
  useEffect(() => {
    if (!open || fields.length > 0) return;
    getFields()
      .then((d) => setFields(d.v2))
      .catch(() => undefined);
  }, [open, fields.length]);

  const send = async () => {
    setBusy(true);
    setErr('');
    try {
      const r = await sendConsole(method, path, body);
      setResp(r);
    } catch (e) {
      setErr((e as Error).message);
      setResp(null);
    } finally {
      setBusy(false);
    }
  };

  /** 点字段或模板时，顺手把方法/路径对齐到 v2 的 _search，避免拼出跑不通的组合 */
  const applyBody = (next: unknown) => {
    setMethod('POST');
    setPath('/eslab-products-v2/_search');
    setBody(JSON.stringify(next, null, 2));
    setErr('');
  };

  return (
    <section className={`console${open ? ' open' : ''}`}>
      <div className="console-bar" onClick={onToggle}>
        <span className="title">DSL 控制台</span>
        <span className="hint">
          {open ? '改完语句按 ⌘/Ctrl + Enter 发送' : '随时手写 DSL 验证你的猜想 —— 只允许操作 eslab-* 索引'}
        </span>
        <span style={{ marginLeft: 'auto', fontSize: 12, color: 'var(--muted-2)' }}>{open ? '收起 ▾' : '展开 ▴'}</span>
      </div>
      {open && (
        <>
          <div className="console-tools">
            <span className="console-tools-label">模板</span>
            {TEMPLATES.map((t) => (
              <button
                key={t.name}
                className="console-chip"
                title={`填入一条 ${t.name} 查询`}
                onClick={() => applyBody(t.body)}
              >
                {t.name}
              </button>
            ))}
            <span className="console-tools-divider" />
            <span className="console-tools-label">v2 字段</span>
            <div className="console-fields">
              {fields.length === 0 && <span className="console-tools-empty">v2 还没建，字段列表为空</span>}
              {fields.map((f) => {
                const q = buildQuery(f);
                return (
                  <button
                    key={f.name}
                    className={`console-chip mono${q ? '' : ' off'}`}
                    disabled={!q}
                    title={q ? `${f.type} · ${f.role || '按类型生成一条查询'}` : `${f.type} · 这种类型不适合直接查`}
                    onClick={() => q && applyBody(q)}
                  >
                    {f.name}
                  </button>
                );
              })}
            </div>
          </div>
          <div className="console-body">
          <div className="console-input">
            <div className="console-line">
              <select value={method} onChange={(e) => setMethod(e.target.value)}>
                {['GET', 'POST', 'PUT', 'DELETE'].map((m) => (
                  <option key={m} value={m}>
                    {m}
                  </option>
                ))}
              </select>
              <input
                type="text"
                className="console-path"
                value={path}
                onChange={(e) => setPath(e.target.value)}
                spellCheck={false}
              />
              <button className="btn primary" onClick={send} disabled={busy}>
                {busy ? '发送中' : '发送'}
              </button>
            </div>

            <textarea
              value={body}
              onChange={(e) => setBody(e.target.value)}
              spellCheck={false}
              onKeyDown={(e) => {
                if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
                  e.preventDefault();
                  send();
                }
              }}
            />
          </div>
          <div className="console-out">
            {err && (
              <div className="callout error" style={{ marginBottom: 8 }}>
                <span className="tag">拒绝</span>
                <span>{err}</span>
              </div>
            )}
            {!err && !resp && <div style={{ color: 'var(--muted-2)', fontSize: 12.5 }}>响应会显示在这里。</div>}
            {resp && (
              <>
                <div className="console-meta">
                  <span>HTTP {resp.status}</span>
                  <span>{resp.ms ? `${resp.ms} ms` : '—'}</span>
                  <span>{resp.ok ? '成功' : '失败'}</span>
                  {resp.ok && (resp.data as { took?: number })?.took !== undefined && (
                    <span>took {(resp.data as { took: number }).took} ms</span>
                  )}
                </div>
                <pre>{JSON.stringify(resp.data ?? resp.error, null, 2)}</pre>
              </>
            )}
          </div>
          </div>
        </>
      )}
    </section>
  );
}
