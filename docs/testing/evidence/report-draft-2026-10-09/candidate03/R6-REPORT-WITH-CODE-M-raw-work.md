# 青禾服务中心月度服务量数据质量报告（初稿）

## 一、报告目的与资料范围

本报告针对青禾服务中心月度服务量台账的数据质量进行初步核查说明。当前可用的资料仅为一份 CSV 数据字典（`materials/csv-dictionary.md`），其中规定：CSV 包含 `month` 与 `count` 两列；`month` 采用 `YYYY-MM` 格式且月份须为 01 至 12；`count` 为非负整数；空单元表示缺失，不得替换为 0；负数、小数、非数字或非法月份应报告为非法值，不能静默丢弃。

需要特别说明的是，**截至本报告撰写时，尚未提供任何实际 CSV 数据行**。因此，本报告只能就字段定义、检查口径与检查方法作出说明，无法给出任何关于记录总数、缺失率、非法值比例、异常月份或服务量趋势的结论。凡涉及具体数值的结果，均在正文中以“所需输入”形式标记，待实际数据到位后补充。

## 二、检查方法

检查以数据字典为唯一判定依据，围绕两个字段展开。

**month 字段检查。** 逐行判断取值是否满足 `YYYY-MM` 结构，且月份部分在 01 至 12 之间。空单元记为缺失，保持缺失状态，不填充、不推断。不符合格式或月份越界的取值记为非法值，并单独列出，不与缺失混同。

**count 字段检查。** 逐行判断取值是否为非负整数。空单元记为缺失，保持缺失，**不得补零**。负数、小数、非数字文本均记为非法值，明确报告，不静默丢弃。

**缺失与非法的区分。** 这是本次检查的核心口径。缺失表示“该单元为空”，非法表示“该单元有内容但不符合规则”。两者在统计与后续处理上含义不同，报告中分别计数、分别列示，避免以“异常”一词笼统合并。

**处理边界。** 检查过程只读取操作者主动指定的 CSV 文件，不修改原始文件，不新增数据库、不发起网络请求、不执行文件删除。

## 三、结果位置

以下位置需在实际数据到位后填入具体结果，当前仅标记所需输入：

- 记录总数：所需输入（实际 CSV 行数）。
- month 缺失数：所需输入。
- month 非法值数及明细：所需输入（含非法取值与所在行号）。
- count 缺失数：所需输入。
- count 非法值数及明细：所需输入（含非法取值与所在行号）。
- 月份覆盖区间与是否存在重复月份：所需输入。
- 服务量分布与趋势描述：所需输入。

## 四、局限

本报告存在以下局限。第一，**无实际数据**，所有涉及数量的结论均无法给出，报告目前仅具方法与口径层面的审阅价值。第二，判定规则完全依赖现有数据字典，若字典后续修订，检查口径需同步调整。第三，检查仅覆盖 `month` 与 `count` 两列的格式与取值合法性，不涉及业务真实性、重复上报、口径一致性等更深层问题。第四，脚本输出为逐项计数与明细，不替代人工复核。

## 五、下一步建议

1. 提供实际 CSV 文件后，运行所附脚本生成检查摘要，并将结果填入第三节对应位置。
2. 对非法值逐条回溯来源，确认是录入错误还是字典未覆盖的合法情形。
3. 对缺失值记录其分布，评估是否影响月度连续性，但不作补零处理。
4. 若发现重复月份，明确同一月份多条记录的合并规则后再行统计。
5. 数据稳定后，将本检查固化为月度例行步骤，保留每次检查摘要以备审阅。

## 六、交付说明

本任务交付两项成果，分别验收：一是本报告正文；二是随附的 Python 3 脚本。脚本仅使用标准库，接收操作者主动指定的本地 CSV 路径，检查 `month` 与 `count` 字段，输出检查摘要到标准输出，不修改原始文件。

---

## 附：检查脚本（Python 3，仅标准库）

```python
#!/usr/bin/env python3
"""月度服务量 CSV 数据质量检查（只读，不修改原文件）。

用法示例：
    python check_ledger.py /path/to/ledger.csv

规则（依据数据字典）：
- month: YYYY-MM，月份 01-12；空单元=缺失；其余=非法
- count: 非负整数；空单元=缺失（不补零）；其余=非法
"""

import csv
import re
import sys

MONTH_RE = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")


def classify_month(value):
    if value is None or value.strip() == "":
        return "missing", None
    v = value.strip()
    if MONTH_RE.match(v):
        return "ok", v
    return "invalid", v


def classify_count(value):
    if value is None or value.strip() == "":
        return "missing", None
    v = value.strip()
    if re.match(r"^\d+$", v):
        return "ok", int(v)
    return "invalid", v


def main():
    if len(sys.argv) != 2:
        print("用法: python check_ledger.py <csv文件路径>")
        sys.exit(2)

    path = sys.argv[1]
    stats = {
        "rows": 0,
        "month_ok": 0, "month_missing": 0, "month_invalid": 0,
        "count_ok": 0, "count_missing": 0, "count_invalid": 0,
    }
    month_invalid_detail = []
    count_invalid_detail = []
    months_seen = []

    try:
        with open(path, "r", encoding="utf-8-sig", newline="") as f:
            reader = csv.DictReader(f)
            if reader.fieldnames is None or \
               "month" not in reader.fieldnames or "count" not in reader.fieldnames:
                print("错误: CSV 必须包含 month 和 count 两列。")
                sys.exit(1)

            for lineno, row in enumerate(reader, start=2):
                stats["rows"] += 1

                m_state, m_val = classify_month(row.get("month"))
                if m_state == "ok":
                    stats["month_ok"] += 1
                    months_seen.append(m_val)
                elif m_state == "missing":
                    stats["month_missing"] += 1
                else:
                    stats["month_invalid"] += 1
                    month_invalid_detail.append((lineno, m_val))

                c_state, c_val = classify_count(row.get("count"))
                if c_state == "ok":
                    stats["count_ok"] += 1
                elif c_state == "missing":
                    stats["count_missing"] += 1
                else:
                    stats["count_invalid"] += 1
                    count_invalid_detail.append((lineno, c_val))
    except FileNotFoundError:
        print(f"错误: 找不到文件 {path}")
        sys.exit(1)

    print("=== 月度服务量数据质量检查摘要 ===")
    print(f"文件: {path}")
    print(f"记录总数: {stats['rows']}")
    print()
    print("[month 字段]")
    print(f"  合法: {stats['month_ok']}")
    print(f"  缺失: {stats['month_missing']}")
    print(f"  非法: {stats['month_invalid']}")
    for lineno, val in month_invalid_detail:
        print(f"    第 {lineno} 行: {val!r}")
    print()
    print("[count 字段]")
    print(f"  合法: {stats['count_ok']}")
    print(f"  缺失: {stats['count_missing']}")
    print(f"  非法: {stats['count_invalid']}")
    for lineno, val in count_invalid_detail:
        print(f"    第 {lineno} 行: {val!r}")
    print()
    if months_seen:
        print(f"月份覆盖: {min(months_seen)} ~ {max(months_seen)}")
        dup = sorted({m for m in months_seen if months_seen.count(m) > 1})
        print(f"重复月份: {dup if dup else '无'}")
    else:
        print("月份覆盖: 无合法月份记录")
    print()
    print("说明: 缺失与非法分别计数；缺失保持缺失，未补零；原文件未被修改。")


if __name__ == "__main__":
    main()
```

**最小运行示例：**

```bash
python check_ledger.py ./ledger.csv
```

脚本只读取指定文件，输出摘要到标准输出，不写入、不删除、不联网。