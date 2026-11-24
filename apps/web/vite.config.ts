/// <reference types="vitest/config" />

import { fileURLToPath, URL } from 'node:url';

import vue from '@vitejs/plugin-vue';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');

  return {
    plugins: [vue()],
    worker: {
      // 文件读取 Worker 使用 module 方式创建，输出 ES module 才能支持按需加载 xlsx。
      format: 'es',
    },
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      // Windows 上 localhost 同时解析到 127.0.0.1 和 ::1。不指定 host 时 Node 只监听 ::1，
      // Edge 走 IPv4 会得到「localhost 拒绝连接」。固定环回 IPv4，页面地址仍是 localhost:5173。
      host: '127.0.0.1',
      port: 5173,
      proxy: {
        '/api': {
          target: env.VITE_API_PROXY_TARGET ?? 'http://localhost:9000',
          changeOrigin: true,
        },
      },
    },
    // 浏览器端到端测试由 Playwright 单独执行，Vitest 只负责 src 内的单元测试。
    test: {
      include: ['src/**/*.test.ts'],
    },
  };
});
