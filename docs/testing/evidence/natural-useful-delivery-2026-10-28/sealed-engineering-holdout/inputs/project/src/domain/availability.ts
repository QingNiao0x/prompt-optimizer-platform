import { DeskError } from './errors';
import { remainingUnits } from './quantity';
import type { AvailabilityQuery, AvailabilityQuote, DeskSnapshot } from './types';

/** 从快照计算借用台展示与提交使用的余量，不修改快照。 */
export function availabilityFor(snapshot: DeskSnapshot, query: AvailabilityQuery): AvailabilityQuote {
  const stock = snapshot.stocks.find((row) => row.siteId === query.siteId && row.kitId === query.kitId);
  if (!stock) throw new DeskError('UNKNOWN_STOCK');

  const occupied = snapshot.loans
    .filter((loan) => loan.kitId === query.kitId && loan.status !== 'CANCELLED')
    .reduce((sum, loan) => sum + loan.quantity, 0);

  return {
    siteId: query.siteId,
    kitId: query.kitId,
    total: stock.total,
    occupied,
    available: remainingUnits(stock.total, occupied),
  };
}
