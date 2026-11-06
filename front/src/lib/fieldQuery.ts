import type { FieldInfo } from './types';

/**
 * 字段字典与查询模板的唯一来源。
 *
 * 速查页和底部控制台都从这里取，避免两处各维护一份字段示例值 ——
 * 改了这里，两个入口同时生效。
 */

/** 示例值：与种子数据的真实取值保持一致，点一下就能跑通 */
export const SAMPLE: Record<string, string | number> = {
  sku: 'SKU2026000001',
  title: '蓝牙耳机',
  description: '降噪',
  category: '数码影音',
  brand: '声阔',
  price: 100,
  stock: 10,
  status: 1,
  tags: '爆款',
  created_at: '2026-01-01',
  rating: 4.5,
  sales: 100,
  seller_id: 'S1001',
};

export const NUMERIC = [
  'integer',
  'long',
  'short',
  'byte',
  'float',
  'double',
  'scaled_float',
  'half_float',
];

/**
 * 按字段类型挑一种最合适的查询。
 * text → match（要分词）；keyword → term（整串）；数值/日期 → range。
 */
export function buildQuery(f: FieldInfo): unknown | null {
  const v = SAMPLE[f.name];
  if (v === undefined) return null;
  if (f.type === 'text') return { size: 3, query: { match: { [f.name]: v } } };
  if (f.type === 'keyword') return { size: 3, query: { term: { [f.name]: v } } };
  if (f.type === 'date') return { size: 3, query: { range: { [f.name]: { gte: 'now-30d' } } } };
  if (NUMERIC.includes(f.type)) return { size: 3, query: { range: { [f.name]: { gte: v } } } };
  return null;
}

/** 控制台顶部的四个起步模板：都基于 v2 的真实字段 */
export const TEMPLATES: Array<{ name: string; body: unknown }> = [
  {
    name: 'match',
    body: { size: 3, query: { match: { title: '蓝牙耳机' } } },
  },
  {
    name: 'term',
    body: { size: 3, query: { term: { category: '数码影音' } } },
  },
  {
    name: 'range',
    body: { size: 0, track_total_hits: true, query: { range: { price: { gte: 100, lte: 200 } } } },
  },
  {
    name: 'bool',
    body: {
      size: 3,
      query: {
        bool: {
          must: [{ match: { title: '蓝牙耳机' } }],
          filter: [{ range: { price: { gte: 100, lte: 500 } } }],
        },
      },
    },
  },
];
