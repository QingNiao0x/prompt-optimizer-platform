import { httpClient } from './http';

import type {
  ApiResponse,
  ContextAnalysisRequest,
  ContextSnapshot,
  OptimizationRequest,
  OptimizationResult,
} from '@/types/api';

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
