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

  return [...totals.entries()]
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([sku, delta]) => ({ sku, delta }));
}
```

实现说明：

- 沿用现有导入、导出名与 `StockSummary[]` 返回结构，未新增参数、接口或依赖。
- 每笔 `delta` 先校验 `Number.isFinite` 与 `Number.isInteger`，不满足时抛 `RangeError('delta must be finite integer')`。
- 物品标识统一经 `normalizeSku` 归一化，空 sku 的错误自然沿用其原有 `Error('sku is required')`。
- 用 `Map` 按归一化 sku 合并 `delta`，汇总为 0 的条目仍保留。
- 结果按 sku 的 ASCII 升序排序，返回全新数组与全新对象，未修改 `entries` 或其元素。