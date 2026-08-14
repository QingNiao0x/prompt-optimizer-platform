import { httpClient } from './http';

import type {
  ApiResponse,
  ProviderConfigSavePayload,
  ProviderConfigSummary,
  ProviderConfigUpdatePayload,
} from '@/types/api';

export const listProviderConfigs = async (): Promise<
  ApiResponse<ProviderConfigSummary[]>
> => {
  const response = await httpClient.get<ApiResponse<ProviderConfigSummary[]>>(
    '/api/v1/provider-configs',
  );
  return response.data;
};

export const createProviderConfig = async (
  payload: ProviderConfigSavePayload,
): Promise<ApiResponse<ProviderConfigSummary>> => {
  const response = await httpClient.post<ApiResponse<ProviderConfigSummary>>(
    '/api/v1/provider-configs',
    payload,
  );
  return response.data;
};

export const updateProviderConfig = async (
  id: string,
  payload: ProviderConfigUpdatePayload,
): Promise<ApiResponse<ProviderConfigSummary>> => {
  const response = await httpClient.patch<ApiResponse<ProviderConfigSummary>>(
    `/api/v1/provider-configs/${id}`,
    payload,
  );
  return response.data;
};

export const deleteProviderConfig = async (
  id: string,
): Promise<ApiResponse<null>> => {
  const response = await httpClient.delete<ApiResponse<null>>(
    `/api/v1/provider-configs/${id}`,
  );
  return response.data;
};
