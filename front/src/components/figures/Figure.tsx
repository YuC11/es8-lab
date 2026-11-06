import { useId } from 'react';
import type { ReactNode } from 'react';

/** 与 styles.css 的配色保持一致，图上不再另立一套颜色 */
export const C = {
  canvas: '#f7f8fa',
  panel: '#ffffff',
  border: '#e5e7eb',
  text: '#111827',
  muted: '#6b7280',
  accent: '#2563eb',
  accentSoft: '#eff6ff',
  ok: '#059669',
  okSoft: '#ecfdf5',
  err: '#dc2626',
  errSoft: '#fef2f2',
  warn: '#b45309',
};

type Tone = 'plain' | 'accent' | 'ok' | 'err';

const toneOf = (tone: Tone) => {
  switch (tone) {
    case 'accent':
      return { stroke: C.accent, fill: C.accentSoft, text: C.text };
    case 'ok':
      return { stroke: C.ok, fill: C.okSoft, text: C.ok };
    case 'err':
      return { stroke: C.err, fill: C.errSoft, text: C.err };
    default:
      return { stroke: C.border, fill: C.panel, text: C.text };
  }
};

export function Frame({
  height,
  label,
  draw,
}: {
  height: number;
  label: string;
  draw: (arrow: string) => ReactNode;
}) {
  const raw = useId().replace(/[^a-zA-Z0-9]/g, '');
  const arrow = `arw${raw}`;
  return (
    <svg viewBox={`0 0 720 ${height}`} width="100%" role="img" aria-label={label} style={{ display: 'block' }}>
      <title>{label}</title>
      <defs>
        <marker
          id={arrow}
          viewBox="0 0 10 10"
          refX="8"
          refY="5"
          markerWidth="6"
          markerHeight="6"
          orient="auto-start-reverse"
        >
          <path
            d="M2 1L8 5L2 9"
            fill="none"
            stroke="context-stroke"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </marker>
      </defs>
      <rect x="0" y="0" width="720" height={height} rx="10" fill={C.canvas} />
      {draw(arrow)}
    </svg>
  );
}

export function Box({
  x,
  y,
  w = 120,
  h = 52,
  title,
  sub,
  tone = 'plain',
  dashed = false,
}: {
  x: number;
  y: number;
  w?: number;
  h?: number;
  title: string;
  sub?: string;
  tone?: Tone;
  dashed?: boolean;
}) {
  const c = toneOf(tone);
  return (
    <g>
      <rect
        x={x}
        y={y}
        width={w}
        height={h}
        rx="8"
        fill={c.fill}
        stroke={c.stroke}
        strokeWidth="1.2"
        strokeDasharray={dashed ? '4 3' : undefined}
      />
      <text
        x={x + w / 2}
        y={sub ? y + h / 2 - 7 : y + h / 2}
        textAnchor="middle"
        dominantBaseline="central"
        fontSize="13"
        fontWeight="500"
        fill={c.text}
      >
        {title}
      </text>
      {sub && (
        <text
          x={x + w / 2}
          y={y + h / 2 + 11}
          textAnchor="middle"
          dominantBaseline="central"
          fontSize="11"
          fill={C.muted}
        >
          {sub}
        </text>
      )}
    </g>
  );
}

export function Arrow({
  x1,
  y1,
  x2,
  y2,
  arrow,
  tone = 'muted',
  dashed = false,
  label,
  labelDy = -6,
}: {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  arrow: string;
  tone?: 'muted' | 'accent' | 'ok' | 'err';
  dashed?: boolean;
  label?: string;
  labelDy?: number;
}) {
  const color =
    tone === 'accent' ? C.accent : tone === 'ok' ? C.ok : tone === 'err' ? C.err : C.muted;
  return (
    <g>
      <line
        x1={x1}
        y1={y1}
        x2={x2}
        y2={y2}
        stroke={color}
        strokeWidth="1.4"
        strokeDasharray={dashed ? '4 3' : undefined}
        markerEnd={`url(#${arrow})`}
      />
      {label && (
        <text
          x={(x1 + x2) / 2}
          y={(y1 + y2) / 2 + labelDy}
          textAnchor="middle"
          fontSize="11"
          fill={C.muted}
        >
          {label}
        </text>
      )}
    </g>
  );
}

export function Label({
  x,
  y,
  text,
  anchor = 'start',
  size = 11.5,
  color = C.muted,
  weight = 400,
}: {
  x: number;
  y: number;
  text: string;
  anchor?: 'start' | 'middle' | 'end';
  size?: number;
  color?: string;
  weight?: number;
}) {
  return (
    <text x={x} y={y} textAnchor={anchor} fontSize={size} fill={color} fontWeight={weight}>
      {text}
    </text>
  );
}
