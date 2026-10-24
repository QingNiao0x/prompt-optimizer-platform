下面是可替换的完整实现。它先对全部记录做筛选，再基于筛选结果计算总数与分页，因此页码、总页数正确，且请求页超出末页时会收敛到最后一页；不修改输入数组或元素，函数签名与返回结构保持不变。

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

  const currentPage = Math.min(page, totalPages);
  const start = (currentPage - 1) * pageSize;
  const items = filtered.slice(start, start + pageSize);

  return { items, page: currentPage, pageSize, total, totalPages };
}
```

要点说明：
- 先筛选后分页，`total` 与 `totalPages` 均基于筛选命中数计算。
- 无命中时返回 `items=[]、page=1、total=0、totalPages=0`。
- 请求页超过末页时用 `Math.min(page, totalPages)` 收敛，翻到末页后再更改筛选也不会越界。
- `filter` 与 `slice` 不修改原数组及元素，未新增依赖、API 或返回字段。