import { chromium } from '../apps/web/node_modules/playwright/index.mjs';

const browserChannel = process.env.PLAYWRIGHT_BROWSER_CHANNEL ?? 'msedge';
const browser = await chromium.launch({ channel: browserChannel, headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

// 在应用脚本加载前记录浏览器内容区的全局键盘和全屏监听。
// 该检查不模拟 Windows 最小化或 Win+D；它们属于操作系统窗口管理，网页无法可靠接管。
await page.addInitScript(() => {
  const watchedEventTypes = new Set(['keydown', 'keyup', 'fullscreenchange']);
  const registrations = [];

  const wrapAddEventListener = (target, targetName) => {
    const originalAddEventListener = target.addEventListener.bind(target);
    target.addEventListener = (type, listener, options) => {
      if (watchedEventTypes.has(type)) {
        registrations.push({ target: targetName, type });
      }
      return originalAddEventListener(type, listener, options);
    };
  };

  wrapAddEventListener(window, 'window');
  wrapAddEventListener(document, 'document');
  window.__promptOptimizerWindowDiagnostic = { registrations };
});

await page.goto('http://127.0.0.1:5173', { waitUntil: 'networkidle' });

const pageState = await page.evaluate(() => ({
  fullscreen: Boolean(document.fullscreenElement),
  bodyOverflow: getComputedStyle(document.body).overflow,
  htmlOverflow: getComputedStyle(document.documentElement).overflow,
  bodyPosition: getComputedStyle(document.body).position,
  viewport: { width: window.innerWidth, height: window.innerHeight },
  activeElement: document.activeElement?.tagName ?? null,
  globalEventRegistrations: window.__promptOptimizerWindowDiagnostic.registrations,
  keyboardShortcutPrevention: ['Meta', 'd'].map((key) => {
    const event = new KeyboardEvent('keydown', {
      key,
      code: key === 'd' ? 'KeyD' : 'MetaLeft',
      metaKey: true,
      bubbles: true,
      cancelable: true,
    });
    document.dispatchEvent(event);
    return { key, defaultPrevented: event.defaultPrevented };
  }),
}));

const violations = [];
if (pageState.fullscreen) violations.push('页面进入了 Fullscreen API 全屏状态');
if (pageState.bodyOverflow === 'hidden' || pageState.htmlOverflow === 'hidden') {
  violations.push('页面根节点锁定了滚动');
}
if (pageState.keyboardShortcutPrevention.some((item) => item.defaultPrevented)) {
  violations.push('页面阻止了 Meta 或 Meta+D 键盘事件');
}

console.log(JSON.stringify({ browserChannel, browserVersion: browser.version(), pageState, violations }, null, 2));
await browser.close();

if (violations.length > 0) {
  process.exitCode = 1;
}
