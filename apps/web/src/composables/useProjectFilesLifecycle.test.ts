import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/features/document-upload/documentUploader', () => ({ uploadAndIndexDocument: vi.fn() }));
vi.mock('@/services/documentUploadApi', () => ({ deleteDocumentUpload: vi.fn() }));

import { uploadAndIndexDocument } from '@/features/document-upload/documentUploader';
import { deleteDocumentUpload } from '@/services/documentUploadApi';
import { useProjectFiles } from './useProjectFiles';

class PendingWorker {
  static instances: PendingWorker[] = [];
  onmessage?: (event: { data: unknown }) => void;
  onerror?: (event: { message: string }) => void;
  terminate = vi.fn();
  postMessage = vi.fn();
  constructor() { PendingWorker.instances.push(this); }
}

describe('P0 selected-file lifecycle', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    PendingWorker.instances = [];
    vi.stubGlobal('Worker', PendingWorker);
    vi.mocked(deleteDocumentUpload).mockResolvedValue(undefined);
  });

  it('terminates the old reader and cannot clear a new reader when its callback arrives late', async () => {
    const reader = useProjectFiles();
    const a = reader.selectFileArray([new File(['A'], 'A.txt')]);
    await vi.waitFor(() => expect(PendingWorker.instances).toHaveLength(1));
    reader.reset();
    const b = reader.selectFileArray([new File(['B'], 'B.txt')]);
    await vi.waitFor(() => expect(PendingWorker.instances).toHaveLength(2));
    expect(PendingWorker.instances[0]?.terminate).toHaveBeenCalledOnce();
    PendingWorker.instances[0]?.onmessage?.({ data: { type: 'done', warnings: ['OLD_A_WARNING'],
      files: [{ path: 'A.txt', language: 'text', content: 'A' }] } });
    expect(await a).toEqual([]);
    expect(reader.isReading.value).toBe(true);
    expect(reader.warnings.value).toEqual([]);
    PendingWorker.instances[1]?.onmessage?.({ data: { type: 'done', warnings: [],
      files: [{ path: 'B.txt', language: 'text', content: 'B' }] } });
    expect((await b).map(file => file.path)).toEqual(['B.txt']);
    expect(reader.isReading.value).toBe(false);
  });

  it('cleans up a document completed after reset without returning its reference or old warnings', async () => {
    let finish!: (value: Awaited<ReturnType<typeof uploadAndIndexDocument>>) => void;
    vi.mocked(uploadAndIndexDocument).mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
    const reader = useProjectFiles();
    const pending = reader.selectFileArray([new File(['%PDF synthetic'], 'A.pdf')]);
    await vi.waitFor(() => expect(uploadAndIndexDocument).toHaveBeenCalledOnce());
    const signal = vi.mocked(uploadAndIndexDocument).mock.calls[0]?.[4];
    reader.reset();
    expect(signal?.aborted).toBe(true);
    finish({ file: { path: 'A.pdf', language: 'pdf', content: '', documentId: 'old-a' },
      status: { documentId: 'old-a', path: 'A.pdf', language: 'pdf', fileSizeBytes: 14, phase: 'READY',
        uploadedBytes: 14, progressPercent: 100, extractedCharacters: 1, chunkCount: 1, summary: '',
        warnings: ['OLD_A_WARNING'], errorMessage: '', expiresAt: '2099', chunkSizeBytes: 1024 } });
    expect(await pending).toEqual([]);
    expect(deleteDocumentUpload).toHaveBeenCalledWith('old-a');
    expect(reader.warnings.value).toEqual([]);
    expect(reader.isReading.value).toBe(false);
  });
});
