import { describe, expect, it } from 'vitest';

import { collectDroppedFiles } from './fileDrop';

describe('collectDroppedFiles', () => {
  it('should accept a directly dropped file without navigating', async () => {
    const file = new File(['report'], 'report.txt', { type: 'text/plain' });
    const dataTransfer = createDataTransfer([
      { kind: 'file', webkitGetAsEntry: () => createFileEntry(file) },
    ], [file]);

    const result = await collectDroppedFiles(dataTransfer);

    expect(result.hasDirectory).toBe(false);
    expect(result.files).toHaveLength(1);
    expect(result.files[0]?.name).toBe('report.txt');
  });

  it('should recursively read a dropped directory and preserve relative paths', async () => {
    const mainFile = new File(['export const main = true;'], 'main.ts', { type: 'text/plain' });
    const readmeFile = new File(['# Demo'], 'README.md', { type: 'text/markdown' });
    const root = createDirectoryEntry('demo', [
      createDirectoryEntry('src', [createFileEntry(mainFile)]),
      createFileEntry(readmeFile),
    ]);
    const dataTransfer = createDataTransfer([
      { kind: 'file', webkitGetAsEntry: () => root },
    ], []);

    const result = await collectDroppedFiles(dataTransfer);

    expect(result.hasDirectory).toBe(true);
    expect(result.files.map((file) => file.webkitRelativePath)).toEqual([
      'demo/src/main.ts',
      'demo/README.md',
    ]);
  });
});

const createDataTransfer = (items: unknown[], files: File[]): DataTransfer => ({
  items,
  files,
} as unknown as DataTransfer);

const createFileEntry = (file: File) => ({
  isFile: true as const,
  isDirectory: false as const,
  name: file.name,
  file: (resolve: (value: File) => void) => resolve(file),
});

const createDirectoryEntry = (name: string, entries: unknown[]) => {
  let read = false;
  return {
    isFile: false as const,
    isDirectory: true as const,
    name,
    createReader: () => ({
      readEntries: (resolve: (value: unknown[]) => void) => {
        resolve(read ? [] : entries);
        read = true;
      },
    }),
  };
};
