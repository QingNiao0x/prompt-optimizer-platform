# 青禾服务中心月度服务量数据质量报告（初稿）

## 一、报告目的与资料范围

本报告针对青禾服务中心月度服务量台账的数据质量进行初步核查说明。当前可用的资料仅为一份 CSV 数据字典，字典规定该台账包含 `month` 与 `count` 两列：`month` 采用 `YYYY-MM` 格式且月份须为 01 至 12；`count` 为非负整数；空单元表示缺失，不得替换为 0；负数、小数、非数字或非法月份应报告为非法值，不能静默丢弃。

需要特别说明的是，截至本报告撰写时，尚未提供任何实际 CSV 数据行。因此，本报告不包含任何记录数、缺失率、异常月份、实际趋势或具体统计结论。凡涉及数据依赖的结果，均在相应位置标记所需输入，待实际数据到位后再行补充。本报告的目标是提供一份可审阅的核查框架与结果位置说明，而非对数据质量下最终结论。

## 二、检查方法

检查以数据字典为唯一判定依据，采用字段级规则校验，不引入字典之外的假设。具体方法如下：

1. **字段完整性检查**：确认 CSV 是否包含 `month` 和 `count` 两列，列名是否与字典一致，是否存在多余列或缺失列。
2. **month 格式检查**：逐行判断 `month` 是否符合 `YYYY-MM` 格式，且月份部分在 01 至 12 之间。不符合者标记为非法月份。
3. **count 取值检查**：逐行判断 `count` 是否为非负整数。负数、小数、非数字内容均标记为非法值。
4. **缺失与非法区分**：空单元保持为缺失，单独统计，不补零、不参与数值计算；非法值与缺失分别记录，避免混同。
5. **不修改原始文件**：检查过程只读取操作者主动指定的 CSV 文件，输出检查摘要到标准输出，不对原文件做任何写入、删除或覆盖。

上述方法仅使用 Python 标准库实现，不涉及数据库、网络请求或文件删除操作。

## 三、结果位置说明

由于实际数据未提供，以下结果位置暂以占位方式列出，待数据到位后填入：

- **总行数**：所需输入为实际 CSV 文件。
- **month 缺失数**：所需输入为实际 CSV 文件。
- **month 非法数及示例**：所需输入为实际 CSV 文件。
- **count 缺失数**：所需输入为实际 CSV 文件。
- **count 非法数及示例**：所需输入为实际 CSV 文件。
- **合法记录数**：所需输入为实际 CSV 文件。
- **月份分布与趋势**：所需输入为实际 CSV 文件，且需在确认数据合法性后另行分析。

在数据未到位前，任何上述数值均不应被推定或填充。

## 四、局限

本报告及配套脚本存在以下局限：

1. 仅有数据字典，无实际数据，无法验证字典规则在真实台账中的执行情况。
2. 检查为字段级规则校验，不涉及跨月连续性、重复月份、业务口径一致性等更高层问题。
3. 脚本按行独立判断，不处理多行合并、编码异常或分隔符异常等文件级问题；若文件编码或分隔符与预期不符，可能影响读取结果。
4. 缺失与非法虽已区分，但非法值的具体成因（如录入错误、格式迁移等）需结合业务背景进一步判断。
5. 本报告不构成对数据整体可用性的最终结论，仅作为初步核查说明。

## 五、下一步建议

1. 尽快提供实际 CSV 数据文件，以便填充本报告第三节所列结果位置。
2. 在数据到位后，先运行配套脚本获取字段级检查摘要，再根据摘要决定是否需要进一步清洗或回溯。
3. 对非法值建立单独清单，区分缺失与非法，避免在后续统计中混同处理。
4. 若发现月份重复或跨月断档，建议另行开展连续性核查，本报告不预设此类结论。
5. 明确数据责任人与复核人，确保检查结果可追溯、可审阅。

## 六、交付说明

本报告与配套 Python 脚本分别交付、分别验收。报告正文用于审阅数据质量核查框架与结果位置；脚本用于在操作者主动指定 CSV 文件后输出检查摘要。两者验收标准相互独立，脚本的运行说明不占用报告正文的核查结论部分。

---

## 附：可运行 Python 3 脚本

```python
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
青禾服务中心月度服务量数据质量检查脚本

用途：
    读取操作者主动指定的 CSV 文件，检查 month 和 count 字段，
    输出检查摘要到标准输出。不修改原始文件。

规则（依据数据字典）：
    - month: YYYY-MM，月份 01-12
    - count: 非负整数
    - 空单元表示缺失，保持缺失，不补零
    - 负数、小数、非数字、非法月份报告为非法值，与缺失区分

仅使用 Python 标准库。
"""

import argparse
import csv
import re
import sys

MONTH_RE = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")


def is_non_negative_int(s: str) -> bool:
    """判断字符串是否为非负整数（不含正负号、不含小数点）。"""
    return s.isdigit()


def check_file(path: str) -> int:
    total = 0
    month_missing = 0
    month_invalid = 0
    count_missing = 0
    count_invalid = 0
    valid_rows = 0
    month_invalid_examples = []
    count_invalid_examples = []

    try:
        with open(path, "r", encoding="utf-8-sig", newline="") as f:
            reader = csv.DictReader(f)
            if reader.fieldnames is None:
                print("错误：文件为空或无法读取表头。", file=sys.stderr)
                return 2
            missing_cols = [c for c in ("month", "count") if c not in reader.fieldnames]
            if missing_cols:
                print(f"错误：缺少必需列：{', '.join(missing_cols)}", file=sys.stderr)
                return 2

            for row in reader:
                total += 1
                month_raw = (row.get("month") or "").strip()
                count_raw = (row.get("count") or "").strip()

                month_ok = True
                if month_raw == "":
                    month_missing += 1
                    month_ok = False
                elif not MONTH_RE.match(month_raw):
                    month_invalid += 1
                    month_ok = False
                    if len(month_invalid_examples) < 5:
                        month_invalid_examples.append(month_raw)

                count_ok = True
                if count_raw == "":
                    count_missing += 1
                    count_ok = False
                elif not is_non_negative_int(count_raw):
                    count_invalid += 1
                    count_ok = False
                    if len(count_invalid_examples) < 5:
                        count_invalid_examples.append(count_raw)

                if month_ok and count_ok:
                    valid_rows += 1

    except FileNotFoundError:
        print(f"错误：文件不存在：{path}", file=sys.stderr)
        return 2
    except OSError as e:
        print(f"错误：读取文件失败：{e}", file=sys.stderr)
        return 2

    print("=== 数据质量检查摘要 ===")
    print(f"文件：{path}")
    print(f"总行数：{total}")
    print(f"month 缺失数：{month_missing}")
    print(f"month 非法数：{month_invalid}")
    if month_invalid_examples:
        print(f"month 非法示例：{month_invalid_examples}")
    print(f"count 缺失数：{count_missing}")
    print(f"count 非法数：{count_invalid}")
    if count_invalid_examples:
        print(f"count 非法示例：{count_invalid_examples}")
    print(f"合法记录数：{valid_rows}")
    print("说明：缺失与非法分别统计；未对原始文件做任何修改。")
    return 0


def main():
    parser = argparse.ArgumentParser(
        description="检查月度服务量 CSV 的 month 和 count 字段质量。"
    )
    parser.add_argument("csv_path", help="操作者主动指定的 CSV 文件路径")
    args = parser.parse_args()
    sys.exit(check_file(args.csv_path))


if __name__ == "__main__":
    main()
```

**最小运行示例：**

```bash
python3 check_quality.py /path/to/your/monthly_ledger.csv
```

脚本仅读取指定文件，输出检查摘要到标准输出，不修改原文件，不涉及数据库、网络请求或文件删除。