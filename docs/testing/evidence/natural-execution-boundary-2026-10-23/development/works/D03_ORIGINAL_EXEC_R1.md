```javascript
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

修复要点：
- 先对全部 `rows` 做 `status === 'ACTIVE'` 与 `name` 子串筛选，再基于筛选结果计算 `total` 和 `totalPages`，页码与总页数因此一致。
- 分页切片作用于筛选后的数组，避免先切片后筛选导致的页内条数不足与页码错位。
- 请求页超出末页时用 `Math.min(page, totalPages)` 收敛到最后一页，翻到末页后更改筛选也不会越界。
- 无命中时返回 `items=[]、page=1、total=0、totalPages=0`。
- 使用 `filter`/`slice`，不修改 `rows` 及其元素；函数签名与返回结构保持不变。