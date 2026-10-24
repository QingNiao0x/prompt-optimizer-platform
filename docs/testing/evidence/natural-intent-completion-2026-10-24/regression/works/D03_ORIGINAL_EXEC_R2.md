下面是可替换的完整实现。它先筛选、再分页，因此页码与总页数基于筛选结果；请求页超出末页时收敛到末页；无命中时按契约返回；不修改输入数组或元素。

```js
export function paginateRows(rows, options = {}) {
  const keyword = String(options.keyword || '').trim().toLowerCase();
  const page = options.page || 1;
  const pageSize = options.pageSize || 3;

  const filtered = rows.filter(
    row => row.status === 'ACTIVE' && row.name.toLowerCase().includes(keyword)
  );

  const total = filtered.length;
  const totalPages = Math.ceil(total / pageSize);

  if (total === 0) {
    return { items: [], page: 1, pageSize, total: 0, totalPages: 0 };
  }

  const safePage = Math.min(page, totalPages);
  const start = (safePage - 1) * pageSize;
  const items = filtered.slice(start, start + pageSize);

  return { items, page: safePage, pageSize, total, totalPages };
}
```

要点：
- 先按 `status === 'ACTIVE'` 和 `name` 不区分大小写包含 `keyword` 筛选，再计算 `total` 与 `totalPages`。
- `page` 超过 `totalPages` 时用 `Math.min(page, totalPages)` 收敛到末页，避免筛选变化后越界。
- 无命中时返回 `items: []`、`page: 1`、`total: 0`、`totalPages: 0`。
- `filter` 与 `slice` 不修改 `rows` 或元素，返回结构保持 `{items, page, pageSize, total, totalPages}`。