import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';

import type { ProjectIndexSummary } from '@/features/project-index/projectIndexer';

import { useOptimizationStore } from './optimization';

const readyIndex = (): ProjectIndexSummary => ({
  id: 'same-project',
  rootName: 'sample-project',
  status: 'READY',
  discoveredFiles: 2,
  eligibleFiles: 2,
  indexedFiles: 2,
  ignoredFiles: 0,
  ignoredDirectories: 0,
  metadataOnlyFiles: 0,
  failedFiles: 0,
  addedFiles: 2,
  updatedFiles: 0,
  unchangedFiles: 0,
  removedFiles: 0,
  changedPaths: [],
  chunkCount: 2,
  indexedCharacters: 100,
  estimatedIndexBytes: 2_000,
  scanLimitReached: false,
  storageLimitReached: false,
  retention: 'SESSION',
  sessionId: 'test-session',
  expiresAt: '2099-01-01T00:00:00.000Z',
  scanId: 'scan-1',
  lastCheckpointPath: 'src/main.ts',
  startedAt: '2026-08-30T00:00:00.000Z',
  completedAt: '2026-08-30T00:00:01.000Z',
});

describe('optimization store project context', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('should retain a manually supplied active file after refreshing the same index', () => {
    const store = useOptimizationStore();
    store.setProjectIndex(readyIndex());
    store.addFile({
      path: 'src/main.ts',
      language: 'typescript',
      content: 'export const active = true;',
    });

    store.setProjectIndex({
      ...readyIndex(),
      updatedFiles: 1,
      changedPaths: ['src/main.ts'],
      scanId: 'scan-2',
    });

    expect(store.files).toHaveLength(1);
    expect(store.activeFilePath).toBe('src/main.ts');
  });
});
