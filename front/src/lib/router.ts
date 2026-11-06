import { useEffect, useState } from 'react';

/**
 * 极简 hash 路由：不引第三方依赖，但页面可分享、可前进后退。
 *
 *   #/                  课程路线图
 *   #/level/m0-2        某一关
 *   #/deep/m0-2         该关的深入原理（长文阅读页）
 *   #/glossary          术语表
 *   #/dsl               查询速查
 *
 * 为什么用 hash 而不是 history API：本项目由 Spring Boot 直接托管静态文件，
 * 刷新 /level/m0-2 会打到后端并 404；hash 不会发给服务端，刷新永远能用。
 */
export type Route =
  | { page: 'roadmap' }
  | { page: 'level'; id: string }
  | { page: 'deep'; id: string }
  | { page: 'glossary' }
  | { page: 'dsl' };

function parse(hash: string): Route {
  const h = hash.replace(/^#\/?/, '');
  if (h.startsWith('level/')) return { page: 'level', id: h.slice('level/'.length) };
  if (h.startsWith('deep/')) return { page: 'deep', id: h.slice('deep/'.length) };
  if (h === 'glossary') return { page: 'glossary' };
  if (h === 'dsl') return { page: 'dsl' };
  return { page: 'roadmap' };
}

export function useRoute(): Route {
  const [route, setRoute] = useState<Route>(() => parse(window.location.hash));
  useEffect(() => {
    const onHash = () => setRoute(parse(window.location.hash));
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);
  return route;
}

export const go = (hash: string) => {
  window.location.hash = hash;
};

export const goHome = () => go('#/');
export const goLevel = (id: string) => go(`#/level/${id}`);
export const goDeep = (id: string) => go(`#/deep/${id}`);
export const goGlossary = () => go('#/glossary');
export const goDsl = () => go('#/dsl');
