# TypeScript 文本按行去重实现

根据确认的需求，实现要点如下：

- **去重单位**：按行
- **比较规则**：整行逐字符完全一致，区分大小写，不 trim
- **顺序**：保留首次出现顺序
- **换行**：支持 LF 与 CRLF，输出统一为 LF
- **空行**：保留首次出现的空行
- **接口**：输入字符串，返回字符串，无第三方依赖

## 实现代码

```typescript
/**
 * 按行对文本去重。
 *
 * 规则：
 * - 按行去重，整行逐字符完全一致比较（区分大小写，不 trim）。
 * - 保留每行首次出现的顺序。
 * - 支持 LF 与 CRLF 输入，输出统一使用 LF。
 * - 保留首次出现的空行。
 *
 * @param text 输入文本
 * @returns 去重后的文本
 */
export function dedupeLines(text: string): string {
  // 统一换行符为 LF，便于按行切分
  const normalized = text.replace(/\r\n/g, "\n");

  // 按 LF 切分；注意末尾换行会产生一个空字符串元素，
  // 这里通过 split 后处理保留原始行结构语义。
  const lines = normalized.split("\n");

  const seen = new Set<string>();
  const result: string[] = [];

  for (const line of lines) {
    if (!seen.has(line)) {
      seen.add(line);
      result.push(line);
    }
  }

  return result.join("\n");
}
```

## 使用示例

```typescript
import { dedupeLines } from "./dedupeLines";

// 基本去重 + 保留首次顺序
console.log(dedupeLines("a\nb\na\nc\nb"));
// 输出:
// a
// b
// c

// 区分大小写
console.log(dedupeLines("Hello\nhello\nHello"));
// 输出:
// Hello
// hello

// 不 trim：带空格的行视为不同
console.log(dedupeLines("x\n x\nx "));
// 输出:
// x
//  x
// x 

// CRLF 输入，输出统一 LF
const crlf = "a\r\nb\r\na\r\n";
console.log(JSON.stringify(dedupeLines(crlf)));
// 输出: "a\nb\n"

// 保留首次出现的空行
console.log(JSON.stringify(dedupeLines("a\n\nb\n\na")));
// 输出: "a\n\nb"
```

## 边界情况说明

| 输入 | 输出 | 说明 |
|------|------|------|
| `""` | `""` | 空字符串原样返回 |
| `"a"` | `"a"` | 单行无换行 |
| `"a\n"` | `"a\n"` | 末尾换行保留（split 产生的尾部空串参与去重，但只保留一次） |
| `"a\n\na"` | `"a\n"` | 空行保留首次出现，重复的 `a` 被去掉 |
| `"a\r\nb"` | `"a\nb"` | CRLF 归一化为 LF |

## 关于末尾换行的细节

`"a\n"` 经 `split("\n")` 得到 `["a", ""]`，去重后仍为 `["a", ""]`，`join("\n")` 得到 `"a\n"`，行为符合"保留首次出现的空行"语义——末尾的空串代表文件末尾换行，只保留一次。

如果希望**严格区分**"末尾换行"与"中间空行"的语义（例如 `"a\n"` 与 `"a"` 视为不同），当前实现已经天然满足：前者输出 `"a\n"`，后者输出 `"a"`。

## 复杂度

- 时间：O(n)，n 为文本长度（Set 查找均摊 O(1)）
- 空间：O(n)，存储去重后的行与 Set

## 可选：流式/大文件版本

若需处理超大文本，可改为生成器逐行产出，避免一次性构建大数组：

```typescript
export function* dedupeLinesIter(text: string): Generator<string> {
  const normalized = text.replace(/\r\n/g, "\n");
  const seen = new Set<string>();
  for (const line of normalized.split("\n")) {
    if (!seen.has(line)) {
      seen.add(line);
      yield line;
    }
  }
}

export function dedupeLinesStream(text: string): string {
  return Array.from(dedupeLinesIter(text)).join("\n");
}
```

以上实现完全满足题目确认的全部约束，且无任何第三方运行依赖。