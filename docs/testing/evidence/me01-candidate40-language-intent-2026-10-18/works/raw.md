根据你的确认，下面是按“整行完全相同”去重、区分大小写、保留首次出现顺序、统一输出 LF 的 TypeScript 实现。

```ts
/**
 * 文本按整行去重。
 *
 * 规则：
 * - 按整行逐字符完全一致比较（区分大小写）
 * - 保留第一次出现的顺序
 * - 支持 LF 与 CRLF 输入
 * - 输出统一使用 LF
 * - 保留首次出现的空行
 */
export function dedupeLines(text: string): string {
  // 统一换行符为 LF，便于按行处理
  const normalized = text.replace(/\r\n/g, "\n");

  // 按 LF 拆分为行
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

使用示例：

```ts
const input = "apple\r\nbanana\napple\n\nbanana\n\nOrange\norange";

const output = dedupeLines(input);

console.log(JSON.stringify(output));
// "apple\nbanana\n\nOrange\norange"
```

说明：

- `apple` 第二次出现被去掉；
- 空行第一次出现被保留，第二次出现被去掉；
- `Orange` 与 `orange` 被视为不同行；
- 输入中的 `\r\n` 被统一处理，输出只包含 `\n`；
- 不引入任何第三方运行依赖。