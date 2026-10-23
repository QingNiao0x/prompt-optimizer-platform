```ts
// src/summarizeStock.ts
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

说明：
- 复用 `normalizeSku` 归一化 sku，空 sku 的错误行为沿用其原有实现。
- 先校验每笔 `delta` 是否为有限整数，不满足时抛 `RangeError('delta must be finite integer')`。
- 用 `Map` 按归一化后的 sku 合并 `delta`，汇总为 0 的项也保留。
- 返回新数组，按 sku 的 ASCII 升序排序；不修改 `entries` 及其元素。
- 未新增接口、参数或依赖，导出名保持不变。