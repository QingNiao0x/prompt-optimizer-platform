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