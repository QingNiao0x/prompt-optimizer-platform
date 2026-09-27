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

it('indexes source and discovers office documents in one directory traversal', async () => {
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
  expect(traversals).toBe(1);
  expect(getSource).toHaveBeenCalledOnce();
  expect(getDocument).toHaveBeenCalledOnce();
  expect(scope.postMessage).toHaveBeenLastCalledWith(expect.objectContaining({
    type: 'done', summary: expect.objectContaining({ indexedFiles: 1 }),
    documents: [expect.objectContaining({ path: 'report.pdf' })],
  }));
});
