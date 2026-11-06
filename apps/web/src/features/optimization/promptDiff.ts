export interface PromptChange {
  kind: 'unchanged' | 'added' | 'removed';
  text: string;
}

/** 合并相邻同类片段，保留原文空格与换行，供差异展示及原稿核对。 */
const append = (changes: PromptChange[], kind: PromptChange['kind'], text: string): void => {
  if (!text) return;
  const last = changes.at(-1);
  if (last?.kind === kind) last.text += text;
  else changes.push({ kind, text });
};

/** 长文本采用线性前后缀比较，中间完整展示，避免大矩阵卡住工作台或截掉改动。 */
const compareBoundaries = (before: string, after: string): PromptChange[] => {
  let prefix = 0;
  while (prefix < before.length && prefix < after.length && before[prefix] === after[prefix]) prefix++;
  // UTF-16 边界不能拆开 emoji 等补充平面字符。
  if (prefix > 0 && /[\uD800-\uDBFF]/.test(before[prefix - 1]!)) prefix--;
  let suffix = 0;
  while (suffix < before.length - prefix && suffix < after.length - prefix
    && before[before.length - 1 - suffix] === after[after.length - 1 - suffix]) suffix++;
  if (suffix > 0 && /[\uDC00-\uDFFF]/.test(before[before.length - suffix]!)) suffix--;
  const changes: PromptChange[] = [];
  append(changes, 'unchanged', before.slice(0, prefix));
  append(changes, 'removed', before.slice(prefix, before.length - suffix));
  append(changes, 'added', after.slice(prefix, after.length - suffix));
  append(changes, 'unchanged', before.slice(before.length - suffix));
  return changes;
};

/**
 * 对保存稿做确定性的文本比较。正常段落保留中间未改行，大文本回退为完整区块对照。
 * 仅展示文字变化，不把差异大小或无差异视为模型质量结论。
 */
export const comparePromptText = (before: string, after: string): PromptChange[] => {
  if (before === after) return before ? [{ kind: 'unchanged', text: before }] : [];
  const oldLines = before.split(/(?<=\n)/);
  const newLines = after.split(/(?<=\n)/);
  const width = newLines.length + 1;
  const estimatedTextWork = (before.length + after.length) * Math.min(oldLines.length, newLines.length);
  if ((oldLines.length + 1) * width > 250_000 || estimatedTextWork > 8_000_000)
    return compareBoundaries(before, after);
  const lengths = new Uint32Array((oldLines.length + 1) * width);
  for (let oldIndex = oldLines.length - 1; oldIndex >= 0; oldIndex--) {
    for (let newIndex = newLines.length - 1; newIndex >= 0; newIndex--) {
      lengths[oldIndex * width + newIndex] = oldLines[oldIndex] === newLines[newIndex]
        ? lengths[(oldIndex + 1) * width + newIndex + 1]! + 1
        : Math.max(lengths[(oldIndex + 1) * width + newIndex]!, lengths[oldIndex * width + newIndex + 1]!);
    }
  }
  const changes: PromptChange[] = [];
  let oldIndex = 0;
  let newIndex = 0;
  while (oldIndex < oldLines.length && newIndex < newLines.length) {
    if (oldLines[oldIndex] === newLines[newIndex]) {
      append(changes, 'unchanged', oldLines[oldIndex++]!);
      newIndex++;
    } else if (lengths[(oldIndex + 1) * width + newIndex]! >= lengths[oldIndex * width + newIndex + 1]!) {
      append(changes, 'removed', oldLines[oldIndex++]!);
    } else append(changes, 'added', newLines[newIndex++]!);
  }
  append(changes, 'removed', oldLines.slice(oldIndex).join(''));
  append(changes, 'added', newLines.slice(newIndex).join(''));
  return changes;
};
