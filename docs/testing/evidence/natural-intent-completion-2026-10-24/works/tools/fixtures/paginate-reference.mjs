// 根据D03原契约独立编写，仅用于校验检查器；不是任何候选的下游作品。
export function paginateRows(rows, options = {}) {
  const needle = String(options.keyword || '').trim().toLowerCase();
  const selected = [];
  for (const row of rows) {
    if (row.status === 'ACTIVE' && row.name.toLowerCase().includes(needle)) selected.push(row);
  }
  const pageSize = options.pageSize ?? 3;
  const total = selected.length;
  const totalPages = Math.ceil(total / pageSize);
  const page = totalPages === 0 ? 1 : Math.min(options.page ?? 1, totalPages);
  return { items: selected.slice((page - 1) * pageSize, page * pageSize), page, pageSize, total, totalPages };
}
