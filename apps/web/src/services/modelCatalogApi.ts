import { httpClient } from './http';
import type { AdminModel, AdminModelChange, ApiResponse, ModelRoute } from '@/types/api';

/** 管理接口只传模型目录元数据；供应商端点和密钥保留在服务端。 */
export const listAdminModels = async (): Promise<AdminModel[]> =>
  (await httpClient.get<ApiResponse<AdminModel[]>>('/api/v1/admin/models')).data.data;

export const listModelRoutes = async (): Promise<ModelRoute[]> =>
  (await httpClient.get<ApiResponse<ModelRoute[]>>('/api/v1/admin/models/routes')).data.data;

export const createAdminModel = async (change: AdminModelChange): Promise<AdminModel> =>
  (await httpClient.post<ApiResponse<AdminModel>>('/api/v1/admin/models', change)).data.data;

export const updateAdminModel = async (id: string, change: AdminModelChange): Promise<AdminModel> =>
  (await httpClient.put<ApiResponse<AdminModel>>(`/api/v1/admin/models/${id}`, change)).data.data;

export const deleteAdminModel = async (id: string): Promise<void> => {
  await httpClient.delete(`/api/v1/admin/models/${id}`);
};
