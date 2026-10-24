已读取现有实现与契约。现有函数的问题：先按原始 `rows` 切片分页、再在切片内筛选，导致 `total`/`totalPages` 基于未筛选数组，且页码越界时不会收敛。下面给出可直接替换原函数的完整实现，保留原签名、筛选方式与返回结构，不修改输入数组。

```js
export function paginateRows(rows, options = {}) {
  const keyword = String(options.keyword || '').trim().toLowerCase();
  const pageSize = options.pageSize || 3;

  // 先筛选，保持输入顺序；不修改 rows 或元素
  const filtered = rows.filter(
    row => row.status === 'ACTIVE' && row.name.toLowerCase().includes(keyword)
  );

  const total = filtered.length;
  const totalPages = Math.ceil(total / pageSize);

  // 无命中时固定 page=1；否则将请求页收敛到 [1, totalPages]
  let page = options.page || 1;
  if (totalPages === 0) {
    page = 1;
  } else if (page > totalPages) {
    page = totalPages;
  } else if (page < 1) {
    page = 1;
  }

  const start = (page - 1) * pageSize;
  const items = filtered.slice(start, start + pageSize);

  return { items, page, pageSize, total, totalPages };
}
```

要点说明：
- 筛选在分页之前完成，`total` 与 `totalPages` 均基于筛选命中数。
- 末页后更改筛选条件时，`page` 会被收敛到新结果的最后一页；无命中时 `page=1`、`totalPages=0`。
- `filter`/`slice` 不改变原数组，元素仅被读取。
- 返回字段仍为 `{items, page, pageSize, total, totalPages}`，未新增依赖或字段。