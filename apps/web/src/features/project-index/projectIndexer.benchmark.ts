import { describe, expect, it } from 'vitest';

import {
  indexProject,
  type IndexedProjectChunk,
  type IndexedProjectFile,
  type ProjectIndexRepository,
  type ProjectIndexSummary,
  type ProjectSourceEntry,
} from './projectIndexer';

const MILLION_FILES = 1_000_000;

/**
 * 这个仓库按路径即时返回已存在的元数据，用于测量增量扫描本身的吞吐量。
 * 它不会在测试进程中保留一百万条记录，因此结果不会被测试夹具的内存占用扭曲。
 */
class SyntheticIncrementalRepository implements ProjectIndexRepository {
  writtenFiles = 0;

  async beginProject(_summary: ProjectIndexSummary): Promise<void> {}

  async findFiles(projectId: string, paths: string[]): Promise<IndexedProjectFile[]> {
    return paths.map((path) => ({
      id: `${projectId}\u0000${path}`,
      projectId,
      path,
      language: 'typescript',
      size: 0,
      lastModified: 1,
      priority: 10,
      chunkCount: 0,
      metadataOnly: true,
      fingerprint: '4:0:1',
      lastSeenScanId: 'previous-scan',
      indexedCharacters: 0,
      estimatedIndexBytes: 512,
      symbols: [],
      imports: [],
    }));
  }

  async writeBatch(files: IndexedProjectFile[]): Promise<void> {
    this.writtenFiles += files.length;
  }

  async pauseProject(_summary: ProjectIndexSummary): Promise<void> {}

  async completeProject(_summary: ProjectIndexSummary): Promise<void> {}

  async removeUnseenFiles(_projectId: string, _scanId: string): Promise<number> {
    return 0;
  }

  async failProject(_projectId: string, _message: string): Promise<void> {}

  async findCandidateChunks(
    _projectId: string,
    _searchTerms: string[],
    _limit: number,
  ): Promise<IndexedProjectChunk[]> {
    return [];
  }

  async findChunksByPaths(
    _projectId: string,
    _paths: string[],
    _limit: number,
  ): Promise<IndexedProjectChunk[]> {
    return [];
  }

  async deleteProject(_projectId: string): Promise<void> {}
}

const millionUnchangedFiles = async function* (): AsyncGenerator<ProjectSourceEntry> {
  const emptyFile = new File([], 'source.ts', { lastModified: 1 });
  for (let index = 0; index < MILLION_FILES; index += 1) {
    yield {
      kind: 'file',
      path: `src/generated/module-${index}.ts`,
      file: emptyFile,
    };
  }
};

describe('million-file incremental index benchmark', () => {
  it('scans one million unchanged file records with bounded retained test data', async () => {
    const repository = new SyntheticIncrementalRepository();
    const heapBefore = process.memoryUsage().heapUsed;
    const startedAt = performance.now();

    const summary = await indexProject({
      projectId: 'benchmark-million-files',
      rootName: 'synthetic-million-files',
      mode: 'INCREMENTAL',
      entries: millionUnchangedFiles(),
      repository,
      limits: {
        maxScanFiles: MILLION_FILES,
        maxIndexBytes: Number.MAX_SAFE_INTEGER,
        maxIndexableFileBytes: 50 * 1024 * 1024,
      },
    });

    const elapsedMs = performance.now() - startedAt;
    const heapDeltaMb = (process.memoryUsage().heapUsed - heapBefore) / 1024 / 1024;
    const filesPerSecond = Math.round(MILLION_FILES / (elapsedMs / 1_000));
    console.info('[project-index benchmark]', {
      files: MILLION_FILES,
      elapsedMs: Math.round(elapsedMs),
      filesPerSecond,
      heapDeltaMb: Number(heapDeltaMb.toFixed(2)),
    });

    expect(summary.status).toBe('READY');
    expect(summary.discoveredFiles).toBe(MILLION_FILES);
    expect(summary.unchangedFiles).toBe(MILLION_FILES);
    expect(repository.writtenFiles).toBe(MILLION_FILES);
  });
});
