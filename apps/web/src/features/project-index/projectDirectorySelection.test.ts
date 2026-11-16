import { describe, expect, it } from 'vitest';
import { createDirectorySelection, directoryRoots, prepareDirectoryRoots, countSelectedDirectories,
  streamSelectedDirectories } from './projectDirectorySelection';
import type { DirectoryHandleLike, FileHandleLike } from './fileSystemDirectorySource';

const root = (name: string, files: Record<string, string>, ignore?: string): DirectoryHandleLike => {
  const children = Object.entries(files).map(([name, body]): FileHandleLike => ({ kind: 'file', name,
    getFile: async () => new File([body], name) }));
  return { kind: 'directory', name, async *values() { yield* children; },
    getFileHandle: async () => {
      if (ignore === undefined) throw new DOMException('No .gitignore', 'NotFoundError');
      return { kind: 'file', name: '.gitignore', getFile: async () => new File([ignore], '.gitignore') };
    } };
};

describe('多文件夹完整索引来源', () => {
  it('同名不同目录保留独立正文及固定路径，单目录兼容既有路径', async () => {
    const first = root('资料', { 'rule.ts': '甲机构末尾规则' });
    const second = root('资料', { 'rule.ts': '乙机构末尾规则' });
    const selection = createDirectorySelection([first, second]);
    expect(directoryRoots(selection).map(item => item.prefix)).toEqual(['资料', '资料 (2)']);
    const actual: Record<string, string> = {};
    for await (const entry of streamSelectedDirectories(await prepareDirectoryRoots(selection))) {
      if (entry.kind === 'file') actual[entry.path] = await entry.file.text();
    }
    expect(actual).toEqual({ '资料/rule.ts': '甲机构末尾规则', '资料 (2)/rule.ts': '乙机构末尾规则' });
    expect(createDirectorySelection([first])).toBe(first);
    expect(directoryRoots(first)[0]?.prefix).toBe('');
  });

  it('根规则互不污染，预统计与完整流对同一范围计数', async () => {
    const roots = await prepareDirectoryRoots(createDirectorySelection([
      root('甲', { 'keep.ts': '保留', 'private.ts': '甲排除' }, 'private.ts'),
      root('乙', { 'private.ts': '乙必须保留', 'drop.ts': '乙排除' }, 'drop.ts'),
    ]));
    const progress: number[] = [];
    const count = await countSelectedDirectories(roots, item => progress.push(item.discoveredFiles), () => false, 100);
    const paths: string[] = [];
    for await (const entry of streamSelectedDirectories(roots)) paths.push(entry.path);
    expect(paths).toEqual(['甲/keep.ts', '乙/private.ts']);
    expect(count).toMatchObject({ totalFiles: 2, scopeExcludedFiles: 2 });
    expect(progress.every((value, index) => index === 0 || value >= progress[index - 1]!)).toBe(true);
    const allRoots = await prepareDirectoryRoots(createDirectorySelection(roots.map(item => item.handle)), false);
    expect((await countSelectedDirectories(allRoots, () => {}, () => false, 100)).totalFiles).toBe(4);
  });

  it('总上限跨根累计，不为每个目录重置；暂停后不打开后续根', async () => {
    const roots = await prepareDirectoryRoots(createDirectorySelection([
      root('甲', { 'a.ts': 'a', 'b.ts': 'b' }), root('乙', { 'c.ts': 'c', 'd.ts': 'd' }),
    ]));
    expect((await countSelectedDirectories(roots, () => {}, () => false, 3)).totalFiles).toBe(3);
    expect((await countSelectedDirectories(roots, () => {}, () => true, 100)).totalFiles).toBe(0);
  });

  it('某根规则读取失败时整批显式失败，不放宽或跳过该根', async () => {
    const invalid = root('invalid', {});
    invalid.getFileHandle = async () => { throw new DOMException('denied', 'NotAllowedError'); };
    await expect(prepareDirectoryRoots(createDirectorySelection([root('ok', {}), invalid])))
      .rejects.toMatchObject({ name: 'NotAllowedError' });
  });
});
