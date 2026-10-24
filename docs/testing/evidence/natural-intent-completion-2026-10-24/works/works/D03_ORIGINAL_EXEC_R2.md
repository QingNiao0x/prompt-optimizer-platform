下面是可替换的完整实现。它先筛选、再分页，因此 `total`/`totalPages` 基于筛选命中数；页码在筛选后收敛到 `[1, totalPages]`（无命中时为 1），不会越界；不修改 `rows` 或元素，返回结构不变。

```js
export function paginateRows(rows, options = {}) {
  const keyword = String(options.keyword || '').trim().toLowerCase();
  const requestedPage = options.page || 1;
  const pageSize = options.pageSize || 3;

  const filtered = rows.filter(row =>
    row.status === 'ACTIVE' &&
    row.name.toLowerCase().includes(keyword)
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

要点说明：

- **筛选先于分页**：`filter` 得到全部命中记录，`total` 与 `totalPages` 都基于命中数，页码切片也只作用于命中结果。
- **末页越界收敛**：`page = Math.min(Math.max(requestedPage, 1), totalPages)`，翻到末页后再改筛选（命中数变少）也会被夹到新的最后一页。
- **无命中**：`totalPages = 0` 时强制 `page = 1`，`items = []`，符合契约。
- **不修改输入**：只使用 `filter`/`slice` 产生新数组，未改动 `rows` 或元素。
- **签名与返回结构不变**：仍为 `paginateRows(rows, options = {})`，返回 `{items, page, pageSize, total, totalPages}`。