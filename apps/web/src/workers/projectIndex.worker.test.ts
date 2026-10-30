import { afterEach, expect, it, vi } from 'vitest';
import type { DirectoryHandleLike } from '@/features/project-index/fileSystemDirectorySource';

const repository = vi.hoisted(() => ({
  beginProject: vi.fn(async () => undefined),
  writeBatch: vi.fn(async () => undefined),
  completeProject: vi.fn(async () => undefined),
  removeUnseenFiles: vi.fn(async () => 0),
  failProject: vi.fn(async () => undefined),
}));
vi.mock('@/features/project-index/indexedDbProjectIndexRepository', () => ({ projectIndexRepository: repository }));

afterEach(() => vi.unstubAllGlobals());

it('pre-counts handles then reads each file once and reports completion after persistence', async () => {
  let traversals = 0;
  const getSource = vi.fn(async () => new File(['审批阈值：五万元'], 'rules.txt'));
  const getDocument = vi.fn(async () => new File(['synthetic-pdf'], 'report.pdf', { type: 'application/pdf' }));
  const root: DirectoryHandleLike = {
    kind: 'directory', name: 'mixed',
    async *values() {
      traversals += 1;
      yield { kind: 'file', name: 'rules.txt', getFile: getSource };
      yield { kind: 'file', name: 'report.pdf', getFile: getDocument };
    },
  };
  const scope = {
    onmessage: null as ((event: MessageEvent) => Promise<void>) | null,
    postMessage: vi.fn(),
  };
  vi.stubGlobal('self', scope);
  await import('./projectIndex.worker');
  await scope.onmessage?.(new MessageEvent('message', { data: {
    type: 'start', projectId: 'single-pass', rootHandle: root, mode: 'FULL',
    retention: 'SESSION', sessionId: 'test', expiresAt: '2099-01-01T00:00:00Z',
    limits: { maxScanFiles: 100, maxIndexBytes: 10_000_000, maxIndexableFileBytes: 1_000_000 },
  } }));
  expect(traversals).toBe(2);
  expect(getSource).toHaveBeenCalledOnce();
  expect(getDocument).toHaveBeenCalledOnce();
  const progress = scope.postMessage.mock.calls.map(([message]) => message)
    .filter(message => message.type === 'progress').map(message => message.progress);
  expect(progress.some(value => value.phase === 'SCANNING' && value.percent === undefined)).toBe(true);
  expect(progress.filter(value => value.phase === 'INDEXING').every(value => value.totalFiles === 2 && value.percent < 100)).toBe(true);
  expect(progress.at(-1)).toMatchObject({ phase: 'COMPLETED', totalFiles: 2, processedFiles: 2, percent: 100, indexedFiles: 1, ignoredFiles: 1 });
  expect(scope.postMessage).toHaveBeenLastCalledWith(expect.objectContaining({
    type: 'done', summary: expect.objectContaining({ indexedFiles: 1 }),
    documents: [expect.objectContaining({ path: 'report.pdf' })],
  }));
});
