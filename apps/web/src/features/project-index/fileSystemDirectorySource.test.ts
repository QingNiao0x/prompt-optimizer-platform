import { describe, expect, it } from 'vitest';

import {
  countDirectoryEntries,
  streamDirectoryEntries,
  type DirectoryHandleLike,
  type FileSystemHandleLike,
} from './fileSystemDirectorySource';

const fileHandle = (name: string, content: string): FileSystemHandleLike => ({
  kind: 'file',
  name,
  getFile: async () => new File([content], name, { type: 'text/plain' }),
});

const directoryHandle = (
  name: string,
  children: FileSystemHandleLike[],
): DirectoryHandleLike => ({
  kind: 'directory',
  name,
  async *values() {
    for (const child of children) {
      yield child;
    }
  },
});

describe('streamDirectoryEntries', () => {
  it.each(['.m2', '.npm', '.npm-cache', '_cacache', '.pnpm-store', '.NPM-CACHE'])
    ('should prune dependency cache %s without opening its entries', async (name) => {
      let cacheTraversals = 0;
      const cache: DirectoryHandleLike = {
        kind: 'directory', name,
        async *values() {
          cacheTraversals += 1;
          yield fileHandle('package.json', '{"name":"cached-dependency"}');
        },
      };
      const root = directoryHandle('demo', [
        directoryHandle('apps', [directoryHandle('web', [cache])]),
        fileHandle('pom.xml', '<project/>'),
        directoryHandle('docs', [fileHandle('cache-design.md', '业务缓存方案')]),
        directoryHandle('business-cache', [fileHandle('rules.txt', '业务规则')]),
      ]);
      const entries = [];
      for await (const entry of streamDirectoryEntries(root)) entries.push(entry);

      expect(cacheTraversals).toBe(0);
      expect(entries.filter((entry) => entry.kind === 'file').map((entry) => entry.path))
        .toEqual(['pom.xml', 'docs/cache-design.md', 'business-cache/rules.txt']);
      expect(await countDirectoryEntries(root)).toEqual({ totalFiles: 3, ignoredDirectories: 1 });
      expect(cacheTraversals).toBe(0);
    });

  it('should skip generated reports while still traversing user documents', async () => {
    const root = directoryHandle('demo', [
      directoryHandle('playwright-report', [fileHandle('index.html', 'generated report')]),
      directoryHandle('.codegraph', [fileHandle('index.db', 'generated index')]),
      directoryHandle('docs', [fileHandle('plan.md', '业务方案'), fileHandle('rules.txt', '规则')]),
    ]);
    const entries = [];
    for await (const entry of streamDirectoryEntries(root)) entries.push(entry);
    expect(entries.filter((entry) => entry.kind === 'file').map((entry) => entry.path))
      .toEqual(['docs/plan.md', 'docs/rules.txt']);
  });
  it('should traverse nested source files and skip dependency directories before reading them', async () => {
    let dependencyDirectoryVisited = false;
    const dependencyDirectory: DirectoryHandleLike = {
      kind: 'directory',
      name: 'node_modules',
      async *values() {
        dependencyDirectoryVisited = true;
        yield fileHandle('library.js', 'third party');
      },
    };
    const root = directoryHandle('demo', [
      directoryHandle('src', [fileHandle('main.ts', 'export const main = true;')]),
      dependencyDirectory,
      fileHandle('package.json', '{}'),
    ]);

    const entries = [];
    for await (const entry of streamDirectoryEntries(root)) {
      entries.push(entry);
    }

    expect(entries).toEqual([
      expect.objectContaining({ kind: 'file', path: 'src/main.ts' }),
      { kind: 'ignored-directory', path: 'node_modules' },
      expect.objectContaining({ kind: 'file', path: 'package.json' }),
    ]);
    expect(dependencyDirectoryVisited).toBe(false);
  });

  it('should count files without entering ignored dependency directories', async () => {
    let dependencyDirectoryVisited = false;
    const dependencyDirectory: DirectoryHandleLike = {
      kind: 'directory',
      name: 'node_modules',
      async *values() {
        dependencyDirectoryVisited = true;
        yield fileHandle('library.js', 'third party');
      },
    };
    const progress: number[] = [];
    const root = directoryHandle('demo', [
      directoryHandle('src', [fileHandle('main.ts', 'export const main = true;')]),
      dependencyDirectory,
      fileHandle('package.json', '{}'),
    ]);

    const summary = await countDirectoryEntries(root, (value) => {
      progress.push(value.discoveredFiles);
    });

    expect(summary).toEqual({ totalFiles: 2, ignoredDirectories: 1 });
    expect(progress.at(-1)).toBe(2);
    expect(dependencyDirectoryVisited).toBe(false);
  });

  it('should read file metadata with bounded concurrency while preserving directory order', async () => {
    let activeReads = 0;
    let maximumActiveReads = 0;
    const children: FileSystemHandleLike[] = Array.from({ length: 12 }, (_, index) => ({
      kind: 'file' as const,
      name: `file-${index}.ts`,
      getFile: async (): Promise<File> => {
        activeReads += 1;
        maximumActiveReads = Math.max(maximumActiveReads, activeReads);
        await new Promise((resolve) => setTimeout(resolve, 1));
        activeReads -= 1;
        return new File([`export const value${index} = ${index};`], `file-${index}.ts`);
      },
    }));

    const entries = [];
    for await (const entry of streamDirectoryEntries(directoryHandle('demo', children))) {
      entries.push(entry);
    }

    expect(entries.map((entry) => entry.path)).toEqual(
      children.map((child) => child.name),
    );
    expect(maximumActiveReads).toBeGreaterThan(1);
    expect(maximumActiveReads).toBeLessThanOrEqual(6);
  });
});
