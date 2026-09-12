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
