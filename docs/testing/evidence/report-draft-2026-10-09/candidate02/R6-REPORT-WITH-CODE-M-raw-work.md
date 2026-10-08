# 青禾服务中心月度服务量数据质量报告（初稿）

## 一、报告目的与资料范围

本报告针对青禾服务中心月度服务量台账的数据质量进行初步核查说明。当前可用的资料仅为一份 CSV 数据字典（`materials/csv-dictionary.md`），其中规定 CSV 包含 `month` 与 `count` 两列：`month` 采用 `YYYY-MM` 格式且月份须为 01 至 12；`count` 为非负整数；空单元表示缺失，不得替换为 0；负数、小数、非数字或非法月份应报告为非法值，不能静默丢弃。

需要特别说明的是，截至本报告撰写时，尚未提供任何实际 CSV 数据行。因此本报告不包含任何记录数、缺失率、异常月份分布或服务量趋势等基于真实数据的结论。凡涉及数据依赖的结果，均在正文中以“【所需输入】”标记，待实际数据到位后补充。

## 二、检查方法

数据质量检查围绕字段级规则展开，分为格式检查与取值检查两类。

对 `month` 字段，检查其是否满足 `YYYY-MM` 结构，且月份部分是否落在 01 至 12 之间。不符合该结构的取值（如 `2024-13`、`2024/01`、`202401`、空字符串以外的乱码等）应判定为非法值。

对 `count` 字段，检查其是否为非负整数。负数、小数、非数字文本均判定为非法值。空单元单独判定为缺失，不参与非法值统计，也不得补零。

检查结果按“缺失”与“非法”两类分别记录，二者不得混同。缺失表示数据未填写，非法表示填写了但不符合规则，二者在后续处理中的含义不同。

## 三、结果位置

由于当前无实际数据，以下结果位置暂以占位标记表示，待数据到位后填入：

- 总记录数：【所需输入：实际 CSV 记录数】
- `month` 缺失数：【所需输入：month 为空的行数】
- `month` 非法数：【所需输入：month 格式或月份非法的行数】
- `count` 缺失数：【所需输入：count 为空的行数】
- `count` 非法数：【所需输入：count 为负数、小数或非数字的行数】
- 非法值明细样例：【所需输入：具体非法行及原因】

上述各项均需在拿到实际 CSV 后，由检查脚本输出摘要，再据实填入本报告。

## 四、局限

本报告及配套脚本存在以下局限：

第一，当前无实际数据，所有数据依赖结论均未生成，报告仅完成方法与位置说明，不能视为数据质量结论。

第二，检查仅覆盖 `month` 与 `count` 两列的格式与取值规则，不涉及跨月连续性、重复月份、业务口径一致性等更复杂问题。若同一月份出现多行，脚本不会自动判定为重复或合并，需人工复核。

第三，脚本只读取操作者主动指定的本地 CSV 文件，不修改原文件，不进行数据库写入、网络请求或文件删除。因此检查过程不改变台账原始状态，但也意味着脚本不负责修复数据。

第四，缺失与非法虽已区分，但脚本不判断缺失是否由业务原因造成，也不对非法值做自动纠正。

## 五、下一步建议

建议在取得实际 CSV 后，按以下步骤推进：

1. 由数据提供方确认 CSV 文件路径与编码，确保脚本读取的是目标台账。
2. 运行配套脚本，获取缺失与非法值的分类摘要。
3. 将摘要结果填入本报告第三节的对应位置，形成完整版报告。
4. 对非法值逐条复核，区分录入错误与口径问题，必要时回溯原始台账。
5. 对缺失值不补零，保留缺失状态，并在后续分析中单独说明。
6. 若发现同一月份多行，另行制定去重或合并规则，不在本脚本中自动处理。

## 六、交付说明

本报告与配套 Python 脚本分别交付、分别验收。报告用于审阅数据质量检查的范围、方法、结果位置与局限；脚本用于在实际数据到位后执行检查并输出摘要。两者不互相替代。

---

## 附：可运行 Python 3 脚本

```python
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
青禾服务中心月度服务量 CSV 数据质量检查脚本

用法:
    python check_monthly.py <csv文件路径>

说明:
    - 仅使用 Python 标准库。
    - 只读取操作者主动指定的 CSV 文件，不修改原文件。
    - 检查 month 与 count 两列。
    - month 格式 YYYY-MM，月份 01-12。
    - count 为非负整数。
    - 空单元视为缺失，不补零。
    - 非法值与缺失分别报告。
"""

import csv
import re
import sys

MONTH_RE = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")


def is_non_negative_int(text):
    """判断字符串是否为非负整数（不含正负号、不含小数点）。"""
    return text.isdigit()


def check_file(path):
    total = 0
    month_missing = 0
    month_invalid = 0
    count_missing = 0
    count_invalid = 0
    invalid_details = []

    with open(path, "r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames is None:
            print("错误：CSV 文件为空或缺少表头。")
            return
        if "month" not in reader.fieldnames or "count" not in reader.fieldnames:
            print("错误：CSV 必须包含 month 和 count 两列。")
            print("实际表头：", reader.fieldnames)
            return

        for lineno, row in enumerate(reader, start=2):
            total += 1
            month = (row.get("month") or "").strip()
            count = (row.get("count") or "").strip()

            if month == "":
                month_missing += 1
            elif not MONTH_RE.match(month):
                month_invalid += 1
                invalid_details.append((lineno, "month", month, "格式或月份非法"))

            if count == "":
                count_missing += 1
            elif not is_non_negative_int(count):
                count_invalid += 1
                invalid_details.append((lineno, "count", count, "非负整数校验失败"))

    print("===== 检查摘要 =====")
    print("文件：", path)
    print("总记录数：", total)
    print("month 缺失数：", month_missing)
    print("month 非法数：", month_invalid)
    print("count 缺失数：", count_missing)
    print("count 非法数：", count_invalid)
    print("非法值明细：")
    if not invalid_details:
        print("  （无）")
    else:
        for lineno, col, value, reason in invalid_details:
            print(f"  第{lineno}行 列={col} 值={value!r} 原因={reason}")


def main():
    if len(sys.argv) != 2:
        print("用法：python check_monthly.py <csv文件路径>")
        sys.exit(1)
    check_file(sys.argv[1])


if __name__ == "__main__":
    main()
```

最小运行示例：

```bash
python check_monthly.py ./monthly_ledger.csv
```

脚本将输出总记录数、`month` 与 `count` 的缺失数和非法数，以及非法值明细。原始 CSV 文件不会被修改。