import { httpClient } from '@/services/http';
import { useAnalyticsDeliveryStore } from '@/stores/analyticsDelivery';
import type {
  AnalyticsAccountFilters,
  AnalyticsDashboard,
  AnalyticsDashboardQuery,
  AnalyticsDeliveryStatus,
  AnalyticsEventType,
  AnalyticsOperationLogPage,
  AnalyticsOperationQuery,
  AnalyticsRanking,
  ApiResponse,
} from '@/types/api';

/** 管理员独立读取当前实例的投递积压和故障状态，不能把统计查询成功当作采集完整。 */
export const getAnalyticsDeliveryStatus = async (): Promise<AnalyticsDeliveryStatus> => {
  const response = await httpClient.get<ApiResponse<AnalyticsDeliveryStatus>>('/api/v1/admin/analytics/delivery');
  return response.data.data;
};

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

/** 将无自由文本的浏览器事件加入可恢复队列；完成入队不代表服务端已经落库。 */
export const reportClientAnalyticsEvent = async (
  eventType: Extract<AnalyticsEventType, 'APP_VISIT' | 'RESULT_EXPORTED'>,
): Promise<void> => {
  useAnalyticsDeliveryStore().enqueue(eventType);
};

/** 路由与导出主业务只负责入队；失败由账号隔离队列重试，保留稳定事件 ID。 */
export const reportClientAnalyticsEventBestEffort = (
  eventType: Extract<AnalyticsEventType, 'APP_VISIT' | 'RESULT_EXPORTED'>,
): void => {
  useAnalyticsDeliveryStore().enqueue(eventType);
};
