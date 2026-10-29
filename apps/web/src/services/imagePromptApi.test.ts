import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, AxiosHeaders, CanceledError } from 'axios';
import type { ImagePromptInput } from '@/types/imagePrompt';

vi.mock('./http', async importOriginal => ({ ...await importOriginal<typeof import('./http')>(), httpClient: { get: vi.fn(), post: vi.fn() } }));
vi.mock('./trialApi', () => ({ trialEndpoint: (path: string) => path }));
import { getApiErrorCode, getApiErrorMessage, getApiErrorRequestId, httpClient } from './http';
import { imagePromptApi } from './imagePromptApi';

const input = (): ImagePromptInput => ({ mode: 'CUSTOM_CREATION', modelId: 'vision', images: [],
  creationIntent: '合成产品海报', exactText: '', context: '', language: 'ZH', targetTool: '通用文生图' });
const blobFailure = (data: Blob): AxiosError => new AxiosError('Request failed with status code 409', undefined, undefined, undefined,
  { data, status: 409, statusText: 'Conflict', headers: {}, config: { headers: new AxiosHeaders() } });

describe('image prompt request boundaries', () => {
  beforeEach(() => { vi.resetAllMocks(); });

  it('extends only multimodal Plan wait while preserving cancellation and the original input', async () => {
    const payload: ImagePromptInput = { ...input(), materials: [{ assetId: 'reference', track: 'REFERENCE', role: 'STYLE',
      units: [{ unitId: 'page-1', masks: [] }] }] };
    const signal = new AbortController().signal;
    vi.mocked(httpClient.post).mockResolvedValue({ data: { data: { id: 'plan' } } });
    await imagePromptApi.plan('operation', payload, signal);
    expect(httpClient.post).toHaveBeenCalledWith('/api/v1/image-prompts/plans', { requestId: 'operation', input: payload },
      { signal, timeout: 270_000 });
  });

  it.each([undefined, []])('leaves ordinary and empty-material Plan requests on the shared default timeout', async materials => {
    const payload = { ...input(), materials };
    const signal = new AbortController().signal;
    vi.mocked(httpClient.post).mockResolvedValue({ data: { data: { id: 'plan' } } });
    await imagePromptApi.plan('operation', payload, signal);
    expect(httpClient.post).toHaveBeenCalledWith('/api/v1/image-prompts/plans', { requestId: 'operation', input: payload }, { signal });
  });

  it.each([false, true])('sets the extended wait only for direct extraction with media=%s', async media => {
    const payload: ImagePromptInput = { ...input(), mode: 'REFERENCE_RECONSTRUCTION', creationIntent: '',
      ...(media ? { materials: [{ assetId: 'reference', track: 'REFERENCE', role: 'GENERAL', units: [{ unitId: 'frame', masks: [] }] }] } : {}) };
    const signal = new AbortController().signal;
    vi.mocked(httpClient.post).mockResolvedValue({ data: { data: { id: 'document' } } });
    await imagePromptApi.extract('operation', payload, signal);
    expect(httpClient.post).toHaveBeenCalledWith('/api/v1/image-prompts', { requestId: 'operation', input: payload },
      media ? { signal, timeout: 270_000 } : { signal });
  });

  it('exports only the requested private unit and exact mask payload as a blob without a model endpoint', async () => {
    const masks = [{ x: .1, y: .2, width: .3, height: .4 }], signal = new AbortController().signal;
    const blob = new Blob(['synthetic-png'], { type: 'image/png' });
    vi.mocked(httpClient.post).mockResolvedValue({ data: blob });
    expect(await imagePromptApi.exportMediaUnit('asset/id', 'unit/id', masks, signal)).toBe(blob);
    expect(httpClient.post).toHaveBeenCalledExactlyOnceWith('/api/v1/image-prompt-media/asset%2Fid/units/unit%2Fid/export',
      { masks }, { responseType: 'blob', signal });
  });

  it.each(['preview', 'export'] as const)('preserves the server JSON error, code and request id on a %s blob response', async operation => {
    const failure = blobFailure(new Blob([JSON.stringify({ error: { code: 'IMAGE_MEDIA_UNAVAILABLE', message: '素材已不可用，请重新上传。' }, requestId: 'synthetic-request' })], { type: 'application/json' }));
    vi.mocked(operation === 'preview' ? httpClient.get : httpClient.post).mockRejectedValue(failure);
    const request = operation === 'preview' ? imagePromptApi.mediaPreview('asset', 'unit', new AbortController().signal)
      : imagePromptApi.exportMediaUnit('asset', 'unit', [], new AbortController().signal);
    const received: unknown = await request.catch(error => error);
    expect(received).toBe(failure);
    expect(getApiErrorMessage(received)).toBe('素材已不可用，请重新上传。');
    expect(getApiErrorCode(received)).toBe('IMAGE_MEDIA_UNAVAILABLE');
    expect(getApiErrorRequestId(received)).toBe('synthetic-request');
  });

  it.each([
    new Blob(['private-binary-data'], { type: 'image/png' }),
    new Blob(['<html>untrusted gateway output</html>'], { type: 'text/html' }),
    new Blob(['{"error":'], { type: 'application/json' }),
    new Blob([JSON.stringify({ error: { message: { raw: 'private-object' } } })], { type: 'application/json' }),
    new Blob(['x'.repeat(65537)], { type: 'application/json' }),
  ])('does not expose malformed, non-JSON or oversized blob error bodies', async body => {
    const failure = blobFailure(body);
    vi.mocked(httpClient.get).mockRejectedValue(failure);
    const received: unknown = await imagePromptApi.mediaPreview('asset', 'unit', new AbortController().signal).catch(error => error);
    expect(received).toBe(failure);
    expect(getApiErrorMessage(received)).toBe('素材图片读取失败，请稍后重试。');
    expect(getApiErrorCode(received)).toBe('');
  });

  it('keeps cancellation errors unchanged rather than decoding or retrying a blob request', async () => {
    const cancellation = new CanceledError('已取消');
    vi.mocked(httpClient.get).mockRejectedValue(cancellation);
    await expect(imagePromptApi.mediaPreview('asset', 'unit', new AbortController().signal)).rejects.toBe(cancellation);
    expect(httpClient.get).toHaveBeenCalledTimes(1);
  });
  it('keeps text preparation and explicit external-send consent on distinct platform endpoints', async () => {
    const signal = new AbortController().signal, entries = [{ path: 'src/a.ts', content: 'export const a = 1' }];
    vi.mocked(httpClient.post).mockResolvedValue({ data: { data: { id: 'asset' } } });
    await imagePromptApi.prepareSourceText('项目片段', entries, signal);
    expect(httpClient.post).toHaveBeenLastCalledWith('/api/v1/image-prompt-sources/text', { name: '项目片段', entries }, { signal });
    const payload = { ...input(), sourceTask: { taskType: 'CODE' as const, sources: [] } };
    await imagePromptApi.sourceConsent(payload, signal);
    expect(httpClient.post).toHaveBeenLastCalledWith('/api/v1/image-prompt-sources/consents', payload, { signal });
  });

});
