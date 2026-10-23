下面是可直接替换原函数的完整实现，保留原函数签名、筛选方式与返回结构，不修改传入数组或元素。

```js
export function paginateRows(rows, options = {}) {
  const keyword = String(options.keyword || '').trim().toLowerCase();
  const page = options.page || 1;
  const pageSize = options.pageSize || 3;

  // 先筛选，再分页：total/totalPages 基于筛选命中数计算
  const filtered = rows.filter(
    row => row.status === 'ACTIVE' && row.name.toLowerCase().includes(keyword)
  );

  const total = filtered.length;
  const totalPages = Math.ceil(total / pageSize);

  // 无命中：items=[]、page=1、total=0、totalPages=0
  if (total === 0) {
    return { items: [], page: 1, pageSize, total: 0, totalPages: 0 };
  }

  // 页码收敛到 [1, totalPages]，避免末页后更改筛选条件导致越界
  const safePage = Math.min(Math.max(page, 1), totalPages);
  const start = (safePage - 1) * pageSize;
  const items = filtered.slice(start, start + pageSize);

  return { items, page: safePage, pageSize, total, totalPages };
}
```

修复要点：

- **先筛选后分页**：`total` 与 `totalPages` 基于筛选命中数计算，不再使用 `rows.length`。
- **页码收敛**：`safePage = Math.min(Math.max(page, 1), totalPages)`，当筛选后总页数减少或当前页超出末页时，自动收敛到最后一页。
- **空结果处理**：无命中时返回 `items=[]、page=1、total=0、totalPages=0`。
- **不修改输入**：仅使用 `filter` 与 `slice` 生成新数组，`rows` 及其元素保持不变。
- **返回结构不变**：仍为 `{items, page, pageSize, total, totalPages}`，未新增字段或依赖。