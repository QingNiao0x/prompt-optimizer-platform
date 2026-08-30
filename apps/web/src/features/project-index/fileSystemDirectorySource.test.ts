import { describe, expect, it } from 'vitest';

import {
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
});
