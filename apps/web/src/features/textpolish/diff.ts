export interface PolishDiff { kind: 'same' | 'removed' | 'added'; text: string }

/** 合并相邻同类片段，保留原始空格、换行和Unicode字符。 */
const append = (changes: PolishDiff[], kind: PolishDiff['kind'], text: string): void => {
  if (!text) return;
  const previous = changes.at(-1);
  if (previous?.kind === kind) previous.text += text;
  else changes.push({ kind, text });
};

/** 裁掉公共前后缀后作有界LCS，长文预算耗尽时展示剩余整块，避免阻塞页面。 */
const compareTokens = (a: string[], b: string[], budget: { cells: number }): PolishDiff[] => {
  let start = 0;
  while (start < a.length && start < b.length && a[start] === b[start]) start++;
  let endA = a.length, endB = b.length;
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) { endA--; endB--; }
  const changes: PolishDiff[] = [];
  append(changes, 'same', a.slice(0, start).join(''));
  const rows = endA - start, columns = endB - start;
  const cells = (rows + 1) * (columns + 1);
  if (!rows || !columns || cells > budget.cells) {
    append(changes, 'removed', a.slice(start, endA).join(''));
    append(changes, 'added', b.slice(start, endB).join(''));
  } else {
    budget.cells -= cells;
    const table = Array.from({ length: rows + 1 }, () => new Uint32Array(columns + 1));
    for (let i = rows - 1; i >= 0; i--) {
      for (let j = columns - 1; j >= 0; j--) {
        table[i][j] = a[start + i] === b[start + j]
          ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1]);
      }
    }
    let i = 0, j = 0;
    while (i < rows || j < columns) {
      if (i < rows && j < columns && a[start + i] === b[start + j]) {
        append(changes, 'same', a[start + i]); i++; j++;
      } else if (i < rows && (j === columns || table[i + 1][j] >= table[i][j + 1])) {
        append(changes, 'removed', a[start + i++]);
      } else append(changes, 'added', b[start + j++]);
    }
  }
  append(changes, 'same', a.slice(endA).join(''));
  return changes;
};

/** 按句子和换行对齐，再细化修改块。全次计算共享预算，差异仅说明文字变化。 */
export const comparePolish = (before: string, after: string): PolishDiff[] => {
  const budget = { cells: 500_000 };
  const sentences = (text: string): string[] => text.match(/[^。！？\n]+[。！？]?|[。！？\n]/gu) ?? [];
  const blocks = compareTokens(sentences(before), sentences(after), budget);
  const changes: PolishDiff[] = [];
  for (let i = 0; i < blocks.length; i++) {
    const block = blocks[i];
    if (block.kind === 'removed' && blocks[i + 1]?.kind === 'added') {
      for (const part of compareTokens(Array.from(block.text), Array.from(blocks[++i].text), budget)) {
        append(changes, part.kind, part.text);
      }
    } else append(changes, block.kind, block.text);
  }
  return changes;
};
