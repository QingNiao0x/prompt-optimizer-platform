import { fileURLToPath } from 'node:url';

import { createServer } from 'vite';

const WEB_ROOT = fileURLToPath(new URL('..', import.meta.url));
const configuredE2ePort = Number.parseInt(process.env.PROMPT_OPTIMIZER_E2E_PORT ?? '5175', 10);
const e2ePort = Number.isFinite(configuredE2ePort) ? configuredE2ePort : 5175;
const e2eBaseUrl = `http://127.0.0.1:${e2ePort}`;

/**
 * 在测试进程内启动 Vite，测试结束后由 Playwright 调用返回的清理函数。
 * 这样不依赖系统回收 npm 子进程，Windows 和 CI 环境使用的是同一套启动方式。
 */
export default async function globalSetup(): Promise<() => Promise<void>> {
  if (process.env.PLAYWRIGHT_REUSE_EXISTING_SERVER === 'true') {
    const response = await fetch(e2eBaseUrl);
    if (!response.ok || !(await response.text()).includes('<title>Prompt Optimizer</title>')) {
      throw new Error(`${e2ePort} 端口上的服务不是可用的 Prompt Optimizer 前端`);
    }
    return async (): Promise<void> => undefined;
  }

  const server = await createServer({
    root: WEB_ROOT,
    logLevel: 'warn',
    server: {
      host: '127.0.0.1',
      port: e2ePort,
      strictPort: true,
    },
  });

  await server.listen();

  return async (): Promise<void> => {
    await server.close();
  };
}
