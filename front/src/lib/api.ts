import type {
  DataState,
  ExperimentResult,
  FieldDict,
  Health,
  IndexState,
  JobView,
  Knowledge,
  LevelResult,
  SourceInfo,
} from './types';

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { 'content-type': 'application/json', ...(init?.headers ?? {}) },
  });
  const text = await res.text();
  let data: unknown = text;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    /* 保留纯文本 */
  }
  if (!res.ok) {
    const msg =
      typeof data === 'object' && data && 'error' in data
        ? String((data as { error: unknown }).error)
        : `HTTP ${res.status}`;
    throw new Error(msg);
  }
  return data as T;
}

export const getHealth = () => request<Health>('/api/health');
export const getFields = () => request<FieldDict>('/api/data/fields');
export const getKnowledge = () => request<Knowledge>('/api/knowledge');

export interface LevelRunResponse {
  ok: boolean;
  id: string;
  result?: LevelResult;
  error?: string;
  hint?: string;
  elapsedMs: number;
}

export const runLevel = (id: string) =>
  request<LevelRunResponse>(`/api/levels/${id}/run`, { method: 'POST', body: '{}' });

export const getSource = (id: string) => request<SourceInfo>(`/api/levels/${id}/source`);

export const runExperiment = (id: string) =>
  request<{ ok: boolean; result?: ExperimentResult; error?: string; elapsedMs: number }>(
    `/api/experiments/${id}/run`,
    { method: 'POST', body: '{}' },
  );

export interface ConsoleResponse {
  ok: boolean;
  ms: number;
  status: number;
  data?: unknown;
  error?: string;
}

export const sendConsole = (method: string, path: string, body: string) =>
  request<ConsoleResponse>('/api/console', {
    method: 'POST',
    body: JSON.stringify({
      method,
      path,
      body: body.trim() ? JSON.parse(body) : undefined,
    }),
  });

export interface ProbeResponse {
  ok: boolean;
  status: number;
  ms: number;
  /** 后端把原始响应归纳成几条「该看的数」 */
  summary?: { facts?: string[] };
  data?: unknown;
  error?: string;
}

/** 变体实验：执行一条真实请求，返回摘要指标 */
export const runProbe = (probe: { method?: string; path: string; body?: unknown }) =>
  request<ProbeResponse>('/api/probe', {
    method: 'POST',
    body: JSON.stringify({
      method: probe.method ?? 'POST',
      path: probe.path,
      body: probe.body,
    }),
  });

// ── 数据工坊：建索引 / 灌数据 / 查任务进度 ────────────────

export const getData = () => request<DataState>('/api/data');

export const createIndexApi = (version: 'v1' | 'v2') =>
  request<{ ok: boolean; index?: IndexState; error?: string }>(`/api/data/${version}/create`, {
    method: 'POST',
    body: '{}',
  });

export const dropIndexApi = (version: 'v1' | 'v2') =>
  request<{ ok: boolean; error?: string }>(`/api/data/${version}`, { method: 'DELETE' });

export interface BulkStart {
  ok: boolean;
  jobId?: string;
  total?: number;
  startFrom?: number;
  skipped?: boolean;
  docs?: number;
  message?: string;
  error?: string;
}

export const startBulk = (version: 'v1' | 'v2', count: number) =>
  request<BulkStart>(`/api/data/${version}/bulk`, {
    method: 'POST',
    body: JSON.stringify({ count }),
  });

export const getJob = (id: string) => request<JobView>(`/api/jobs/${id}`);
