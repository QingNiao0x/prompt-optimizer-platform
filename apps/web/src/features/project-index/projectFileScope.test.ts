import { describe, expect, it } from 'vitest';
import { createProjectFileScope, loadSelectedFileScopes, readProjectIgnore } from './projectFileScope';
import { collectCandidateFiles } from '@/workers/fileReaderCore';

const fileAt = (path: string, content = 'content') => {
  const file = new File([content], path.split('/').at(-1)!);
  Object.defineProperty(file, 'webkitRelativePath', { value: path });
  return file;
};

describe('project file scope', () => {
  it('applies root patterns and negation without unconditionally dropping tmp or docs', () => {
    const scope = createProjectFileScope('/tmp/\n**/data/analytics-journal/\ndocs/*.txt\n!docs/keep.txt');
    expect(scope.excludes('tmp', true)).toBe(true);
    expect(scope.excludes('src/temp/business.ts')).toBe(false);
    expect(scope.excludes('src/tmp/business.ts')).toBe(false);
    expect(scope.excludes('services/api/data/analytics-journal', true)).toBe(true);
    expect(scope.excludes('docs/keep.txt')).toBe(false);
    expect(scope.excludes('docs/drop.txt')).toBe(true);
    expect(scope.excludes('docs/keep.md')).toBe(false);
    expect(scope.excludes('TMP/business.ts')).toBe(false);
    expect(scope.excludes('src/new-untracked.ts')).toBe(false);
    expect(scope.excludes('build')).toBe(false);
  });

  it('shares project rules with folder FileList but does not affect explicit individual files', async () => {
    const files = [fileAt('demo/.gitignore', '/tmp/'), fileAt('demo/tmp/draft.txt'),
      fileAt('demo/src/temp/new.ts'), new File(['explicit'], 'draft.txt')];
    const scope = await loadSelectedFileScopes(files);
    const selected = await collectCandidateFiles(files, 100, undefined, scope);
    expect(selected.files.map(f => f.webkitRelativePath || f.name)).toContain('demo/src/temp/new.ts');
    expect(selected.files.map(f => f.webkitRelativePath || f.name)).toContain('draft.txt');
    expect(selected.files.map(f => f.webkitRelativePath || f.name)).not.toContain('demo/tmp/draft.txt');
    expect((await loadSelectedFileScopes(files, false))(files[1]!)).toBe(false);
  });

  it('does not count unscanned entries as known ignored files', async () => {
    const result = await collectCandidateFiles([fileAt('a.ts'), fileAt('b.ts'), fileAt('c.ts')], 1);
    expect(result.stats).toMatchObject({ total: 1, unscanned: 2, selected: 1, pathIgnored: 0 });
  });

  it('fails explicitly on oversized ignore rules', async () => {
    await expect(readProjectIgnore(new File([new Uint8Array(128 * 1024 + 1)], '.gitignore'))).rejects.toThrow('128 KiB');
  });
});
