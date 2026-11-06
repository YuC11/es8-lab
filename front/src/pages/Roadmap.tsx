import { useEffect, useMemo, useRef, useState } from 'react';
import type { DataState, Health, Knowledge } from '../lib/types';
import { goLevel } from '../lib/router';
import { getLast, resetProgress } from '../lib/progress';
import DataPanel from '../components/DataPanel';
import LabIndexPanel from '../components/LabIndexPanel';

interface Props {
  knowledge: Knowledge;
  health: Health | null;
  data: DataState | null;
  progress: Record<string, number>;
  onDataChange: () => void;
}

/**
 * 重置学习进度：两步确认，不做 modal。
 *
 * 只清 localStorage 里的完成记录与「上次浏览」，不动索引、不动数据 ——
 * 这是两件完全不同的事：想重来一遍课程，用这个；想把数据重新灌一遍，用上面的数据工坊。
 */
function ResetProgress({ doneCount, hasAny }: { doneCount: number; hasAny: boolean }) {
  const [asking, setAsking] = useState(false);
  const timer = useRef<number | null>(null);

  // 举起确认状态后 5 秒没动就自动收回，避免一直挂在那里
  useEffect(() => {
    if (!asking) return;
    timer.current = window.setTimeout(() => setAsking(false), 5000);
    return () => {
      if (timer.current) window.clearTimeout(timer.current);
    };
  }, [asking]);

  if (!hasAny) {
    return (
      <span className="roadmap-reset">
        <button className="btn small ghost" disabled title="还没有学习记录">
          重置学习进度
        </button>
      </span>
    );
  }

  if (!asking) {
    return (
      <span className="roadmap-reset">
        <button
          className="btn small ghost"
          onClick={() => setAsking(true)}
          title="只清空本机浏览器里的学习记录，不会动索引和数据"
        >
          重置学习进度
        </button>
      </span>
    );
  }

  return (
    <span className="roadmap-reset asking">
      <span className="reset-ask">
        {doneCount > 0 ? `清空 ${doneCount} 关的完成记录？` : '清空「上次浏览」记录？'}
      </span>
      <button
        className="btn small danger"
        onClick={() => {
          resetProgress();
          setAsking(false);
        }}
      >
        确定重置
      </button>
      <button className="btn small ghost" onClick={() => setAsking(false)}>
        取消
      </button>
    </span>
  );
}

export default function Roadmap({ knowledge, health, data, progress, onDataChange }: Props) {
  const allLevels = useMemo(
    () => knowledge.modules.flatMap((m) => m.levels.map((l) => ({ ...l, module: m }))),
    [knowledge],
  );
  const doneCount = allLevels.filter((l) => progress[l.id]).length;
  const probeCount = allLevels.reduce((n, l) => n + (l.probes?.length ?? 0), 0);
  const expCount = health?.experiments?.length ?? 0;
  const last = getLast();
  const lastLevel = allLevels.find((l) => l.id === last);
  // 主按钮永远指向「第一关没完成的」：顺序学习者不该被上次浏览记录带去后面的章节
  const nextLevel = allLevels.find((l) => !progress[l.id]) ?? allLevels[0];
  const resume = lastLevel && lastLevel.id !== nextLevel.id ? lastLevel : null;
  const v1Ready = !!data?.v1?.exists && data.v1.docs > 0;

  const target = nextLevel;

  return (
    <div className="roadmap">
      <div className="roadmap-hero">
        <div className="crumb">课程路线图 · {allLevels.length} 关</div>
        <h1>从一个空集群，到一个能支撑商品搜索的索引</h1>
        <p className="hook">
          {knowledge.scenario.company}：{knowledge.scenario.background}
        </p>
        <p className="prose roadmap-plan">
          {knowledge.scenario.dataPlan ??
            '数据不是预先塞给你的：第 0 章会带你从空集群开始，亲手建索引、写文档、灌到 2 万条。'}
        </p>
        <div className="roadmap-cta">
          <button className="btn primary" onClick={() => goLevel(target.id)}>
            {doneCount === 0 ? '从第一关开始' : `继续 ${target.id}：${target.title}`}
          </button>
          {resume && (
            <button className="btn-link" onClick={() => goLevel(resume.id)}>
              回到上次：{resume.id}
            </button>
          )}
          <span className="roadmap-progress">
            已完成 {doneCount} / {allLevels.length} 关
            {!v1Ready && ' · 数据还没建，建议先走第 0 章'}
          </span>
          <ResetProgress doneCount={doneCount} hasAny={doneCount > 0 || !!lastLevel} />
        </div>
      </div>

      <DataPanel data={data} health={health} onChange={onDataChange} />

      <LabIndexPanel />

      <div className="roadmap-chapters">
        {knowledge.modules.map((m) => {
          const done = m.levels.filter((l) => progress[l.id]).length;
          const pct = Math.round((done / m.levels.length) * 100);
          const firstUndone = m.levels.find((l) => !progress[l.id]) ?? m.levels[0];
          return (
            <div className="chapter" key={m.id}>
              <div className="chapter-head">
                <div className="chapter-num">{m.id.replace('m', '')}</div>
                <div className="chapter-title">
                  <div className="chapter-name">
                    {m.name}
                    <span className="chapter-count">
                      {done}/{m.levels.length} 关
                    </span>
                  </div>
                  <div className="chapter-goal">{m.goal}</div>
                </div>
                <button className="btn small" onClick={() => goLevel(firstUndone.id)}>
                  {done === 0 ? '开始' : done === m.levels.length ? '重看' : '继续'}
                </button>
              </div>
              <div className="chapter-bar">
                <div className="chapter-fill" style={{ width: `${pct}%` }} />
              </div>
              <div className="chapter-levels">
                {m.levels.map((l) => (
                  <div
                    key={l.id}
                    className={`chapter-level${progress[l.id] ? ' done' : ''}`}
                    onClick={() => goLevel(l.id)}
                  >
                    <span className="chapter-level-id">{l.id}</span>
                    <span className="chapter-level-title">{l.title}</span>
                    <span className="chapter-level-mark">{progress[l.id] ? '已完成' : '待学'}</span>
                  </div>
                ))}
              </div>
            </div>
          );
        })}
      </div>

      <div className="roadmap-foot">
        课程 {allLevels.length} 关 + {expCount} 个 A/B 实验 + {probeCount} 条变体实验。每关末尾的「变体实验」都是可一键执行的真实请求，
        没有选择题、不判分、不记录作答。学习进度只存在你自己的浏览器里，随时可以重置。
      </div>
    </div>
  );
}
