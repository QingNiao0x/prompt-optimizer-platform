import { httpClient } from './http';

import type {
  ApiResponse,
  AvailableModel,
  ContextAnalysisRequest,
  ContextSnapshot,
  OptimizationHistoryDetail,
  OptimizationHistoryPage,
  OptimizationPlan,
  OptimizationPlanRequest,
  OptimizationRequest,
  OptimizationResult,
  PlanningContextPreparation,
  PlanningContextRequest,
  ReoptimizationResult,
} from '@/types/api';

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
  page = 0,
  size = 20,
): Promise<ApiResponse<OptimizationHistoryPage>> => {
  const response = await httpClient.get<ApiResponse<OptimizationHistoryPage>>(
    '/api/v1/optimization-history',
    { params: { page, size } },
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
