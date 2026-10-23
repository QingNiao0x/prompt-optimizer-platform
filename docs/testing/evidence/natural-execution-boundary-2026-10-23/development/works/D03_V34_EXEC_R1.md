下面是可直接替换原函数的完整实现，保留原函数签名、筛选方式与返回结构，不修改传入数组或元素。

```js
export function paginateRows(rows, options = {}) {
  const keyword = String(options.keyword || '').trim().toLowerCase();

  const toPositiveInt = (value, fallback) => {
    return Number.isInteger(value) && value > 0 ? value : fallback;
  };

  const requestedPage = toPositiveInt(options.page, 1);
  const pageSize = toPositiveInt(options.pageSize, 3);

  // 先筛选，再分页：total/totalPages 基于全部筛选命中数
  const filtered = rows.filter(
    row => row.status === 'ACTIVE' && row.name.toLowerCase().includes(keyword)
  );

  const total = filtered.length;
  const totalPages = Math.ceil(total / pageSize);

  // 无命中：items=[]、page=1、total=0、totalPages=0
  if (total === 0) {
    return { items: [], page: 1, pageSize, total: 0, totalPages: 0 };
  }

  // 页码收敛到 [1, totalPages]，防止末页后更改筛选条件导致越界
  const page = Math.min(Math.max(requestedPage, 1), totalPages);

  const start = (page - 1) * pageSize;
  const items = filtered.slice(start, start + pageSize);

  return { items, page, pageSize, total, totalPages };
}
```

修复要点：

- **先筛选后分页**：`total` 与 `totalPages` 基于全部筛选命中数计算，不再使用原始 `rows.length`。
- **页码收敛**：`page` 被限制在 `[1, totalPages]` 内；末页后更改筛选条件导致总页数减少时，页码不会越界。
- **空结果处理**：无命中时返回 `items=[]`、`page=1`、`total=0`、`totalPages=0`。
- **输入校验**：`page`、`pageSize` 仅在为有限正整数时采用，否则回退默认值 `1`、`3`。
- **不修改输入**：仅使用 `filter` 与 `slice` 生成新数组，不修改 `rows` 或其中元素。
- **返回结构不变**：仍为 `{items, page, pageSize, total, totalPages}`，未新增字段或依赖。