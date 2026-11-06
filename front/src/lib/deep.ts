import type { DeepBlock } from './types';

export interface DeepStats {
  chars: number;
  sections: number;
  figures: number;
  minutes: number;
}

export interface TocItem {
  id: string;
  text: string;
}

/** 统计口径：叙述文本才算字数，代码块与图注不算 —— 代码不是阅读负担 */
function blockText(b: DeepBlock): string {
  switch (b.type) {
    case 'p':
    case 'h':
      return b.text;
    case 'ul':
    case 'ol':
      return b.items.join('');
    case 'callout':
      return b.text;
    case 'compare':
      return (
        b.left.title + b.left.text + (b.left.items ?? []).join('') +
        b.right.title + b.right.text + (b.right.items ?? []).join('')
      );
    case 'table':
      return b.columns.join('') + b.rows.map((r) => r.join('')).join('');
    default:
      return '';
  }
}

export function deepStats(blocks: DeepBlock[]): DeepStats {
  const chars = blocks.reduce((n, b) => n + blockText(b).replace(/\s/g, '').length, 0);
  const sections = blocks.filter((b) => b.type === 'h').length;
  const figures = blocks.filter((b) => b.type === 'figure').length;
  return { chars, sections, figures, minutes: Math.max(1, Math.round(chars / 350)) };
}

/** 目录条目：只收 h 块，锚点按出现顺序编号，与渲染器共用同一套规则 */
export function tocOf(blocks: DeepBlock[]): TocItem[] {
  const out: TocItem[] = [];
  for (const b of blocks) {
    if (b.type === 'h') out.push({ id: headingId(out.length), text: b.text });
  }
  return out;
}

export const headingId = (index: number) => `s-${index + 1}`;

export const fmtNum = (n: number) => n.toLocaleString('zh-CN');
