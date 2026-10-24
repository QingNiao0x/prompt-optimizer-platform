```js
export function paginateRows(rows, options = {}) {
  const keyword = String(options.keyword || '').trim().toLowerCase();

  const toPositiveInt = (value, fallback) => {
    const n = Number(value);
    return Number.isFinite(n) && Number.isInteger(n) && n > 0 ? n : fallback;
  };

  const pageSize = toPositiveInt(options.pageSize, 3);
  const requestedPage = toPositiveInt(options.page, 1);

  // 先按筛选条件过滤，顺序沿用输入顺序；不修改 rows 及其元素
  const filtered = rows.filter(
    row => row.status === 'ACTIVE' && row.name.toLowerCase().includes(keyword)
  );

  const total = filtered.length;
  const totalPages = Math.ceil(total / pageSize);

  // 无命中时固定返回 page=1、totalPages=0
  // 有命中时把请求页码收敛到 [1, totalPages]
  const page = total === 0 ? 1 : Math.min(requestedPage, totalPages);

  const start = (page - 1) * pageSize;
  const items = filtered.slice(start, start + pageSize);

  return { items, page, pageSize, total, totalPages };
}
```

关键修复点：

1. **先筛选再分页**：原实现先对原始数组切片再过滤，导致 `total`、`totalPages` 和 `items` 都基于未筛选数据计算。现在先按 `status === 'ACTIVE'` 和 `name` 包含 `keyword`（不区分大小写）过滤，再对筛选结果分页。
2. **页码越界收敛**：请求页码超出最后一页时收敛到 `totalPages`；无命中时固定 `page = 1`、`totalPages = 0`，避免出现 `page > totalPages` 的无效状态。
3. **参数归一化**：`page`、`pageSize` 仅在为有限正整数时采用，否则回退默认值（`1`、`3`），避免 `0`、负数、`NaN` 等导致切片异常。
4. **不修改输入**：使用 `filter` 和 `slice` 生成新数组，未改动 `rows` 或其元素，也未新增依赖、API 或返回字段，函数签名与返回结构保持不变。