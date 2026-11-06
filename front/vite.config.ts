import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 前端是 front/ 下的独立项目：根目录就是 front/，产物输出到 front/dist。
// 后端（backend/）只读这个 dist，不参与前端构建。
// 开发模式下 /api 代理到本地后端 8787；Vite 的服务端代理是进程内直连，
// 不经过系统 HTTP_PROXY，本地联调不受代理干扰。
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8787',
        changeOrigin: false,
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
