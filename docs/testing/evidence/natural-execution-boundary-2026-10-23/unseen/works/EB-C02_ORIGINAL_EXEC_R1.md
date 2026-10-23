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
    totals.set(sku, (totals.get(sku) ?? 0) + entry.delta);
  }

  return Array.from(totals, ([sku, delta]) => ({ sku, delta })).sort((a, b) =>
    a.sku < b.sku ? -1 : a.sku > b.sku ? 1 : 0,
  );
}
```

实现说明：

- 沿用 `StockMovement` / `StockSummary` 类型与 `normalizeSku`，未新增接口、参数或依赖。
- 每笔 `delta` 先校验 `Number.isFinite` 且 `Number.isInteger`，不满足即抛 `RangeError('delta must be finite integer')`。
- 用 `Map` 按归一化后的 sku 合并 `delta`；汇总为 0 的条目仍保留在结果中。
- 返回新数组，元素为新对象，按 sku 的 ASCII 升序排序；不修改 `entries` 或其元素。
- 空 sku 的错误由 `normalizeSku` 原样抛出。