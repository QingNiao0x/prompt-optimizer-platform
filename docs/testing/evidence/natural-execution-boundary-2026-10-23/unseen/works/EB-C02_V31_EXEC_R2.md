```ts
import type { StockMovement, StockSummary } from './types';
import { normalizeSku } from './normalizeSku';

export function summarizeStock(entries: readonly StockMovement[]): StockSummary[] {
  const totals = new Map<string, number>();

  for (const entry of entries) {
    if (!Number.isFinite(entry.delta) || !Number.isInteger(entry.delta)) {
      throw new RangeError('delta must be finite integer');
    }

    const sku = normalizeSku(entry.sku);
    const current = totals.get(sku);
    totals.set(sku, current === undefined ? entry.delta : current + entry.delta);
  }

  return Array.from(totals, ([sku, delta]) => ({ sku, delta })).sort((a, b) =>
    a.sku < b.sku ? -1 : a.sku > b.sku ? 1 : 0,
  );
}
```

说明：
- 沿用 `normalizeSku` 做物品标识归一化，空 sku 的错误由其原有逻辑抛出。
- 每笔 `delta` 先校验为有限整数，否则抛固定消息的 `RangeError`。
- 用 `Map` 按归一化后的 sku 合并，汇总为 0 的条目自然保留。
- 返回新数组与新对象，不修改 `entries` 及其元素。
- 按 sku 的 ASCII 升序排序（用 `<`/`>` 比较，避免 `localeCompare` 的区域差异）。
- 未新增接口、参数或依赖，导出名保持不变。