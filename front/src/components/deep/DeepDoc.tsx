import type { DeepBlock } from '../../lib/types';
import { headingId } from '../../lib/deep';
import Inline from './Inline';
import Figure from '../figures';

const TONES: Record<string, { cls: string; tag: string }> = {
  warn: { cls: 'pitfall', tag: '注意' },
  info: { cls: 'info', tag: '补充' },
  insight: { cls: 'insight', tag: '要点' },
};

/** 把块数组渲染成长文：只做 switch，不解析任何文本标记（行内 code 除外） */
export default function DeepDoc({ blocks }: { blocks: DeepBlock[] }) {
  let hIndex = 0;

  return (
    <div className="deep-doc">
      {blocks.map((b, i) => {
        switch (b.type) {
          case 'p':
            return (
              <p key={i}>
                <Inline text={b.text} />
              </p>
            );
          case 'h': {
            const id = headingId(hIndex);
            hIndex += 1;
            return (
              <h2 key={i} id={id}>
                <Inline text={b.text} />
              </h2>
            );
          }
          case 'ul':
            return (
              <ul key={i}>
                {b.items.map((t, j) => (
                  <li key={j}>
                    <Inline text={t} />
                  </li>
                ))}
              </ul>
            );
          case 'ol':
            return (
              <ol key={i}>
                {b.items.map((t, j) => (
                  <li key={j}>
                    <Inline text={t} />
                  </li>
                ))}
              </ol>
            );
          case 'code':
            return (
              <div className="deep-code" key={i}>
                <pre>
                  <code>{b.code}</code>
                </pre>
                {b.caption && (
                  <div className="deep-caption">
                    <Inline text={b.caption} />
                  </div>
                )}
              </div>
            );
          case 'table':
            return (
              <div className="deep-table" key={i}>
                <div className="table-wrap">
                  <table className="data-table">
                    <thead>
                      <tr>
                        {b.columns.map((c, j) => (
                          <th key={j}>{c}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {b.rows.map((r, ri) => (
                        <tr key={ri}>
                          {r.map((c, ci) => (
                            <td key={ci}>
                              <Inline text={c} />
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {b.caption && (
                  <div className="deep-caption">
                    <Inline text={b.caption} />
                  </div>
                )}
              </div>
            );
          case 'callout': {
            const tone = TONES[b.tone] ?? TONES.info;
            return (
              <div className={`callout ${tone.cls}`} key={i}>
                <span className="tag">{b.title || tone.tag}</span>
                <span>
                  <Inline text={b.text} />
                </span>
              </div>
            );
          }
          case 'compare':
            return (
              <div className="compare" key={i}>
                {[b.left, b.right].map((side, j) => (
                  <div className="compare-side" key={j}>
                    <div className="compare-title">{side.title}</div>
                    <div className="compare-text">
                      <Inline text={side.text} />
                    </div>
                    {side.items && side.items.length > 0 && (
                      <ul className="compare-items">
                        {side.items.map((t, k) => (
                          <li key={k}>
                            <Inline text={t} />
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                ))}
              </div>
            );
          case 'figure':
            return (
              <figure className="fig" key={i}>
                <Figure kind={b.kind} />
                <figcaption>
                  <Inline text={b.caption} />
                </figcaption>
              </figure>
            );
          default:
            return null;
        }
      })}
    </div>
  );
}
