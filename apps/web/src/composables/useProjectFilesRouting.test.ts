import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useProjectFiles } from './useProjectFiles';
import { uploadAndIndexDocument } from '@/features/document-upload/documentUploader';

vi.mock('@/features/document-upload/documentUploader', () => ({ uploadAndIndexDocument: vi.fn() }));
vi.mock('@/services/documentUploadApi', () => ({ deleteDocumentUpload: vi.fn() }));

describe('document upload routing', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(uploadAndIndexDocument).mockImplementation(async (file, path, language) => ({
      file: { path, language, content: '', documentId: `id-${path}`, sizeBytes: file.size },
      status: {
        documentId: `id-${path}`, path, language, phase: 'READY', fileSizeBytes: file.size,
        uploadedBytes: file.size, progressPercent: 100, extractedCharacters: file.size,
        chunkCount: 1, summary: '', warnings: [], errorMessage: '', expiresAt: '2099-01-01T00:00:00Z',
        chunkSizeBytes: 1_048_576,
      },
    }));
  });

  it('should protect document tails when many small documents exceed the inline total budget', async () => {
    // 每个文件都小于 64,000 字符，但总量超过 Worker 的 4,000,000 字符预算。
    const documents = Array.from({ length: 70 }, (_, index) => new File(['a'.repeat(60_000)], `文档-${index}.txt`));
    const state = useProjectFiles();
    const result = await state.selectFileArray(documents);
    expect(result).toHaveLength(70);
    expect(uploadAndIndexDocument).toHaveBeenCalledTimes(70);
    expect(result.every((file) => Boolean(file.documentId))).toBe(true);
    expect(state.warnings.value).toEqual([]);
  });

  it('should upload nested office documents and reject protected entries before invoking the uploader', async () => {
    const paths = ['资料/方案.txt', '资料/子目录/方案.docx', '资料/报告.pdf', '资料/.env', '资料/id_rsa'];
    const documents = paths.map((path) => {
      const file = new File(['中'.repeat(70_000)], path.split('/').at(-1)!);
      Object.defineProperty(file, 'webkitRelativePath', { value: path });
      return file;
    });
    const state = useProjectFiles();
    const result = await state.selectFileArray(documents);
    expect(result.map((file) => file.path)).toEqual(paths.slice(0, 3));
    expect(vi.mocked(uploadAndIndexDocument).mock.calls.map((call) => call[1])).toEqual(paths.slice(0, 3));
    expect(state.warnings.value).toContain('已跳过 2 个敏感文件（密钥、私钥、.env 或凭据文件），不会上传。');
  });
});
