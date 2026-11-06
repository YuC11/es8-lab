/** 前端与后端之间的类型契约 */
export interface Metric {
  label: string;
  value: string | number;
  hint?: string;
}

export interface TableData {
  columns: string[];
  rows: Array<Array<string | number>>;
}

export interface BarChartData {
  items: Array<{ label: string; value: number }>;
  unit?: string;
  note?: string;
}

/** 原理图的种类：与 components/figures 的注册表一一对应 */
export type FigureKind =
  | 'write-path'
  | 'refresh-timeline'
  | 'segment-merge'
  | 'inverted-index'
  | 'analyzer-pipeline'
  | 'bool-tree'
  | 'paging-window'
  | 'alias-swap'
  | 'reindex-pipeline'
  | 'template-resolution'
  | 'dynamic-template-branch'
  | 'object-flattened-nested'
  | 'ingest-pipeline'
  | 'concurrency-seqno'
  | 'relevance-stack'
  | 'suggest-funnel';

export interface CompareSide {
  title: string;
  text: string;
  items?: string[];
}

/**
 * 「深入原理」的内容块。
 *
 * 为什么不直接用 markdown：这层要能表达表格、左右对照和原理图，
 * 用字符串加解析器等于自己实现一个小语言；块数组是显式的，渲染器只做 switch。
 */
export type DeepBlock =
  | { type: 'p'; text: string }
  | { type: 'h'; text: string }
  | { type: 'ul'; items: string[] }
  | { type: 'ol'; items: string[] }
  | { type: 'code'; lang?: string; code: string; caption?: string }
  | { type: 'table'; columns: string[]; rows: string[][]; caption?: string }
  | { type: 'callout'; tone: 'warn' | 'info' | 'insight'; title?: string; text: string }
  | { type: 'compare'; left: CompareSide; right: CompareSide }
  | { type: 'figure'; kind: FigureKind; caption: string; alt?: string };

export interface LevelResult {
  summary: string;
  metrics?: Metric[];
  table?: TableData;
  chart?: BarChartData;
  json?: unknown;
  notes?: string[];
}

/**
 * 变体实验：一句「改什么」，一条可以立即执行的真实请求。
 *
 * 刻意不做对错判定 —— 这不是考试。跑完看 facts 里的观测值，
 * 再展开「会看到什么」对照原因即可。
 */
export interface Probe {
  /** 要做什么（祈使句，直接描述操作） */
  task: string;
  method?: string;
  path: string;
  body?: unknown;
  /** 该盯哪个指标 */
  watch: string;
  /** 跑完会看到的现象及原因 */
  expect: string;
}

export interface Step {
  label: string;
  desc: string;
  kind: 'level' | 'console';
  method?: string;
  path?: string;
  body?: unknown;
}

/** 本关需要的前置状态：ES 在线、某些索引存在且文档数达标 */
export interface PrereqIndex {
  index: string;
  minDocs?: number;
  /** 缺这个索引时该去哪一关补；缺省回退 m1-2（建 v2） */
  fixLevel?: string;
}

export interface Prereq {
  cluster?: boolean;
  indices?: PrereqIndex[];
  text?: string;
}

export interface Level {
  id: string;
  title: string;
  /** 这一关要回答的问题，一句话 */
  objective?: string;
  hook: string;
  /** 执行前的预测提示：只让人先想一想，不收集答案、不判分 */
  predict?: string;
  brief: string;
  /** 深入原理的正文块；阅读页渲染，关卡页只显示统计与入口 */
  deep: DeepBlock[];
  pitfall: string;
  steps: Step[];
  probes: Probe[];
  /** 跑完之后应该能说出口的一句话 */
  takeaway?: string;
  next?: string | null;
  prereq?: Prereq;
  after?: { indices: string[]; docs?: number };
}

export interface Module {
  id: string;
  name: string;
  goal: string;
  levels: Level[];
}

export interface Knowledge {
  scenario: {
    company: string;
    background: string;
    requirements: string[];
    constraint: string;
    dataPlan?: string;
  };
  modules: Module[];
}

export interface LabIndex {
  index: string;
  docs: number;
  storeBytes: number;
  segments: number;
  health: string;
}

export interface ExperimentBrief {
  id: string;
  title: string;
  question: string;
}

export interface Health {
  cluster: {
    up: boolean;
    version?: string;
    clusterName?: string;
    health?: string;
    nodes?: number;
    error?: string;
  };
  indices: LabIndex[];
  seedMeta: { count: number; seededAt: string; indexVersion: string; mode?: string } | null;
  esUrl: string;
  levels: string[];
  experiments: ExperimentBrief[];
}

/** 单个实验索引的实时状态（/api/data） */
export interface IndexState {
  index: string;
  exists: boolean;
  docs: number;
  shards: number;
  replicas?: number;
  storeBytes: number;
  segments: number;
  fields: number;
  error?: string;
}

/** 别名：前置检查要靠它判断 eslab-goods 这类「逻辑索引」是否可用 */
export interface AliasState {
  alias: string;
  index: string;
  exists: boolean;
  targets: string[];
  writeIndex: string;
  docs: number;
}

export interface DataState {
  target: number;
  full: number;
  v1: IndexState;
  v2: IndexState;
  /** 全部 eslab-* 索引；第 8-11 章的实验索引不在 v1/v2 里 */
  indices?: IndexState[];
  aliases?: AliasState[];
  seedMeta: Health['seedMeta'];
}

/** bulk 任务进度（/api/jobs/{id}） */
export interface JobView {
  id: string;
  kind: string;
  state: 'RUNNING' | 'DONE' | 'FAILED';
  written: number;
  total: number;
  rate: number;
  batchIndex: number;
  batches: number;
  ms: number;
  result?: {
    written: number;
    ms: number;
    rate: number;
    segments: number;
    storeBytes: number;
    docs: number;
  };
  error?: string;
}

export interface ExperimentSide {
  label: string;
  value: number;
  unit: string;
  samples: number[];
  detail?: string;
}

export interface ExperimentResult {
  id: string;
  title: string;
  question: string;
  a: ExperimentSide;
  b: ExperimentSide;
  delta: { speedup: number; improvementPct: number };
  conclusion: string;
  caveats: string[];
}

export interface SourceInfo {
  id: string;
  file: string;
  lines: number;
  focus: Array<{ start: number; end: number }>;
  code: string;
}

/** 字段字典：类型从真实 mapping 读，不是前端硬编码 */
export interface FieldInfo {
  name: string;
  type: string;
  searchable: boolean;
  aggregatable: boolean;
  hasKeyword: boolean;
  role: string;
}

export interface FieldDict {
  v1: FieldInfo[];
  v2: FieldInfo[];
}
