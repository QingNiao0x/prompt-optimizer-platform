import { describe, expect, it, vi } from 'vitest';

vi.mock('@/services/documentUploadApi', () => ({
  createDocumentUpload: vi.fn(),
  uploadDocumentChunk: vi.fn(),
  completeDocumentUpload: vi.fn(),
  getDocumentUploadStatus: vi.fn(),
  deleteDocumentUpload: vi.fn(),
  confirmContextVision: vi.fn(),
}));

import { uploadAndIndexDocument } from './documentUploader';
import {
  completeDocumentUpload,
  createDocumentUpload,
  uploadDocumentChunk,
  confirmContextVision,
  getDocumentUploadStatus,
} from '@/services/documentUploadApi';
import type { DocumentUploadStatus } from '@/types/api';

const status = (overrides: Partial<DocumentUploadStatus> = {}): DocumentUploadStatus => ({
  documentId: 'document-123',
  path: '论文.txt',
  language: 'text',
  phase: 'UPLOADING',
  fileSizeBytes: 2_500_000,
  uploadedBytes: 0,
  progressPercent: 0,
  extractedCharacters: 0,
  chunkCount: 0,
  summary: '',
  warnings: [],
  errorMessage: '',
  expiresAt: '2026-09-12T12:00:00Z',
  chunkSizeBytes: 1_000_000,
  ...overrides,
});

describe('uploadAndIndexDocument', () => {
  it('reports every acknowledged chunk even when the browser emits no upload events', async () => {
    vi.clearAllMocks();
    vi.mocked(createDocumentUpload).mockResolvedValue({ requestId: 'create', data: status({ chunkSizeBytes: 4 }) });
    vi.mocked(uploadDocumentChunk).mockResolvedValue({ requestId: 'chunk', data: status() });
    vi.mocked(completeDocumentUpload).mockResolvedValue({ requestId: 'complete', data: status({ phase: 'READY', uploadedBytes: 10 }) });
    const progress = vi.fn();
    await uploadAndIndexDocument(new File(['0123456789'], 'small.txt'), 'small.txt', 'text', progress);
    expect(progress.mock.calls.map(([value]) => value).filter(value => value.phase === 'UPLOADING')
      .map(value => [value.uploadedBytes, value.totalBytes])).toEqual([[4, 10], [8, 10], [10, 10]]);
    vi.clearAllMocks();
  });
  it('waits for explicit vision confirmation and queries an uncertain confirmation without replaying it', async () => {
    vi.clearAllMocks();
    const ready = status({ phase: 'PARTIAL', warnings: ['第 2 页未识别'], chunkCount: 1 });
    const waiting = status({ phase: 'AWAITING_CONFIRMATION', visionReview: { pages: [{ page: 1, readableText: false,
      hasImages: true, suggested: true, blocked: false, reason: '扫描页' }], maxPages: 10, maxCalls: 10, maxOutputTokens: 8000, deadlineSeconds: 180 } });
    vi.mocked(createDocumentUpload).mockResolvedValue({ requestId: 'create', data: status() });
    vi.mocked(uploadDocumentChunk).mockResolvedValue({ requestId: 'chunk', data: status() });
    vi.mocked(completeDocumentUpload).mockResolvedValue({ requestId: 'complete', data: waiting });
    vi.mocked(confirmContextVision).mockRejectedValue(new Error('uncertain transport'));
    vi.mocked(getDocumentUploadStatus).mockResolvedValue({ requestId: 'status', data: ready });
    let resolve!: (value: { modelId: string; consent: boolean; pages: { page: number; masks: [] }[] }) => void;
    const confirmation = new Promise<{ modelId: string; consent: boolean; pages: { page: number; masks: [] }[] }>(settle => { resolve = settle; });
    const review = vi.fn(() => confirmation);
    const operation = uploadAndIndexDocument(new File(['synthetic'], 'scan.png'), 'scan.png', 'png', undefined, undefined, review);
    await vi.waitFor(() => expect(review).toHaveBeenCalledOnce());
    expect(confirmContextVision).not.toHaveBeenCalled();
    resolve({ modelId: 'fixture', consent: true, pages: [{ page: 1, masks: [] }] });
    const result = await operation;
    expect(confirmContextVision).toHaveBeenCalledOnce();
    expect(getDocumentUploadStatus).toHaveBeenCalledOnce();
    expect(result.status.phase).toBe('PARTIAL');
    expect(createDocumentUpload).toHaveBeenCalledWith(expect.objectContaining({ visionRequested: true }), undefined);
    vi.clearAllMocks();
  });
  it('should upload fixed chunks and return only a lightweight document reference', async () => {
    vi.mocked(createDocumentUpload).mockResolvedValue({
      requestId: 'create',
      data: status(),
    });
    vi.mocked(uploadDocumentChunk).mockImplementation(async (
      _documentId,
      _chunkIndex,
      chunk,
      onProgress,
    ) => {
      onProgress?.({ loaded: chunk.size } as never);
      return { requestId: 'chunk', data: status() };
    });
    vi.mocked(completeDocumentUpload).mockResolvedValue({
      requestId: 'complete',
      data: status({
        phase: 'READY',
        uploadedBytes: 2_500_000,
        progressPercent: 100,
        extractedCharacters: 2_300_000,
        chunkCount: 420,
        summary: '覆盖全文的分层摘要',
      }),
    });
    const progress = vi.fn();
    const file = new File([new Uint8Array(2_500_000)], '论文.txt', { type: 'text/plain' });

    const result = await uploadAndIndexDocument(file, '论文.txt', 'text', progress);

    expect(uploadDocumentChunk).toHaveBeenCalledTimes(3);
    expect(vi.mocked(uploadDocumentChunk).mock.calls.map((call) => call[2].size))
      .toEqual([1_000_000, 1_000_000, 500_000]);
    expect(result.file).toEqual({
      path: '论文.txt',
      language: 'text',
      content: '',
      documentId: 'document-123',
      sizeBytes: 2_500_000,
    });
    expect(progress).toHaveBeenLastCalledWith(expect.objectContaining({
      phase: 'READY',
      percent: 100,
      indexedChunks: 420,
    }));
  });
});
