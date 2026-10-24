import { httpClient } from './http';
import type { ApiResponse } from '@/types/api';
import type {
  ImagePromptAnswer, ImagePromptAsset, ImagePromptCapabilities, ImagePromptDocument, ImagePromptHistoryPage,
  ImagePromptInput, ImagePromptMode, ImagePromptModel, ImagePromptOperation, ImagePromptPlan,
} from '@/types/imagePrompt';

/** 专用图像接口；浏览器只提交用户主动选择的图片，不接收供应商密钥或上游地址。 */
export const imagePromptApi = {
  async capabilities(): Promise<ImagePromptCapabilities> {
    return (await httpClient.get<ApiResponse<ImagePromptCapabilities>>('/api/v1/image-prompts/capabilities')).data.data;
  },
  async upload(file: File, signal: AbortSignal): Promise<ImagePromptAsset> {
    const form = new FormData(); form.append('file', file);
    return (await httpClient.post<ApiResponse<ImagePromptAsset>>('/api/v1/image-prompt-assets', form,
      { headers: { 'Content-Type': undefined }, signal })).data.data;
  },
  async removeAsset(id: string): Promise<void> { await httpClient.delete(`/api/v1/image-prompt-assets/${encodeURIComponent(id)}`); },
  async extract(requestId: string, input: ImagePromptInput, signal: AbortSignal): Promise<ImagePromptDocument> {
    return (await httpClient.post<ApiResponse<ImagePromptDocument>>('/api/v1/image-prompts', { requestId, input }, { signal })).data.data;
  },
  async plan(requestId: string, input: ImagePromptInput, signal: AbortSignal): Promise<ImagePromptPlan> {
    return (await httpClient.post<ApiResponse<ImagePromptPlan>>('/api/v1/image-prompts/plans', { requestId, input }, { signal })).data.data;
  },
  async confirm(plan: ImagePromptPlan, requestId: string, answers: ImagePromptAnswer[], signal: AbortSignal): Promise<ImagePromptDocument> {
    return (await httpClient.post<ApiResponse<ImagePromptDocument>>(`/api/v1/image-prompts/plans/${plan.id}/confirm`,
      { requestId, fingerprint: plan.fingerprint, answers }, { signal })).data.data;
  },
  async operation(id: string): Promise<ImagePromptOperation> {
    return (await httpClient.get<ApiResponse<ImagePromptOperation>>(`/api/v1/image-prompts/operations/${id}`)).data.data;
  },
  async retrySave(id: string): Promise<ImagePromptDocument> {
    return (await httpClient.post<ApiResponse<ImagePromptDocument>>(`/api/v1/image-prompts/operations/${id}/save`)).data.data;
  },
  async history(current: number, size: number, mode?: ImagePromptMode): Promise<ImagePromptHistoryPage> {
    return (await httpClient.get<ApiResponse<ImagePromptHistoryPage>>('/api/v1/image-prompts/history', { params: { current, size, mode } })).data.data;
  },
  async detail(id: string): Promise<ImagePromptDocument> {
    return (await httpClient.get<ApiResponse<ImagePromptDocument>>(`/api/v1/image-prompts/history/${encodeURIComponent(id)}`)).data.data;
  },
  async edit(document: ImagePromptDocument, optimizedPrompt: string): Promise<ImagePromptDocument> {
    return (await httpClient.patch<ApiResponse<ImagePromptDocument>>(`/api/v1/image-prompts/history/${document.recordId}`,
      { optimizedPrompt, expectedRevision: document.editRevision })).data.data;
  },
  async remove(id: string): Promise<void> { await httpClient.delete(`/api/v1/image-prompts/history/${encodeURIComponent(id)}`); },
  async rewrite(document: ImagePromptDocument, requestId: string, instruction: string, signal: AbortSignal): Promise<ImagePromptDocument> {
    return (await httpClient.post<ApiResponse<ImagePromptDocument>>(`/api/v1/image-prompts/history/${document.recordId}/re-optimize`,
      { requestId, instruction, expectedRevision: document.editRevision }, { signal })).data.data;
  },
  async adminModels(): Promise<ImagePromptModel[]> {
    return (await httpClient.get<ApiResponse<ImagePromptModel[]>>('/api/v1/admin/image-prompt-models')).data.data;
  },
  async configureModel(id: string, enabled: boolean, maxImages: number, evidence: string): Promise<void> {
    // 模型标识可能含斜杠；查询参数避免 Servlet 将编码后的斜杠当作非法路径。
    await httpClient.put('/api/v1/admin/image-prompt-models', { enabled, maxImages, evidence }, { params: { modelId: id } });
  },
};
