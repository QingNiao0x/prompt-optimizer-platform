```js
export function paginateRows(rows, options = {}) {
  const keyword = String(options.keyword || '').trim().toLowerCase();
  const requestedPage = options.page || 1;
  const pageSize = options.pageSize || 3;

  const filtered = rows.filter(
    row => row.status === 'ACTIVE' && row.name.toLowerCase().includes(keyword)
  );

  const total = filtered.length;
  const totalPages = Math.ceil(total / pageSize);

  let page;
  if (totalPages === 0) {
    page = 1;
  } else {
    page = Math.min(Math.max(requestedPage, 1), totalPages);
  }

  const start = (page - 1) * pageSize;
  const items = filtered.slice(start, start + pageSize);

  return { items, page, pageSize, total, totalPages };
}
```

关键修复点：

1. **先筛选后分页**：原实现先对 `rows` 切片再过滤，导致 `total`/`totalPages` 基于未筛选数组、`items` 数量与页码错位。现改为先按 `status === 'ACTIVE'` 与 `name` 包含 `keyword`（不区分大小写）筛选，保持输入顺序，再对筛选结果分页。
2. **total/totalPages 基于筛选命中数**：`total = filtered.length`，`totalPages = Math.ceil(total / pageSize)`，与契约一致。
3. **页码自动收敛**：请求页超出末页时收敛到 `totalPages`，低于 1 时收敛到 1；无命中时固定 `page = 1`、`totalPages = 0`、`items = []`，避免末页后更改筛选条件导致越界。
4. **不修改输入**：仅使用 `filter`/`slice` 生成新数组，未改动 `rows` 或其元素，也未新增依赖、API 或返回字段，函数签名与返回结构保持不变。