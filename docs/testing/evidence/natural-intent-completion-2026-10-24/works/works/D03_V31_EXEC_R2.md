```js
export function paginateRows(rows, options = {}) {
  const keyword = String(options.keyword || '').trim().toLowerCase();

  const toPositiveInt = (value, fallback) => {
    const n = Number(value);
    return Number.isFinite(n) && Number.isInteger(n) && n > 0 ? n : fallback;
  };

  const pageSize = toPositiveInt(options.pageSize, 3);
  const requestedPage = toPositiveInt(options.page, 1);

  // 先按筛选条件过滤，再基于筛选结果计算总数与分页
  const filtered = rows.filter(
    row => row.status === 'ACTIVE' && row.name.toLowerCase().includes(keyword)
  );

  const total = filtered.length;
  const totalPages = Math.ceil(total / pageSize);

  // 无命中时固定收敛为 page=1、totalPages=0
  const page = totalPages === 0 ? 1 : Math.min(requestedPage, totalPages);

  const start = (page - 1) * pageSize;
  const items = filtered.slice(start, start + pageSize);

  return { items, page, pageSize, total, totalPages };
}
```

关键修复点：

1. **先筛选后分页**：原实现先对 `rows` 切片再过滤，导致 `total`、`totalPages` 和 `items` 都基于未筛选数据，页码错位。现改为先按 `status === 'ACTIVE'` 和 `name` 包含 `keyword`（不区分大小写）过滤，再对筛选结果分页。
2. **页码越界收敛**：请求页超过 `totalPages` 时用 `Math.min(requestedPage, totalPages)` 收敛到末页；无命中时固定 `page = 1`、`totalPages = 0`，避免 `page` 越界。
3. **参数归一化**：`page`、`pageSize` 仅在为有限正整数时采用，否则回退默认值（1 和 3），与契约一致。
4. **不修改输入**：使用 `filter` 和 `slice` 生成新数组，未改动 `rows` 或其元素，也未新增依赖、API 或返回字段，函数签名与返回结构保持不变。