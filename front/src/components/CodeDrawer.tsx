import { useEffect, useState } from 'react';
import { getSource } from '../lib/api';
import type { SourceInfo } from '../lib/types';

export default function CodeDrawer({ levelId, onClose }: { levelId: string; onClose: () => void }) {
  const [info, setInfo] = useState<SourceInfo | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setInfo(null);
    setError('');
    getSource(levelId)
      .then((d) => {
        if (!cancelled) setInfo(d);
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e.message);
      });
    return () => {
      cancelled = true;
    };
  }, [levelId]);

  const inFocus = (n: number) => !!info?.focus.some((f) => n >= f.start && n <= f.end);

  return (
    <>
      <div className="drawer-mask" onClick={onClose} />
      <aside className="drawer">
        <div className="drawer-head">
          <div>
            <div className="drawer-title">本关的后端实现</div>
            <div className="drawer-sub">{info?.file ?? `backend/src/main/java/com/eslab/levels/${levelId}.java`}</div>
          </div>
          <button className="btn small" onClick={onClose}>
            关闭
          </button>
        </div>
        <div className="drawer-body">
          {error && (
            <div className="callout error" style={{ margin: '0 18px' }}>
              <span className="tag">错误</span>
              <span>{error}</span>
            </div>
          )}
          {!info && !error && <div className="empty">读取源码中…</div>}
          {info &&
            info.code.split('\n').map((line, i) => {
              const n = i + 1;
              return (
                <div className={`code-row${inFocus(n) ? ' focus' : ''}`} key={n}>
                  <span className="ln">{n}</span>
                  <span className="src">{line || ' '}</span>
                </div>
              );
            })}
        </div>
        <div className="sidebar-foot" style={{ borderTop: '1px solid var(--border)' }}>
          高亮行就是本关真正执行的那段请求：页面上的表格与数字，都来自这里的代码。
        </div>
      </aside>
    </>
  );
}
