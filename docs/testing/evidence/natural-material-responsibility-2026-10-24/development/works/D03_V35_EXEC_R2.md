根据提供的契约与现有实现，先确认现状，再给出可直接替换的完整函数。

## 一、现有实现的问题确认

现有 `paginateRows.js`：

```js
export function paginateRows(rows, options = {}) {
  const keyword = String(options.keyword || '').trim().toLowerCase();
  const page = options.page || 1;
  const pageSize = options.pageSize || 3;
  const total = rows.length;
  const totalPages = Math.ceil(total / pageSize);
  const items = rows.slice((page - 1) * pageSize, page * pageSize)
    .filter(row => row.status === 'ACTIVE' && row.name.toLowerCase().includes(keyword));
  return { items, page, pageSize, total, totalPages };
}
```

对照契约，存在以下缺陷：

1. **先分页后筛选**：`slice` 在 `filter` 之前执行，导致 `items` 是“原始数组某页中恰好命中的记录”，而不是“筛选结果中的某一页”。
2. **`total` 错误**：`total = rows.length` 统计的是全部行数，而非筛选命中数。
3. **`totalPages` 错误**：基于未筛选的 `total` 计算，与筛选结果无关。
4. **页码越界未收敛**：`page` 原样返回，末页后更改筛选条件会返回越界页码，且 `items` 为空。
5. **`page`/`pageSize` 未做有限正整数校验**：`options.page || 1` 对 `0`、负数、非整数、`Infinity` 等不健壮（契约要求“若给出则是有限正整数”，实现应保证输出合法）。
6. **`name` 可能非字符串**：直接 `row.name.toLowerCase()` 在 `name` 缺失/非字符串时会抛错（契约未要求新增协议，但应避免因数据形状导致崩溃；此处按字符串化处理，不改变筛选语义）。
7. **无命中时**：契约要求 `page=1`、`totalPages=0`，现有实现不满足。

## 二、修复要点

- 先筛选（`status === 'ACTIVE'` 且 `name` 不区分大小写包含 `keyword`），保持输入顺序。
- `total` = 筛选命中数；`totalPages = Math.ceil(total / pageSize)`。
- 无命中：`items=[]`、`page=1`、`total=0`、`totalPages=0`。
- 有命中：`page` 收敛到 `[1, totalPages]`，即 `Math.min(Math.max(page, 1), totalPages)`。
- 只对筛选结果做 `slice`，不修改 `rows` 及其元素（`filter`/`slice` 均返回新数组）。
- 保留签名 `paginateRows(rows, options = {})` 与返回结构 `{items,page,pageSize,total,totalPages}`，不新增字段/依赖。
- `page`/`pageSize` 按“有限正整数”取值，否则回退默认值（`page=1`、`pageSize=3`）。

## 三、可直接替换的完整实现

```js
export function paginateRows(rows, options = {}) {
  const keyword = String(options.keyword || '').trim().toLowerCase();

  const isPositiveInteger = (value) =>
    Number.isInteger(value) && Number.isFinite(value) && value > 0;

  const pageSize = isPositiveInteger(options.pageSize) ? options.pageSize : 3;
  const requestedPage = isPositiveInteger(options.page) ? options.page : 1;

  // 先筛选，保持输入顺序；不修改 rows 或元素
  const filtered = rows.filter(
    (row) =>
      row.status === 'ACTIVE' &&
      String(row.name).toLowerCase().includes(keyword)
  );

  const total = filtered.length;
  const totalPages = Math.ceil(total / pageSize);

  // 无命中：page=1、totalPages=0、items=[]
  if (total === 0) {
    return { items: [], page: 1, pageSize, total: 0, totalPages: 0 };
  }

  // 页码收敛到新结果的有效范围
  const page = Math.min(Math.max(requestedPage, 1), totalPages);

  const start = (page - 1) * pageSize;
  const items = filtered.slice(start, start + pageSize);

  return { items, page, pageSize, total, totalPages };
}
```

## 四、行为核对（基于 `rows_合成.json`）

- `keyword='al'`、`pageSize=2`、`page=1`：命中 `r1 Alpha`、`r3 " Alpine "`、`r5 Alto`（`r7 Alphabet` 为 INACTIVE 被排除），`total=3`、`totalPages=2`，`items=[r1,r3]`。
- 同上 `page=2`：`items=[r5]`，`page=2`。
- 同上 `page=99`：收敛为 `page=2`，`items=[r5]`。
- 先 `page=2` 再改 `keyword='zzz'`：无命中，返回 `{items:[],page:1,pageSize:2,total:0,totalPages:0}`，不越界。
- 先 `page=2` 再改 `keyword='gamma'`：命中 1 条，`totalPages=1`，`page` 收敛为 `1`，`items=[r4]`。
- `keyword=''`：命中全部 ACTIVE（`r1,r3,r4,r5,r6`），`total=5`，`pageSize=3` 时 `totalPages=2`。

该实现满足：筛选后页码与总页数正确、末页后改筛选不越界、保留原签名与返回结构、不修改输入数组、无新增依赖或字段。