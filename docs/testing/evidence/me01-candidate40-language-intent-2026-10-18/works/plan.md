# 整行去重 TypeScript 实现

## 实现代码

```typescript
/**
 * 按整行去重：整行逐字符完全一致（区分大小写）视为重复，
 * 保留首次出现的行及其顺序；支持 LF / CRLF，输出统一 LF；
 * 保留首次出现的空行。
 *
 * @param input 待去重文本
 * @returns 去重后的文本（LF 换行）
 * @throws TypeError 当 input 不是字符串时
 */
export function dedupeLines(input: string): string {
  if (typeof input !== "string") {
    throw new TypeError(
      `dedupeLines: expected a string, received ${input === null ? "null" : typeof input}`
    );
  }

  // 空字符串直接返回空字符串（无行可去重）
  if (input.length === 0) {
    return "";
  }

  // 统一换行：先 CRLF -> LF，再处理孤立 CR（视为换行，避免残留 \r 影响比较）
  const normalized = input.replace(/\r\n/g, "\n").replace(/\r/g, "\n");

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

### 设计说明

- **换行归一化**：`\r\n` → `\n`，孤立 `\r` 也视为换行（避免 Windows 老式换行残留 `\r` 造成误判）。归一化后按 `\n` 切分。
- **整行比较**：使用 `Set<string>` 做精确字符串匹配，天然区分大小写、保留原字符。
- **空行**：空字符串 `""` 作为合法行参与去重，首次出现的空行被保留，后续空行被去除。
- **顺序**：按遍历顺序 push，天然保留首次出现顺序。
- **尾随换行**：`"a\n"` 切分为 `["a", ""]`，末尾空行会被保留（首次出现）。这是"按行"语义的自然结果；若业务上不希望保留末尾空行，需在调用方 trim 或另行约定——此处按"保留首次出现的空行"规则处理。
- **异常**：非字符串输入抛 `TypeError`，错误信息不含输入内容（避免泄露敏感值）。

## 运行说明

- 无第三方运行依赖，仅用标准库。
- 目标环境需支持 `Set`（ES2015+）。
- 编译：`tsc dedupe.ts --target es2019 --module commonjs`，或直接在支持 TS 的运行时（如 ts-node / Deno / Bun）中运行。
- 调用示例：

```typescript
import { dedupeLines } from "./dedupe";

dedupeLines("a\r\nb\na\nb"); // "a\nb"
```

## 测试

使用 Node 内置 `node:test` + `node:assert`（无第三方依赖）。文件 `dedupe.test.ts`：

```typescript
import { test } from "node:test";
import assert from "node:assert/strict";
import { dedupeLines } from "./dedupe";

// ---------- 正常场景 ----------
test("整行完全一致去重，保留首次顺序", () => {
  assert.equal(dedupeLines("a\nb\na\nc\nb"), "a\nb\nc");
});

test("区分大小写", () => {
  assert.equal(dedupeLines("A\na\nA"), "A\na");
});

test("LF 与 CRLF 混合输入，输出统一 LF", () => {
  assert.equal(dedupeLines("a\r\nb\na\nb\r\nc"), "a\nb\nc");
});

test("保留首次出现的空行", () => {
  assert.equal(dedupeLines("a\n\nb\n\nc"), "a\n\nb\nc");
});

test("仅空行输入", () => {
  assert.equal(dedupeLines("\n\n\n"), "\n");
});

test("前导空行保留", () => {
  assert.equal(dedupeLines("\na\n\na"), "\na");
});

test("行内空格差异视为不同行", () => {
  assert.equal(dedupeLines("a b\na  b\na b"), "a b\na  b");
});

test("行内容原样保留（含制表符、Unicode）", () => {
  assert.equal(dedupeLines("x\ty\nx\ty\n中文\n中文"), "x\ty\n中文");
});

// ---------- 边界场景 ----------
test("空字符串返回空字符串", () => {
  assert.equal(dedupeLines(""), "");
});

test("单行无换行", () => {
  assert.equal(dedupeLines("only"), "only");
});

test("单行带尾随换行", () => {
  assert.equal(dedupeLines("only\n"), "only\n");
});

test("全部重复行", () => {
  assert.equal(dedupeLines("a\na\na"), "a");
});

test("孤立 CR 视为换行", () => {
  assert.equal(dedupeLines("a\rb\ra"), "a\nb");
});

// ---------- 异常场景 ----------
test("null 抛 TypeError", () => {
  assert.throws(() => dedupeLines(null as unknown as string), TypeError);
});

test("undefined 抛 TypeError", () => {
  assert.throws(() => dedupeLines(undefined as unknown as string), TypeError);
});

test("数字抛 TypeError", () => {
  assert.throws(() => dedupeLines(123 as unknown as string), TypeError);
});

test("对象抛 TypeError", () => {
  assert.throws(() => dedupeLines({} as unknown as string), TypeError);
});

test("错误信息不含输入内容", () => {
  try {
    dedupeLines({ secret: "token-abc" } as unknown as string);
    assert.fail("should have thrown");
  } catch (e) {
    assert.ok(e instanceof TypeError);
    assert.ok(!String((e as Error).message).includes("token-abc"));
  }
});
```

运行：

```bash
node --test --experimental-strip-types dedupe.test.ts
# 或先编译再运行
tsc dedupe.ts dedupe.test.ts --target es2019 --module commonjs
node --test dedupe.test.js
```

## 实际运行结果说明

**如实声明**：本次回答在文本环境中生成，**未实际执行上述测试代码**，因此不能声称测试"已通过"。上述测试为可运行的用例设计，覆盖了：

- 正常：整行去重、大小写区分、LF/CRLF 混合、空行保留、顺序保持、行内空格差异、Unicode/制表符原样保留。
- 边界：空字符串、单行、尾随换行、全重复、孤立 CR。
- 异常：`null` / `undefined` / 数字 / 对象输入抛 `TypeError`，且错误信息不含输入内容。

请在本地按上述命令运行以确认结果。

## 已知事实 / 假设 / 限制

- **已知事实**：实现仅用标准库；按整行精确匹配；输出 LF；保留首次出现顺序与首次空行。
- **假设**：孤立 `\r` 视为换行（常见文本语义）；末尾换行产生的空行按"首次出现空行"规则保留。若业务要求去除末尾空行，需在调用方处理或调整实现。
- **限制**：未做超长文本的内存优化（`split` 会整体驻留内存）；对超大文件建议流式处理，本实现面向字符串入参。