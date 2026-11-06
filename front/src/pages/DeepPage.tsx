import { useEffect, useMemo, useRef, useState } from 'react';
import type { Level, Module } from '../lib/types';
import { deepStats, fmtNum, tocOf } from '../lib/deep';
import { goDeep, goLevel } from '../lib/router';
import DeepDoc from '../components/deep/DeepDoc';

interface Props {
  module: Module;
  level: Level;
  next: { id: string; title: string } | null;
}

/**
 * 深入原理的独立阅读页。
 *
 * 为什么不在关卡页里展开：一篇文章 1500-2000 字 + 两张图，展开会把
 * 「先猜 → 动手 → 结果 → 所以呢」整套流程推下三四屏，读和做互相打架。
 *
 * 滚动高亮不用 IntersectionObserver：滚动容器是 .main 而不是 window，
 * 且图是渲染后才有高度的，IO 的 rootMargin 在布局变化时会漏判。
 */
export default function DeepPage({ module, level, next }: Props) {
  const blocks = level.deep ?? [];
  const stats = useMemo(() => deepStats(blocks), [blocks]);
  const toc = useMemo(() => tocOf(blocks), [blocks]);
  const rootRef = useRef<HTMLDivElement>(null);
  const [active, setActive] = useState(0);
  const [pct, setPct] = useState(0);

  useEffect(() => {
    const scroller = rootRef.current?.closest('.main') as HTMLElement | null;
    if (!scroller) return;
    let raf = 0;

    const update = () => {
      raf = 0;
      const max = Math.max(1, scroller.scrollHeight - scroller.clientHeight);
      setPct(Math.min(1, Math.max(0, scroller.scrollTop / max)));
      const heads = rootRef.current?.querySelectorAll<HTMLElement>('.deep-doc h2');
      if (!heads || heads.length === 0) return;
      let idx = 0;
      heads.forEach((el, i) => {
        if (el.getBoundingClientRect().top <= 96) idx = i;
      });
      // 文章不够长时最后一节永远到不了顶部，滚到底就直接高亮它
      const atBottom = scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - 8;
      setActive(atBottom ? heads.length - 1 : idx);
    };

    const onScroll = () => {
      if (!raf) raf = requestAnimationFrame(update);
    };

    update();
    scroller.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    return () => {
      scroller.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
      if (raf) cancelAnimationFrame(raf);
    };
  }, [level.id]);

  const jump = (id: string) => {
    const el = document.getElementById(id);
    if (!el) return;
    el.scrollIntoView({ block: 'start' });
    const scroller = rootRef.current?.closest('.main') as HTMLElement | null;
    scroller?.scrollBy({ top: -20 });
  };

  return (
    <div className="deep-page" ref={rootRef}>
      <div className="deep-progress">
        <i style={{ width: `${pct * 100}%` }} />
      </div>

      <div className="deep-head">
        <button className="btn-link" onClick={() => goLevel(level.id)}>
          ← 回到 {level.id}
        </button>
        <div className="deep-crumb">
          {module.name} · {level.title}
        </div>
        <h1>深入原理：{level.title}</h1>
        <div className="deep-meta">
          {fmtNum(stats.chars)} 字 · {stats.sections} 节 · {stats.figures} 张原理图 · 约 {stats.minutes} 分钟
        </div>
      </div>

      {blocks.length === 0 ? (
        <div className="callout info">
          <span className="tag">整理中</span>
          <span>这一关的深入原理还在整理，先回关卡把实验跑一遍。</span>
        </div>
      ) : (
        <div className={`deep-layout${toc.length === 0 ? ' single' : ''}`}>
          {toc.length > 0 && (
            <nav className="deep-toc" aria-label="本页目录">
              <div className="deep-toc-title">本页目录</div>
              {toc.map((t, i) => (
                <button
                  key={t.id}
                  className={`deep-toc-item${i === active ? ' active' : ''}`}
                  onClick={() => jump(t.id)}
                >
                  {t.text}
                </button>
              ))}
            </nav>
          )}
          <article className="deep-article">
            <DeepDoc blocks={blocks} />
          </article>
        </div>
      )}

      <div className="deep-foot">
        <button className="btn small" onClick={() => goLevel(level.id)}>
          回到本关动手
        </button>
        {next && (
          <button className="btn small primary" onClick={() => goDeep(next.id)}>
            下一关的深入原理：{next.title} →
          </button>
        )}
      </div>
    </div>
  );
}
