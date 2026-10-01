import { httpClient } from '@/services/http';
import type {
  AnalyticsAccountFilters,
  AnalyticsDashboard,
  AnalyticsDashboardQuery,
  AnalyticsEventType,
  AnalyticsOperationLogPage,
  AnalyticsOperationQuery,
  AnalyticsRanking,
  ApiResponse,
} from '@/types/api';

/** 读取平台管理员统计总览；服务端再次核对当前数据库角色。 */
export const getAnalyticsDashboard = async (
  query: AnalyticsDashboardQuery,
): Promise<AnalyticsDashboard> => {
  const response = await httpClient.get<ApiResponse<AnalyticsDashboard>>(
    '/api/v1/admin/analytics/dashboard',
    { params: query },
  );
  return response.data.data;
};

/** 读取账号日、周或月使用频率排行。 */
export const getAnalyticsRanking = async (
  period: 'DAY' | 'WEEK' | 'MONTH',
  date: string,
  limit = 20,
  account: AnalyticsAccountFilters = {},
): Promise<AnalyticsRanking> => {
  const response = await httpClient.get<ApiResponse<AnalyticsRanking>>(
    '/api/v1/admin/analytics/usage-ranking',
    { params: { period, date, limit, userId: account.userId, email: account.email, displayName: account.displayName } },
  );
  return response.data.data;
};

/** 按时间、用户和事件类型分页读取安全展示字段组成的关键操作日志。 */
export const getAnalyticsOperations = async (
  query: AnalyticsOperationQuery,
): Promise<AnalyticsOperationLogPage> => {
  const response = await httpClient.get<ApiResponse<AnalyticsOperationLogPage>>(
    '/api/v1/admin/analytics/operations',
    { params: query },
  );
  return response.data.data;
};

/** 报告无自由文本的浏览器事件；服务端从认证上下文推导账号 ID。 */
export const reportClientAnalyticsEvent = async (
  eventType: Extract<AnalyticsEventType, 'APP_VISIT' | 'RESULT_EXPORTED'>,
): Promise<void> => {
  await httpClient.post<ApiResponse<void>>('/api/v1/analytics/events', { eventType });
};

/** 以非阻断方式提交客户端事件，失败时仅输出固定提示，不影响主业务。 */
export const reportClientAnalyticsEventBestEffort = (
  eventType: Extract<AnalyticsEventType, 'APP_VISIT' | 'RESULT_EXPORTED'>,
): void => {
  void reportClientAnalyticsEvent(eventType).catch(() => {
    console.warn('统计事件暂未记录。');
  });
};
