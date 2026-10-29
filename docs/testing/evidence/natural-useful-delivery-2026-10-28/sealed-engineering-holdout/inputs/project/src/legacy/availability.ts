export interface LegacyBorrowRow { deviceCode: string; pieces: number; closed: boolean; }

/** 退役工作台的旧口径，只供旧记录回看；没有地点维度。 */
export function availabilityFor(total: number, deviceCode: string, rows: LegacyBorrowRow[]): number {
  return Math.max(0, total - rows.filter((row) => row.deviceCode === deviceCode && !row.closed)
    .reduce((sum, row) => sum + row.pieces, 0));
}

export function legacySummary(rows: LegacyBorrowRow[]): string {
  const open = rows.filter((row) => !row.closed);
  return `旧台账未结 ${open.length} 单，共 ${open.reduce((sum, row) => sum + row.pieces, 0)} 件`;
}
