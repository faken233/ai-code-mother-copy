import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * 前端构建约定（平台固定，AI 不需要改）：
 *   base: './'        —— 资源用相对路径，挂到 /{accessCode}/ 下不用改代码
 *   root: 'client'    —— 源码目录
 *   outDir: ../public/dist —— Express 静态托管目录
 *   server.proxy      —— 本地开发时 /api 直接打到 node 服务（3000）
 */
export default defineConfig({
  root: 'client',
  base: './',
  plugins: [react()],
  build: {
    outDir: '../public/dist',
    emptyOutDir: true,
    sourcemap: false
  },
  server: {
    host: '0.0.0.0',
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:3000',
        changeOrigin: true
      }
    }
  }
});
