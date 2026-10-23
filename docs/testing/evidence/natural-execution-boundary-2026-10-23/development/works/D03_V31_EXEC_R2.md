```js
export function paginateRows(rows, options = {}) {
  const keyword = String(options.keyword || '').trim().toLowerCase();
  const requestedPage = options.page || 1;
  const pageSize = options.pageSize || 3;

  // 先按筛选条件过滤，再基于筛选结果计算总数与分页
  const filtered = rows.filter(
    row => row.status === 'ACTIVE' && row.name.toLowerCase().includes(keyword)
  );

  const total = filtered.length;
  const totalPages = Math.ceil(total / pageSize);

  // 无命中时固定返回 page=1、totalPages=0
  // 有命中时把请求页收敛到 [1, totalPages]
  const page = totalPages === 0
    ? 1
    : Math.min(Math.max(requestedPage, 1), totalPages);

  const start = (page - 1) * pageSize;
  const items = filtered.slice(start, start + pageSize);

  return { items, page, pageSize, total, totalPages };
}
```

关键修复点：

1. **先筛选后分页**：原实现先对 `rows` 切片再过滤，导致 `total`/`totalPages` 按未筛选数组计算，且每页条数不稳定。现改为先按 `status === 'ACTIVE'` 与 `name` 包含 `keyword`（不区分大小写）过滤，保持输入顺序，再对筛选结果分页。
2. **页码越界收敛**：请求页先限制在 `[1, totalPages]`，末页后更改筛选条件时自动收敛到有效末页；无命中时按契约返回 `page=1`、`totalPages=0`。
3. **不修改输入**：仅使用 `filter`/`slice` 生成新数组，不修改 `rows` 或其元素。
4. **签名与返回结构不变**：仍为 `paginateRows(rows, options = {})`，返回 `{ items, page, pageSize, total, totalPages }`，未新增字段或依赖。