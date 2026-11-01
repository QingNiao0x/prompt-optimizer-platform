import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useContextVisionStore } from './contextVision';
import { useAuthStore } from './auth';
import { listContextVisionModels } from '@/services/documentUploadApi';
import { suggestedVisionPages } from '@/features/document-upload/contextVisionBatch';
import type { ContextVisionModel, DocumentUploadStatus } from '@/types/api';

vi.mock('@/services/documentUploadApi', () => ({ listContextVisionModels: vi.fn() }));
const models: ContextVisionModel[] = [{ id: 'vision', displayName: 'Fixture', vision: true, maxImages: 1, evidence: 'controlled', mock: false }];
const status: DocumentUploadStatus = { documentId: 'controlled', path: 'fixture.png', language: 'png', phase: 'AWAITING_CONFIRMATION',
  fileSizeBytes: 100, uploadedBytes: 100, progressPercent: 45, extractedCharacters: 0, chunkCount: 0, summary: '', warnings: [], errorMessage: '',
  expiresAt: '2099-01-01T00:00:00Z', chunkSizeBytes: 1048576,
  visionReview: { pages: [], maxPages: 10, maxCalls: 10, maxOutputTokens: 8000, deadlineSeconds: 180 } };

describe('context vision confirmation ownership', () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks(); vi.mocked(listContextVisionModels).mockResolvedValue(models); });
  it('only returns the explicit confirmed page selection and then clears the review', async () => {
    const store = useContextVisionStore();
    const pending = store.review(status);
    expect(store.visible).toBe(true);
    const choice = { modelId: 'vision', consent: true, pages: [{ page: 1, masks: [{ x: 0, y: 0, width: 0.2, height: 0.2 }] }] };
    store.confirm(choice);
    expect(await pending).toEqual(choice);
    expect(store.visible).toBe(false);
  });
  it('identity changes reject the old review and discard late model responses', async () => {
    let release!: (value: ContextVisionModel[]) => void;
    vi.mocked(listContextVisionModels).mockReturnValue(new Promise(resolve => { release = resolve; }));
    const store = useContextVisionStore();
    const pending = store.review(status).catch(error => error);
    useAuthStore().user = { userId: 'controlled-other', tenantId: 'controlled-other', workspaceId: 'controlled-other', email: 'other@example.test', displayName: 'Fixture', platformAdmin: false };
    expect((await pending).name).toBe('AbortError');
    release(models); await Promise.resolve();
    expect(store.models).toEqual([]); expect(store.visible).toBe(false);
  });
  it('aborting the upload rejects the review and releases its pending confirmation', async () => {
    const abort = new AbortController();
    const store = useContextVisionStore();
    const pending = store.review(status, abort.signal).catch(error => error);
    abort.abort();
    expect((await pending).name).toBe('AbortError');
    expect(store.status).toBeUndefined();
  });
  it('keeps a parked batch on local extraction until a row is explicitly recognized', async () => {
    const store = useContextVisionStore();
    const second = { ...status, documentId: 'other', path: 'other.png' };
    const pending = store.settleBatch([status, second], 'vision');
    expect(store.batchVisible).toBe(true);
    expect(store.batch?.map((row) => row.mode)).toEqual(['local', 'local']);
    store.applyBatch();
    expect(await pending).toEqual([
      { documentId: 'controlled', confirmation: { modelId: '', consent: false, pages: [] } },
      { documentId: 'other', confirmation: { modelId: '', consent: false, pages: [] } },
    ]);
    expect(store.batchVisible).toBe(false);
  });
  it('requires consent before sending a recognized page and drops the batch when identity changes', async () => {
    const reviewed = { ...status, visionReview: { pages: [{ page: 1, readableText: false, hasImages: true, suggested: true, blocked: false, reason: '扫描页' }],
      maxPages: 10, maxCalls: 10, maxOutputTokens: 8000, deadlineSeconds: 180 } };
    const store = useContextVisionStore();
    const pending = store.settleBatch([reviewed], 'vision');
    store.markRow('controlled', 'recognize');
    expect(store.canApplyBatch).toBe(false);
    await vi.waitFor(() => expect(store.loadingModels).toBe(false));
    store.modelId = 'vision';
    store.batchConsent = true;
    expect(store.canApplyBatch).toBe(true);
    expect(suggestedVisionPages(reviewed)).toEqual([{ page: 1, masks: [] }]);
    store.applyBatch();
    expect(await pending).toEqual([{ documentId: 'controlled', confirmation: { modelId: 'vision', consent: true, pages: [{ page: 1, masks: [] }] } }]);

    const cancelled = store.settleBatch([status], 'vision');
    useAuthStore().user = { userId: 'controlled-other', tenantId: 'controlled-other', workspaceId: 'controlled-other', email: 'other@example.test', displayName: 'Fixture', platformAdmin: false };
    expect(await cancelled).toBeNull();
    expect(store.batchVisible).toBe(false);
  });
});
