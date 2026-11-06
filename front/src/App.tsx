import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { getData, getHealth, getKnowledge } from './lib/api';
import type { DataState, Health, Knowledge } from './lib/types';
import { goLevel, useRoute } from './lib/router';
import { useProgress } from './lib/progress';
import Sidebar from './components/Sidebar';
import Console, { type Prefill } from './components/Console';
import Roadmap from './pages/Roadmap';
import LevelPage from './pages/LevelPage';
import DeepPage from './pages/DeepPage';
import Glossary from './pages/Glossary';
import DslRef from './pages/DslRef';

export default function App() {
  const [knowledge, setKnowledge] = useState<Knowledge | null>(null);
  const [health, setHealth] = useState<Health | null>(null);
  const [data, setData] = useState<DataState | null>(null);
  const [fatal, setFatal] = useState('');
  const [consoleOpen, setConsoleOpen] = useState(false);
  const [prefill, setPrefill] = useState<Prefill | null>(null);
  const route = useRoute();
  const progress = useProgress();
  const mainRef = useRef<HTMLDivElement>(null);

  const refresh = useCallback(() => {
    getHealth().then(setHealth).catch(() => undefined);
    getData().then(setData).catch(() => undefined);
  }, []);

  useEffect(() => {
    Promise.all([getKnowledge(), getHealth(), getData()])
      .then(([k, h, d]) => {
        setKnowledge(k);
        setHealth(h);
        setData(d);
      })
      .catch((e: Error) => setFatal(e.message));
  }, []);

  useEffect(() => {
    mainRef.current?.scrollTo({ top: 0 });
  }, [route]);

  const found = useMemo(() => {
    if (!knowledge || (route.page !== 'level' && route.page !== 'deep')) return null;
    const flat = knowledge.modules.flatMap((m) => m.levels.map((l) => ({ m, l })));
    const idx = flat.findIndex((x) => x.l.id === route.id);
    if (idx < 0) return null;
    const cur = flat[idx];
    const indexInModule = cur.m.levels.findIndex((l) => l.id === cur.l.id);
    const nxt = flat[idx + 1];
    return {
      module: cur.m,
      level: cur.l,
      indexInModule,
      next: nxt ? { id: nxt.l.id, title: nxt.l.title } : null,
    };
  }, [knowledge, route]);

  const openConsole = useCallback((method: string, path: string, body?: unknown) => {
    setPrefill({ method, path, body: body ? JSON.stringify(body, null, 2) : '' });
    setConsoleOpen(true);
  }, []);

  if (fatal) {
    return (
      <div className="empty">
        无法连接后端：{fatal}
        <br />
        请确认 <span className="kbd">sh sh/backend.sh</span> 已启动，且 Elasticsearch 在 9200 端口运行。
      </div>
    );
  }

  if (!knowledge) return <div className="empty">加载中…</div>;

  const activeId =
    (route.page === 'level' || route.page === 'deep') && found ? found.level.id : null;

  return (
    <div className={`app${consoleOpen ? ' console-open' : ''}`}>
      <Sidebar
        knowledge={knowledge}
        health={health}
        data={data}
        progress={progress}
        activeId={activeId}
        onSelect={goLevel}
      />

      <div className="main" ref={mainRef}>
        <div className={`content${route.page === 'deep' ? ' content-wide' : ''}`}>
          {health && !health.cluster.up && (
            <div className="callout error">
              <span className="tag">ES 离线</span>
              <span>
                无法连接 {health.esUrl}：{health.cluster.error}
                <br />
                请先启动 Elasticsearch（见 README 第 1 步），然后刷新本页。
              </span>
            </div>
          )}

          {route.page === 'roadmap' && (
            <Roadmap
              knowledge={knowledge}
              health={health}
              data={data}
              progress={progress}
              onDataChange={refresh}
            />
          )}

          {route.page === 'glossary' && <Glossary />}

          {route.page === 'dsl' && <DslRef onOpenConsole={openConsole} />}

          {route.page === 'level' &&
            (found ? (
              <LevelPage
                key={found.level.id}
                module={found.module}
                level={found.level}
                indexInModule={found.indexInModule}
                health={health}
                data={data}
                onOpenConsole={openConsole}
                onDataChange={refresh}
              />
            ) : (
              <div className="empty">
                找不到关卡 {route.id}。回路线图看看有哪些关。
              </div>
            ))}

          {route.page === 'deep' &&
            (found ? (
              <DeepPage
                key={`deep-${found.level.id}`}
                module={found.module}
                level={found.level}
                next={found.next}
              />
            ) : (
              <div className="empty">找不到关卡 {route.id} 的深入原理。</div>
            ))}
        </div>
      </div>

      <Console
        open={consoleOpen}
        onToggle={() => setConsoleOpen((v) => !v)}
        prefill={prefill}
        onPrefillConsumed={() => setPrefill(null)}
      />
    </div>
  );
}
