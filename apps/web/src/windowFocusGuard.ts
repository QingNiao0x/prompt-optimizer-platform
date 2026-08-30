/**
 * 页面隐藏期间的窗口唤起防护。
 *
 * 普通网页无法接管操作系统的最小化按钮或 Win+D，但用户脚本或浏览器注入逻辑
 * 在页面失焦后调用 window.focus 等原生 API，会让浏览器把窗口重新拉到前台。
 * 这里只吞掉“页面不可见时”的调用，不影响页面可见状态下的正常弹窗、新窗口和焦点行为。
 */
const wrapWhileHidden = (target: Window, methodName: string): void => {
  const host = target as unknown as Record<string, unknown>;
  const native = host[methodName];
  if (typeof native !== 'function') {
    return;
  }

  host[methodName] = (...args: unknown[]): unknown => {
    if (document.hidden) {
      if (import.meta.env.DEV) {
        console.debug(
          `[window-focus-guard] ignored window.${methodName} while page hidden`,
          new Error().stack,
        );
      }
      return undefined;
    }
    return (native as (...values: unknown[]) => unknown).apply(target, args);
  };
};

wrapWhileHidden(window, 'focus');
wrapWhileHidden(window, 'open');
wrapWhileHidden(window, 'alert');
wrapWhileHidden(window, 'confirm');
wrapWhileHidden(window, 'prompt');
