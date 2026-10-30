import { describe, expect, it } from 'vitest';
import { extractProjectPathReferences, matchesReferencedPath, referencedPathVariants } from './projectPathReferences';

describe('explicit project path references', () => {
  it('should resolve a root file only as an indexed candidate, preserving exact spans', () => {
    const query = '核对 README.md 和 package.json，随后检查 docs/说明.md';
    const result = extractProjectPathReferences(query);
    expect(result.map(item => item.path)).toEqual(['README.md', 'package.json', 'docs/说明.md']);
    expect(result.every(item => item.indexedFileCandidate)).toBe(true);
    expect(result.map(item => query.slice(item.start, item.end))).toEqual(['README.md', 'package.json', 'docs/说明.md']);
  });

  it('should recognize a complete relative file alongside an at-directory without duplicating spans', () => {
    const query = '修改 @sample/src/forms，并检查 src/shared/dateRule.ts';
    const references = extractProjectPathReferences(query);
    expect(references.map(reference => reference.path)).toEqual(['sample/src/forms', 'src/shared/dateRule.ts']);
    expect(references[1]?.indexedFileCandidate).toBe(true);
    expect(query.slice(references[1]!.start, references[1]!.end)).toBe('src/shared/dateRule.ts');
    expect(extractProjectPathReferences('修改 @src/shared/dateRule.ts')).toHaveLength(1);
  });

  it.each(['查询职业/行业', '参考 https://example.com/src/file.ts', '参考 /absolute/file.ts',
    '参考 C:\\private\\file.ts', '参考 ../outside/file.ts'])
    ('should not infer a bare project file from %s', query => {
      expect(extractProjectPathReferences(query)).toEqual([]);
    });

  it.each([
    ['模块@project/src/views/records 新增', 'project/src/views/records'],
    ['修改 @src\\views\\records\\Entry.vue', 'src/views/records/Entry.vue'],
    ['查看 @"src/forms/My Form.vue"，保持行为', 'src/forms/My Form.vue'],
    ['修改 @`./src/forms/Entry.vue`:42', 'src/forms/Entry.vue'],
    ['阅读 @README.md。', 'README.md'],
  ])('should retain the exact reference span in %s', (query, path) => {
    const result = extractProjectPathReferences(query);
    expect(result).toHaveLength(1);
    expect(result[0]?.path).toBe(path);
    const reference = result[0]!;
    expect(query.slice(reference.start, reference.end)).toContain('@');
  });

  it.each(['联系 owner@example.com', '联系 first.last@example.com',
    '参考 https://example.com?file=README.md#package.json', '读取 @../outside/file.ts', '读取 @/absolute/file.ts'])
    ('should not turn emails or external paths into project targets: %s', (query) => {
      expect(extractProjectPathReferences(query)).toEqual([]);
    });

  it('should remove only the selected root name and retain directory boundaries', () => {
    expect(referencedPathVariants('project/src/forms', 'project')).toEqual(['project/src/forms', 'src/forms']);
    expect(referencedPathVariants('other/src/forms', 'project')).toEqual(['other/src/forms']);
    expect(matchesReferencedPath('src/forms/Entry.vue', 'src/forms')).toBe(true);
    expect(matchesReferencedPath('src/forms-old/Entry.vue', 'src/forms')).toBe(false);
    expect(matchesReferencedPath('src/forms/Entry.vue.backup', 'src/forms/Entry.vue')).toBe(false);
  });
});
