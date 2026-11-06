import { Fragment } from 'react';

/**
 * 行内标记只支持两种：反引号包住的代码，双星号包住的加粗。
 *
 * 不做 markdown 子集：切分成本低、没有 XSS 面（React 文本节点自动转义），
 * 也顺手修掉了原先把反引号原样显示在页面上的问题。
 */
const TOKEN = /(`[^`]+`|\*\*[^*]+\*\*)/g;

export default function Inline({ text }: { text: string }) {
  const parts = text.split(TOKEN);
  return (
    <>
      {parts.map((p, i) => {
        if (p.length > 2 && p.startsWith('`') && p.endsWith('`')) {
          return <code key={i}>{p.slice(1, -1)}</code>;
        }
        if (p.length > 4 && p.startsWith('**') && p.endsWith('**')) {
          return (
            <strong key={i}>
              {p.slice(2, -2)}
            </strong>
          );
        }
        return <Fragment key={i}>{p}</Fragment>;
      })}
    </>
  );
}
