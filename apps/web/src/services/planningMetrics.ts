import { httpClient } from './http';

type PlanningEvent = 'CANCELLED' | 'CONFIRMED' | 'CUSTOM_ANSWER' | 'RESULT_EDITED' | 'EXPIRED_RECOVERED';

// 指标失败不能阻塞业务；只发送枚举与计划编号，不包含回答、提示词或文件。
export const recordPlanningEvent = (planId: string | null | undefined, event: PlanningEvent): void => {
  if (!planId) return;
  void httpClient.post('/api/v1/optimizations/plan-events', { planId, event }).catch(() => undefined);
};
