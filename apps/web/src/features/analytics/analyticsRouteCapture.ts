import type { Pinia } from 'pinia';
import type { Router } from 'vue-router';

/**
 * 页面访问采集已退役；保留旧安装入口的空清理函数，旧调用方也不再注册监听或生成事件。
 * @deprecated 应用启动不再安装路由采集，关键操作由各业务流程显式记录。
 */
export const installAnalyticsRouteCapture = (_router: Router, _pinia: Pinia): (() => void) => () => {};
