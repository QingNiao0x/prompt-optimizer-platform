```js
export function paginateRows(rows, options = {}) {
  const keyword = String(options.keyword || '').trim().toLowerCase();
  const pageSize = options.pageSize || 3;

  // 先筛选，保持输入顺序，不修改原数组或元素
  const filtered = rows.filter(
    row => row.status === 'ACTIVE' && row.name.toLowerCase().includes(keyword)
  );

  const total = filtered.length;
  const totalPages = Math.ceil(total / pageSize);

  // 无命中时固定返回 page=1、totalPages=0
  if (total === 0) {
    return { items: [], page: 1, pageSize, total: 0, totalPages: 0 };
  }

  // 请求页码收敛到 [1, totalPages]
  const requestedPage = options.page || 1;
  const page = Math.min(Math.max(requestedPage, 1), totalPages);

  const start = (page - 1) * pageSize;
  const items = filtered.slice(start, start + pageSize);

  return { items, page, pageSize, total, totalPages };
}
```