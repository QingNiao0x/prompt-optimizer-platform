import { fileURLToPath } from 'node:url';

import { createServer } from 'vite';

const WEB_ROOT = fileURLToPath(new URL('..', import.meta.url));

/**
 * 在测试进程内启动 Vite，测试结束后由 Playwright 调用返回的清理函数。
 * 这样不依赖系统回收 npm 子进程，Windows 和 CI 环境使用的是同一套启动方式。
 */
export default async function globalSetup(): Promise<() => Promise<void>> {
  const server = await createServer({
    root: WEB_ROOT,
    logLevel: 'warn',
    server: {
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
    },
  });

  await server.listen();

  return async (): Promise<void> => {
    await server.close();
  };
}
