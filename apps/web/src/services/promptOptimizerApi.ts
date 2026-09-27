import { httpClient } from './http';

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
  const response = await httpClient.get<ApiResponse<AvailableModel[]>>('/api/v1/models');
  return response.data;
};

export const preparePlanningContext = async (
  request: PlanningContextRequest,
): Promise<ApiResponse<PlanningContextPreparation>> => {
  const response = await httpClient.post<ApiResponse<PlanningContextPreparation>>(
    '/api/v1/context/planning',
    request,
  );
  return response.data;
};

export const createOptimizationPlan = async (
  request: OptimizationPlanRequest,
): Promise<ApiResponse<OptimizationPlan>> => {
  const response = await httpClient.post<ApiResponse<OptimizationPlan>>(
    '/api/v1/optimizations/plan',
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
    '/api/v1/optimizations',
    request,
  );
  return response.data;
};

export const listHistory = async (
  current = 1,
  size = 10,
  filters?: OptimizationHistoryFilters,
): Promise<ApiResponse<OptimizationHistoryPage>> => {
  const keyword = filters?.keyword?.trim();
  const dateRange = filters?.dateRange?.filter(Boolean).join(',');
  const response = await httpClient.get<ApiResponse<OptimizationHistoryPage>>(
    '/api/v1/optimization-history',
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
    `/api/v1/optimization-history/${id}`,
  );
  return response.data;
};

export const deleteHistory = async (id: string): Promise<ApiResponse<null>> => {
  const response = await httpClient.delete<ApiResponse<null>>(
    `/api/v1/optimization-history/${id}`,
  );
  return response.data;
};

export const reoptimizeHistory = async (
  id: string,
): Promise<ApiResponse<ReoptimizationResult>> => {
  const response = await httpClient.post<ApiResponse<ReoptimizationResult>>(
    `/api/v1/optimization-history/${id}/re-optimize`,
  );
  return response.data;
};
