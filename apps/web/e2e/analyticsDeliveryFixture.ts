import type { AnalyticsDeliveryStatus } from '../src/types/api';

/** 健康投递夹具仅用于页面交互，不模拟真实磁盘或数据库恢复验证。 */
export const healthyAnalyticsDelivery: AnalyticsDeliveryStatus = {
  status: 'HEALTHY', scope: 'INSTANCE', healthy: true, initialized: true,
  journalAvailable: true, databaseAvailable: true,
  pendingEvents: 0, oldestPendingAt: null, oldestPendingAgeSeconds: 0,
  databaseFailures: 0, journalFailures: 0, corruptFiles: 0, deliveredEvents: 10,
  lastFailureAt: null, lastDeliveredAt: '2026-10-02T00:00:00Z',
  backlogAlert: false, pendingAlertThreshold: 1000, oldestPendingAlertSeconds: 300,
};
