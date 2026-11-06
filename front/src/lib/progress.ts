import { useEffect, useState } from 'react';

/**
 * 学习进度：存在 localStorage，不上传、不判分。
 *
 * 只记录「这一关跑过」这一件事 —— 跑没跑通由后端说了算（ok=false 时不会记录），
 * 所以它更像书签，而不是成绩单。
 */
const KEY = 'eslab.progress.v1';
const LAST = 'eslab.last.v1';

export type Progress = Record<string, number>;

export function readProgress(): Progress {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Progress) : {};
  } catch {
    return {};
  }
}

function emit() {
  window.dispatchEvent(new Event('eslab-progress'));
}

export function markDone(id: string) {
  const p = readProgress();
  p[id] = Date.now();
  localStorage.setItem(KEY, JSON.stringify(p));
  emit();
}

export function resetProgress() {
  localStorage.removeItem(KEY);
  localStorage.removeItem(LAST);
  emit();
}

export function setLast(id: string) {
  try {
    localStorage.setItem(LAST, id);
  } catch {
    /* 隐私模式下不可写，忽略 */
  }
}

export function getLast(): string | null {
  try {
    return localStorage.getItem(LAST);
  } catch {
    return null;
  }
}

export function useProgress(): Progress {
  const [p, setP] = useState<Progress>(readProgress);
  useEffect(() => {
    const h = () => setP(readProgress());
    window.addEventListener('eslab-progress', h);
    return () => window.removeEventListener('eslab-progress', h);
  }, []);
  return p;
}
