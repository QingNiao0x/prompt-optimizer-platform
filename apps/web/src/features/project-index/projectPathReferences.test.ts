import { describe, expect, it } from 'vitest';
import { extractProjectPathReferences, matchesReferencedPath, referencedPathVariants } from './projectPathReferences';

describe('explicit project path references', () => {
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

  it.each(['联系 owner@example.com', '读取 @../outside/file.ts', '读取 @/absolute/file.ts'])
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
