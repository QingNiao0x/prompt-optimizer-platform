import { httpClient } from '@/services/http';
import type { AnalyticsClientContext, AnalyticsClientEvent, ApiResponse } from '@/types/api';

/** 非阻断加载当前登录的审计关联号；未知时旧事件仍按原有 null 发送。 */
export const getClientAnalyticsContext = async (): Promise<AnalyticsClientContext> => {
  const response = await httpClient.get<ApiResponse<AnalyticsClientContext>>('/api/v1/analytics/context', { timeout: 10_000 });
  return response.data.data;
};

/** 重试仍发送同一个事件 ID；所有者字段仅作当前服务端身份核对，不能决定日志归属。 */
export const postClientAnalyticsEvent = async (
  event: AnalyticsClientEvent,
  signal?: AbortSignal,
): Promise<void> => {
  await httpClient.post<ApiResponse<void>>('/api/v1/analytics/events', event, { signal, timeout: 10_000 });
};
