export interface PolishDiff { kind: 'same' | 'removed' | 'added'; text: string }
/** 按行对照保留原文空白；限制矩阵大小，长段落不按字生成二次方矩阵，也不推断语义正确性。 */
export const comparePolish = (before: string, after: string): PolishDiff[] => {
  if (before === after) return [{ kind: 'same', text: after }];
  const a = before.split('\n'), b = after.split('\n');
  if (a.length > 400 || b.length > 400) return [{ kind: 'removed', text: before }, { kind: 'added', text: after }];
  const table = Array.from({ length: a.length + 1 }, () => new Uint16Array(b.length + 1));
  for (let i = a.length - 1; i >= 0; i--) for (let j = b.length - 1; j >= 0; j--) {
    table[i][j] = a[i] === b[j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1]);
  }
  const changes: PolishDiff[] = []; let i = 0, j = 0;
  while (i < a.length || j < b.length) {
    if (i < a.length && j < b.length && a[i] === b[j]) { changes.push({ kind: 'same', text: b[j++] }); i++; }
    else if (i < a.length && (j >= b.length || table[i + 1][j] >= table[i][j + 1])) changes.push({ kind: 'removed', text: a[i++] });
    else changes.push({ kind: 'added', text: b[j++] });
  }
  return changes;
};
