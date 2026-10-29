import { DeskError } from './errors';

/** 对业务数量做集中检查，表单和直接调用都经这里进入。 */
export function assertRequestedQuantity(quantity: number): void {
  if (!Number.isFinite(quantity) || quantity < 1) {
    throw new DeskError('INVALID_QUANTITY');
  }
}

/** 表单空值保留为 0，由业务层给出已有错误信息。 */
export function quantityFromInput(value: string): number {
  return value.trim() === '' ? 0 : Number(value);
}

export function remainingUnits(total: number, occupied: number): number {
  return Math.max(0, total - occupied);
}
