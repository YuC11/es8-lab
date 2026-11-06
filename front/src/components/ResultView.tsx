import type { LevelResult } from '../lib/types';

export default function ResultView({ result }: { result: LevelResult }) {
  const max = Math.max(...(result.chart?.items.map((i) => i.value) ?? [1]), 1);

  return (
    <div>
      <div className="callout info" style={{ marginBottom: 14 }}>
        <span className="tag">结论</span>
        <span>{result.summary}</span>
      </div>

      {result.metrics && result.metrics.length > 0 && (
        <div className="metrics">
          {result.metrics.map((m) => (
            <div className="metric" key={m.label}>
              <div className="metric-label">{m.label}</div>
              <div className="metric-value">{m.value}</div>
              {m.hint && <div className="metric-hint">{m.hint}</div>}
            </div>
          ))}
        </div>
      )}

      {result.chart && result.chart.items.length > 0 && (
        <div style={{ marginBottom: 14 }}>
          <div className="chart">
            {result.chart.items.map((item, i) => (
              <div className="chart-row" key={`${item.label}-${i}`}>
                <div className="chart-label" title={item.label}>
                  {item.label}
                </div>
                <div className="chart-track">
                  <div
                    className={`chart-fill${i % 2 === 1 ? ' alt' : ''}`}
                    style={{ width: `${Math.max(1, (item.value / max) * 100)}%` }}
                  />
                </div>
                <div className="chart-value">{item.value}</div>
              </div>
            ))}
          </div>
          <div className="chart-note">
            {result.chart.unit ? `单位：${result.chart.unit}。` : ''}
            {result.chart.note ?? ''}
          </div>
        </div>
      )}

      {result.table && result.table.rows.length > 0 && (
        <div className="table-wrap" style={{ marginBottom: 14 }}>
          <table className="data-table">
            <thead>
              <tr>
                {result.table.columns.map((c) => (
                  <th key={c}>{c}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {result.table.rows.map((row, i) => (
                <tr key={i}>
                  {row.map((cell, j) => (
                    <td key={j}>{String(cell)}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {result.notes && result.notes.length > 0 && (
        <ul className="notes" style={{ marginBottom: 10 }}>
          {result.notes.filter(Boolean).map((n, i) => (
            <li key={i}>{n}</li>
          ))}
        </ul>
      )}

      {result.json !== undefined && result.json !== null && (
        <details className="raw">
          <summary>查看原始 ES 响应 / 明细数据</summary>
          <pre className="json">{JSON.stringify(result.json, null, 2)}</pre>
        </details>
      )}
    </div>
  );
}
