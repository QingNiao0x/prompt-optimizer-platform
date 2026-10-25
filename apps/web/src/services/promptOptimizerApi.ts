import { httpClient } from './http';
import { trialEndpoint } from './trialApi';

import type {
  ApiResponse,
  AvailableModel,
  ContextAnalysisRequest,
  ContextSnapshot,
  OptimizationHistoryDetail,
  OptimizationHistoryFilters,
  OptimizationHistoryPage,
  OptimizationPlan,
  OptimizationPlanRequest,
  OptimizationRequest,
  OptimizationResult,
  PlanningContextPreparation,
  PlanningContextRequest,
  ReoptimizationResult,
} from '@/types/api';

/** 读取管理员发布的可选模型目录；浏览器不会收到上游端点或 API Key。 */
export const listAvailableModels = async (): Promise<ApiResponse<AvailableModel[]>> => {
  const response = await httpClient.get<ApiResponse<AvailableModel[]>>(trialEndpoint('/api/v1/models', '/models'));
  return response.data;
};

export const preparePlanningContext = async (
  request: PlanningContextRequest,
): Promise<ApiResponse<PlanningContextPreparation>> => {
  const response = await httpClient.post<ApiResponse<PlanningContextPreparation>>(
    '/api/v2/context/planning',
    request,
  );
  return response.data;
};

export const createOptimizationPlan = async (
  request: OptimizationPlanRequest,
): Promise<ApiResponse<OptimizationPlan>> => {
  const response = await httpClient.post<ApiResponse<OptimizationPlan>>(
    '/api/v2/optimizations/plan',
    request,
  );
  return response.data;
};

export const analyzeContext = async (
  request: ContextAnalysisRequest,
): Promise<ApiResponse<ContextSnapshot>> => {
  const response = await httpClient.post<ApiResponse<ContextSnapshot>>(
    '/api/v1/context/analyze',
    request,
  );
  return response.data;
};

export const optimizePrompt = async (
  request: OptimizationRequest,
): Promise<ApiResponse<OptimizationResult>> => {
  const response = await httpClient.post<ApiResponse<OptimizationResult>>(
    trialEndpoint('/api/v2/optimizations', '/optimizations'),
    request,
    { headers: { 'X-Trial-Request-Id': crypto.randomUUID() } },
  );
  // TEXT协议不返回sections；空集合仅兼容集合操作，不制造四个假段落或重建正文。
  return response.data.data.resultKind === 'TEXT'
    ? { ...response.data, data: { ...response.data.data, sections: [] } }
    : response.data;
};

export const listHistory = async (
  current = 1,
  size = 10,
  filters?: OptimizationHistoryFilters,
): Promise<ApiResponse<OptimizationHistoryPage>> => {
  const keyword = filters?.keyword?.trim();
  const dateRange = filters?.dateRange?.filter(Boolean).join(',');
  const response = await httpClient.get<ApiResponse<OptimizationHistoryPage>>(
    '/api/v2/optimization-history',
    {
      params: {
        current,
        size,
        ...(keyword ? { keyword } : {}),
        ...(dateRange ? { dateRange } : {}),
      },
    },
  );
  return response.data;
};

export const getHistory = async (
  id: string,
): Promise<ApiResponse<OptimizationHistoryDetail>> => {
  const response = await httpClient.get<ApiResponse<OptimizationHistoryDetail>>(
    `/api/v2/optimization-history/${id}`,
  );
  return response.data;
};

export const deleteHistory = async (id: string): Promise<ApiResponse<null>> => {
  const response = await httpClient.delete<ApiResponse<null>>(
    `/api/v2/optimization-history/${id}`,
  );
  return response.data;
};

export const reoptimizeHistory = async (
  id: string,
): Promise<ApiResponse<ReoptimizationResult>> => {
  const response = await httpClient.post<ApiResponse<ReoptimizationResult>>(
    `/api/v2/optimization-history/${id}/re-optimize`,
  );
  return response.data.data.result.resultKind === 'TEXT'
    ? { ...response.data, data: { ...response.data.data, result: { ...response.data.data.result, sections: [] } } }
    : response.data;
};

/** 自然编辑不调用模型；服务端按当前身份、删除状态和编辑版本保存同一份正文。 */
export const saveNaturalPrompt = async (
  id: string,
  optimizedPrompt: string,
  expectedRevision: number,
): Promise<ApiResponse<{ optimizedPrompt: string; editRevision: number }>> => {
  const response = await httpClient.patch<ApiResponse<{ optimizedPrompt: string; editRevision: number }>>(
    '/api/v2/optimization-history/' + encodeURIComponent(id) + '/document',
    { optimizedPrompt, expectedRevision },
  );
  return response.data;
};
