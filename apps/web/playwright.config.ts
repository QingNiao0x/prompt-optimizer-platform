import { defineConfig, devices } from '@playwright/test';

const browserChannel = process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'chrome';
const configuredE2ePort = Number.parseInt(process.env.PROMPT_OPTIMIZER_E2E_PORT ?? '5175', 10);
const e2ePort = Number.isFinite(configuredE2ePort) ? configuredE2ePort : 5175;

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  globalSetup: './e2e/global-setup.ts',
  outputDir: 'test-results',
  // 目录索引用例会在同一 Vite 源上操作 IndexedDB 与 OPFS。串行执行可以避免
  // Windows 本地联调时多个浏览器上下文争用存储和懒加载资源，保证默认命令稳定。
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
  ],
  use: {
    baseURL: `http://127.0.0.1:${e2ePort}`,
    locale: 'zh-CN',
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
    video: 'off',
  },
  projects: [
    {
      name: 'desktop-chrome',
      use: {
        ...devices['Desktop Chrome'],
        channel: browserChannel,
      },
    },
    {
      name: 'mobile-chrome',
      use: {
        ...devices['Pixel 7'],
        channel: browserChannel,
      },
    },
  ],
});
