import { describe, expect, it, vi } from 'vitest';

vi.mock('@/services/documentUploadApi', () => ({
  createDocumentUpload: vi.fn(),
  uploadDocumentChunk: vi.fn(),
  completeDocumentUpload: vi.fn(),
  getDocumentUploadStatus: vi.fn(),
  deleteDocumentUpload: vi.fn(),
}));

import { uploadAndIndexDocument } from './documentUploader';
import {
  completeDocumentUpload,
  createDocumentUpload,
  uploadDocumentChunk,
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
