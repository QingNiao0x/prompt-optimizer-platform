import { AxiosError } from 'axios';
import { httpClient } from './http';
import { trialEndpoint } from './trialApi';
import type { ApiErrorPayload, ApiResponse } from '@/types/api';
import type {
  ImagePromptAnswer, ImagePromptAsset, ImagePromptCapabilities, ImagePromptDocument, ImagePromptHistoryPage,
  ImagePromptInput, ImagePromptMode, ImagePromptModel, ImagePromptOperation, ImagePromptPlan,
  ImagePromptMediaAsset, ImagePromptMediaCapabilities, ImagePromptMask,
  SourcePromptAsset, SourcePromptCapabilities,
} from '@/types/imagePrompt';

/**
 * 二进制请求的业务错误也会被Axios包装为Blob；仅解码本平台有界JSON错误，保留状态与取消语义。
 * 非JSON、损坏或超限正文不转成提示文本，避免把网关页面或二进制内容展示给用户。
 */
async function readMediaBlob(request: Promise<{ data: Blob }>): Promise<Blob> {
  try { return (await request).data; }
  catch (failure: unknown) {
    if (!(failure instanceof AxiosError) || !(failure.response?.data instanceof Blob)) throw failure;
    const body = failure.response.data;
    failure.message = '素材图片读取失败，请稍后重试。';
    if (body.size <= 64 * 1024 && /^application\/(?:[a-z0-9.+-]*\+)?json(?:\s*;|$)/i.test(body.type)) {
      try {
        const payload = JSON.parse(await body.text()) as Partial<ApiErrorPayload> | null;
        if (typeof payload?.error?.message === 'string' && payload.error.message.trim()) {
          failure.response.data = { error: { message: payload.error.message,
            code: typeof payload.error.code === 'string' ? payload.error.code : '' },
          requestId: typeof payload.requestId === 'string' ? payload.requestId : '' };
        }
      } catch { /* 解析失败时继续抛出原AxiosError，使用固定提示而非错误正文。 */ }
    }
    throw failure;
  }
}

/** 专用视觉创作接口；浏览器只提交用户主动选择的素材，不接收供应商密钥或上游地址。 */
export const imagePromptApi = {
  async sourceCapabilities(): Promise<SourcePromptCapabilities> {
    return (await httpClient.get<ApiResponse<SourcePromptCapabilities>>('/api/v1/image-prompt-sources/capabilities')).data.data;
  },
  /** 本平台解析步骤不授权模型外发；范围审阅与同意在生成前单独完成。 */
  async uploadSource(file: File, signal: AbortSignal): Promise<SourcePromptAsset> {
    const form = new FormData(); form.append('file', file);
    return (await httpClient.post<ApiResponse<SourcePromptAsset>>('/api/v1/image-prompt-sources', form,
      { headers: { 'Content-Type': undefined }, signal, timeout: 120_000 })).data.data;
  },
  async prepareSourceText(name: string, entries: { path: string; content: string }[], signal: AbortSignal): Promise<SourcePromptAsset> {
    return (await httpClient.post<ApiResponse<SourcePromptAsset>>('/api/v1/image-prompt-sources/text', { name, entries }, { signal })).data.data;
  },
  async removeSource(id: string): Promise<void> { await httpClient.delete(`/api/v1/image-prompt-sources/${encodeURIComponent(id)}`); },
  async sourceConsent(input: ImagePromptInput, signal: AbortSignal): Promise<{ id: string; expiresAt: string }> {
    return (await httpClient.post<ApiResponse<{ id: string; expiresAt: string }>>('/api/v1/image-prompt-sources/consents', input, { signal })).data.data;
  },
  async capabilities(): Promise<ImagePromptCapabilities> {
    return (await httpClient.get<ApiResponse<ImagePromptCapabilities>>(trialEndpoint('/api/v1/image-prompts/capabilities', '/image-prompts/capabilities'))).data.data;
  },
  async upload(file: File, signal: AbortSignal): Promise<ImagePromptAsset> {
    const form = new FormData(); form.append('file', file);
    return (await httpClient.post<ApiResponse<ImagePromptAsset>>(trialEndpoint('/api/v1/image-prompt-assets', '/image-prompt-assets'), form,
      { headers: { 'Content-Type': undefined }, signal })).data.data;
  },
  async removeAsset(id: string): Promise<void> { await httpClient.delete(trialEndpoint(`/api/v1/image-prompt-assets/${encodeURIComponent(id)}`, `/image-prompt-assets/${encodeURIComponent(id)}`)); },
  /** 解析只上传到本平台；模型识别由之后的明确 Plan 操作及外发同意触发。 */
  async uploadMedia(file: File, signal: AbortSignal, startMs?: number, endMs?: number): Promise<ImagePromptMediaAsset> {
    const form = new FormData(); form.append('file', file);
    if (startMs !== undefined) form.append('startMs', String(startMs));
    if (endMs !== undefined) form.append('endMs', String(endMs));
    return (await httpClient.post<ApiResponse<ImagePromptMediaAsset>>('/api/v1/image-prompt-media', form,
      { headers: { 'Content-Type': undefined }, signal })).data.data;
  },
  async mediaCapabilities(): Promise<ImagePromptMediaCapabilities> {
    return (await httpClient.get<ApiResponse<ImagePromptMediaCapabilities>>('/api/v1/image-prompt-media/capabilities')).data.data;
  },
  async media(id: string, signal: AbortSignal): Promise<ImagePromptMediaAsset> {
    return (await httpClient.get<ApiResponse<ImagePromptMediaAsset>>(`/api/v1/image-prompt-media/${encodeURIComponent(id)}`, { signal })).data.data;
  },
  async mediaPreview(assetId: string, unitId: string, signal: AbortSignal): Promise<Blob> {
    return readMediaBlob(httpClient.get<Blob>(`/api/v1/image-prompt-media/${encodeURIComponent(assetId)}/units/${encodeURIComponent(unitId)}`,
      { responseType: 'blob', signal }));
  },
  /** 导出与模型外发相同的实际遮挡PNG；只访问平台私有素材，不调用模型。 */
  async exportMediaUnit(assetId: string, unitId: string, masks: ImagePromptMask[], signal: AbortSignal): Promise<Blob> {
    return readMediaBlob(httpClient.post<Blob>(`/api/v1/image-prompt-media/${encodeURIComponent(assetId)}/units/${encodeURIComponent(unitId)}/export`,
      { masks }, { responseType: 'blob', signal }));
  },
  async removeMedia(id: string): Promise<void> { await httpClient.delete(`/api/v1/image-prompt-media/${encodeURIComponent(id)}`); },
  async extract(requestId: string, input: ImagePromptInput, signal: AbortSignal): Promise<ImagePromptDocument> {
    // 参考素材还原与Plan均可先分批识别；普通单图及访客仍沿用共享等待时长。
    return (await httpClient.post<ApiResponse<ImagePromptDocument>>(trialEndpoint('/api/v1/image-prompts', '/image-prompts'), { requestId, input },
      { signal, ...(input.materials?.length ? { timeout: 270_000 } : {}) })).data.data;
  },
  async plan(requestId: string, input: ImagePromptInput, signal: AbortSignal): Promise<ImagePromptPlan> {
    // 多批素材分析在180秒后停止开启新阶段，还需等待最后一次有界模型请求返回；其余请求沿用全局超时。
    return (await httpClient.post<ApiResponse<ImagePromptPlan>>('/api/v1/image-prompts/plans', { requestId, input },
      { signal, ...(input.materials?.length ? { timeout: 270_000 } : {}) })).data.data;
  },
  async confirm(plan: ImagePromptPlan, requestId: string, answers: ImagePromptAnswer[], signal: AbortSignal): Promise<ImagePromptDocument> {
    return (await httpClient.post<ApiResponse<ImagePromptDocument>>(`/api/v1/image-prompts/plans/${plan.id}/confirm`,
      { requestId, fingerprint: plan.fingerprint, answers }, { signal })).data.data;
  },
  async operation(id: string): Promise<ImagePromptOperation> {
    return (await httpClient.get<ApiResponse<ImagePromptOperation>>(trialEndpoint(`/api/v1/image-prompts/operations/${id}`, `/image-prompts/operations/${id}`))).data.data;
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
