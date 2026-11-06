import type { DataState, Health, Knowledge } from '../lib/types';
import { goDsl, goGlossary, goHome } from '../lib/router';
import { checkPrereq } from './Prereq';

interface Props {
  knowledge: Knowledge;
  health: Health | null;
  data: DataState | null;
  progress: Record<string, number>;
  activeId: string | null;
  onSelect: (id: string) => void;
}

export default function Sidebar({ knowledge, health, data, progress, activeId, onSelect }: Props) {
  const up = health?.cluster.up;
  const healthColor = !up ? 'err' : health?.cluster.health === 'green' ? 'ok' : 'warn';
  const total = knowledge.modules.reduce((n, m) => n + m.levels.length, 0);
  const done = knowledge.modules.reduce(
    (n, m) => n + m.levels.filter((l) => progress[l.id]).length,
    0,
  );

  return (
    <aside className="sidebar">
      <div className="sidebar-head">
        <div className="brand" onClick={goHome} role="button" title="回路线图">
          <span className="brand-name">ES Lab</span>
          <span className="brand-sub">8.x · 商品搜索场景</span>
        </div>
        <div className="status">
          <span className={`dot ${healthColor}`} />
          {up ? (
            <>
              <span>
                {health?.cluster.version} · {health?.cluster.health}
              </span>
              <span className="status-sep">|</span>
              <span>
                {done}/{total} 关
              </span>
            </>
          ) : (
            <span>ES 未连接</span>
          )}
        </div>
        <div className="sidebar-progress">
          <div className="sidebar-progress-fill" style={{ width: `${(done / total) * 100}%` }} />
        </div>
      </div>

      <div className="sidebar-scroll">
        {knowledge.modules.map((m) => {
          const mdone = m.levels.filter((l) => progress[l.id]).length;
          return (
            <div className="module" key={m.id}>
              <div className="module-head">
                <span>{m.name}</span>
                <span>
                  {mdone}/{m.levels.length}
                </span>
              </div>
              {m.levels.map((l) => {
                const ready = checkPrereq(l.prereq, health, data).ok;
                return (
                  <div
                    key={l.id}
                    className={`level-item${l.id === activeId ? ' active' : ''}${
                      progress[l.id] ? ' done' : ''
                    }${ready ? '' : ' locked'}`}
                    onClick={() => onSelect(l.id)}
                    title={ready ? l.hook : `${l.prereq?.text ?? '前置条件未满足'}`}
                  >
                    <span className="level-id">{l.id.replace('m', '').replace('-', '.')}</span>
                    <span className="level-title">{l.title}</span>
                    {progress[l.id] && <span className="level-mark">✓</span>}
                    {!ready && !progress[l.id] && <span className="level-lock">!</span>}
                  </div>
                );
              })}
            </div>
          );
        })}
      </div>

      <div className="sidebar-foot">
        <button className="btn-link" onClick={goGlossary}>
          术语表
        </button>
        <span className="sidebar-sep">·</span>
        <button className="btn-link" onClick={goDsl}>
          查询速查
        </button>
        <span className="sidebar-sep">·</span>
        <span>仅本机 · 只操作 eslab-* 前缀</span>
      </div>
    </aside>
  );
}
