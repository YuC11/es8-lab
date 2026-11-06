import type { DataState, Health, IndexState, Prereq } from '../lib/types';
import { go } from '../lib/router';

export interface Readiness {
  ok: boolean;
  problems: string[];
  fix: { label: string; hash: string } | null;
}

/**
 * 找一个「索引或别名」的当前状态。
 *
 * 第 8-11 章会用到 v1/v2 之外的实验索引，m9-6 还要求 eslab-goods 这个别名可用 ——
 * 所以先查 indices，再查 aliases，最后回退到 v1/v2。
 */
function stateOf(data: DataState | null, index: string): IndexState | null {
  if (!data) return null;
  const direct = data.indices?.find((i) => i.index === index);
  if (direct) return direct;
  const alias = data.aliases?.find((a) => a.alias === index);
  if (alias) {
    return {
      index,
      exists: (alias.targets?.length ?? 0) > 0,
      docs: alias.docs ?? 0,
      shards: 0,
      storeBytes: 0,
      segments: 0,
      fields: 0,
    };
  }
  if (data.v1?.index === index) return data.v1;
  if (data.v2?.index === index) return data.v2;
  return null;
}

/**
 * 前置条件检查：把「先跑 seed」这种命令行提示，换成页面里能看懂的缺口清单。
 *
 * 检查三件事：ES 在不在、索引建没建、文档数量够不够。
 * 任何一项不满足，都在关卡顶部说明缺什么、以及点哪里补。
 */
export function checkPrereq(
  prereq: Prereq | undefined,
  health: Health | null,
  data: DataState | null,
): Readiness {
  const problems: string[] = [];
  let fix: Readiness['fix'] = null;

  if (!health) return { ok: false, problems: ['正在读取环境状态…'], fix };

  if (prereq?.cluster !== false && !health.cluster.up) {
    problems.push('Elasticsearch 未连接（' + health.esUrl + '）');
    return { ok: false, problems, fix: null };
  }

  for (const req of prereq?.indices ?? []) {
    const st = stateOf(data, req.index);
    const lv = req.fixLevel ?? 'm1-2';
    if (!st || !st.exists) {
      problems.push(`${req.index} 还不存在`);
      fix = { label: `去 ${lv} 补上`, hash: `#/level/${lv}` };
      continue;
    }
    const min = req.minDocs ?? 0;
    if (st.docs < min) {
      problems.push(`${req.index} 只有 ${st.docs.toLocaleString()} 条，需要 ${min.toLocaleString()} 条`);
      fix = { label: `去 ${lv} 补数据`, hash: `#/level/${lv}` };
    }
  }

  if (!fix && problems.length > 0) fix = { label: '回到第 0 章', hash: '#/level/m0-1' };

  return { ok: problems.length === 0, problems, fix };
}

export default function PrereqBanner({
  prereq,
  health,
  data,
}: {
  prereq?: Prereq;
  health: Health | null;
  data: DataState | null;
}) {
  const r = checkPrereq(prereq, health, data);
  const creates = prereq?.creates ?? [];
  const noIndices = (prereq?.indices ?? []).length === 0;

  if (r.ok) {
    // 完全没有索引前置、但本关会自己建东西：说明一句，省得学员到处找「要先建什么」
    if (creates.length === 0 || !noIndices) return null;
    return (
      <div className="callout info prereq">
        <span className="tag">本关自建</span>
        <div style={{ flex: 1 }}>
          <div>{prereq?.text ?? '这一关不需要提前准备数据'}</div>
          <div className="prereq-actions">
            {creates.map((c) => (
              <code className="prereq-create" key={c}>
                {c}
              </code>
            ))}
            <span className="prereq-hint">运行本关时自动创建或重建，不用手动准备</span>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="callout error prereq">
      <span className="tag">前置未满足</span>
      <div style={{ flex: 1 }}>
        <div>{prereq?.text ?? '这一关还缺一些前置条件'}</div>
        <ul className="prereq-list">
          {r.problems.map((p) => (
            <li key={p}>{p}</li>
          ))}
        </ul>
        <div className="prereq-actions">
          {r.fix ? (
            <button className="btn small" onClick={() => go(r.fix!.hash)}>
              {r.fix.label}
            </button>
          ) : (
            <span className="prereq-hint">先在另一个终端启动 Elasticsearch，再刷新本页。</span>
          )}
        </div>
      </div>
    </div>
  );
}
