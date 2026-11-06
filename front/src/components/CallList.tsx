import { useState } from 'react';
import { isReplayable, type CallSpec } from '../lib/levelCalls';

interface Props {
  calls: CallSpec[];
  onOpenConsole: (method: string, path: string, body?: unknown) => void;
  /** 折叠状态下先露几条 */
  preview?: number;
}

/**
 * 「运行本关」到底跑什么 —— 把后端的请求序列摊开在按钮下面。
 *
 * 不想让它变成第二个需要阅读的长文档：默认只露 4 条，其余折叠；
 * 每条一行，左边方法、右边路径，说明压在第二行小字里。
 */
export default function CallList({ calls, onOpenConsole, preview = 4 }: Props) {
  const [expanded, setExpanded] = useState(false);
  if (calls.length === 0) return null;

  const shown = expanded ? calls : calls.slice(0, preview);

  return (
    <div className="calls">
      <div className="calls-head">
        <span className="calls-title">这一关会发出的请求</span>
        <span className="calls-count">
          {calls.length} 条 · 按顺序{!expanded && calls.length > preview ? `，先露 ${preview} 条` : ''}
        </span>
        {calls.length > preview && (
          <button className="btn-link calls-toggle" onClick={() => setExpanded((v) => !v)}>
            {expanded ? '收起' : `展开全部 ${calls.length} 条`}
          </button>
        )}
      </div>
      <ol className="calls-list">
        {shown.map((c, i) => (
          <li className="call" key={`${c.method}-${c.path}-${i}`}>
            <span className={`method m-${c.method.toLowerCase()}`}>{c.method}</span>
            <div className="call-main">
              <div className="call-line">
                <code className="call-path" title={c.path}>
                  {c.path}
                </code>
                {c.repeat && <span className="call-repeat">{c.repeat}</span>}
                {isReplayable(c) && (
                  <button
                    className="btn-link call-try"
                    onClick={() => onOpenConsole(c.method, c.path, c.body)}
                    title="把这条请求填进底部控制台"
                  >
                    <span className="try-full">在控制台执行</span>
                    <span className="try-mini">执行</span>
                  </button>
                )}
              </div>
              <div className="call-note">
                {c.dsl && (
                  <>
                    <code className="call-dsl">{c.dsl}</code>{' '}
                  </>
                )}
                {c.note}
                {c.cond && <span className="call-cond"> · {c.cond}</span>}
              </div>
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}
