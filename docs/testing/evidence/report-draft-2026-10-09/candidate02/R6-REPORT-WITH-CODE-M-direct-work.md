# 青禾服务中心月度服务量数据质量报告（初稿）

## 一、资料范围

本报告依据现有《CSV 数据字典》（materials/csv-dictionary.md）编写，该字典是本次核查唯一可用的规范性资料。字典约定：数据文件为 CSV 格式，包含 month、count 两列；month 采用 YYYY-MM 格式，月份部分须为 01 至 12；count 为非负整数；空单元表示缺失，不得替换为 0；负数、小数、非数字或非法月份应报告为非法值，不得静默丢弃。截至本稿完成时，尚未提供任何实际 CSV 数据行，因此本报告不包含任何记录数、缺失率、异常月份或服务量趋势等实证结论。凡依赖实际数据的结果，均在第四部分以所需输入的形式标注，待数据到位后补充。

## 二、检查方法

检查采用字段级规则校验，逐行读取 CSV 并分别判定 month 与 count 的状态，判定结果分为三类：合规、缺失、非法。month 的合规条件为字符串严格匹配 YYYY-MM 且月份在 01 至 12 之间；count 的合规条件为可解析为非负整数。空单元一律记为缺失，保持缺失状态，不参与任何补零或插补；负数、小数、非数字文本、非法月份（如 13 月、格式错位）一律记为非法值，单独计数并保留原始取值以便复核，不静默丢弃。检查过程只读取操作者主动指定的本地文件，不写入、不修改原文件，不引入数据库、网络请求或文件删除操作。检查摘要输出到标准输出，包含总行数、合规数、缺失数、非法数及非法明细。上述规则与数据字典逐条对应，未增设字典之外的判定口径。

## 三、结果位置

本报告的结果部分按以下位置预留，待实际数据输入后填写：第一，数据文件基本信息，所需输入为文件路径、文件大小、总行数；第二，字段合规情况，所需输入为 month 与 count 各自的合规数、缺失数、非法数；第三，非法值明细，所需输入为非法行的行号、字段名、原始取值与违规类型；第四，缺失分布，所需输入为缺失所在行号及字段；第五，月度服务量汇总，所需输入为各合规月份的 count 合计与记录条数。以上各项在数据未提供前均不填写具体数值，也不以示例值或惯例值代替。若后续数据行数较大，建议在摘要之外另存明细清单，但该清单的生成方式与存放位置需另行确认。

## 四、局限

本报告当前的主要局限是缺乏实际数据，所有实证结论均无法给出，报告仅完成方法框架与结果位置的界定。其次，检查规则完全依赖现有数据字典，字典未约定的情形（如 month 含前后空格、count 含正号、重复月份是否允许、同一月份多条记录如何汇总）尚无明确口径，需在数据到位后与业务方确认，不能自行假定。再次，字段级校验只能发现格式与取值问题，无法判断数据是否真实反映服务量，也无法识别漏报、重复报送或口径变更等业务性偏差。此外，缺失值保持缺失虽符合字典要求，但缺失比例过高时会影响月度汇总的可用性，该影响程度需在数据到位后评估。本报告不构成对数据质量的最终结论，仅作为可修订初稿。

## 五、下一步建议

建议按以下顺序推进：第一，提供实际 CSV 数据文件，并明确其来源、导出时间与统计口径，以便核对；第二，运行所附检查脚本，将标准输出摘要与本报告第三部分的结果位置逐项对应填写；第三，就字典未约定的边界情形（空格、正号、重复月份、汇总口径）与业务方确认，确认后再调整脚本规则；第四，对非法值与缺失值分别形成处理意见，非法值应回溯原始台账核实，缺失值不得补零，需说明缺失原因；第五，在数据完整且口径确认后，再形成包含实际趋势判断的正式报告。上述建议中涉及口径选择的部分均需业务方确认，本稿不代为决定。

---

# 附：数据质量检查脚本（Python 3，仅标准库）

```python
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查月度服务量 CSV 的 month 与 count 字段质量。

用法:
    python check_service_volume.py <csv文件路径>

只读取操作者指定的本地 CSV 文件，不修改原文件，不访问网络。
缺失值保持缺失，不补零；非法值单独报告，不静默丢弃。
"""

import csv
import re
import sys

MONTH_RE = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")


def classify_month(raw):
    """返回 ('ok'|'missing'|'invalid', 说明)。"""
    if raw is None or raw.strip() == "":
        return "missing", "空单元"
    value = raw.strip()
    if MONTH_RE.match(value):
        return "ok", ""
    return "invalid", "月份格式非法或月份超出01-12"


def classify_count(raw):
    """返回 ('ok'|'missing'|'invalid', 说明)。"""
    if raw is None or raw.strip() == "":
        return "missing", "空单元"
    value = raw.strip()
    if not re.match(r"^[+-]?\d+$", value):
        return "invalid", "非整数（含小数或非数字）"
    number = int(value)
    if number < 0:
        return "invalid", "负数"
    return "ok", ""


def check_file(path):
    summary = {
        "total": 0,
        "month_ok": 0, "month_missing": 0, "month_invalid": 0,
        "count_ok": 0, "count_missing": 0, "count_invalid": 0,
    }
    issues = []

    try:
        handle = open(path, "r", encoding="utf-8-sig", newline="")
    except OSError as exc:
        print("错误：无法打开文件：%s" % exc, file=sys.stderr)
        return None, None

    with handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            print("错误：文件为空或缺少表头。", file=sys.stderr)
            return None, None
        missing_cols = [c for c in ("month", "count") if c not in reader.fieldnames]
        if missing_cols:
            print("错误：缺少必需列：%s" % ", ".join(missing_cols), file=sys.stderr)
            return None, None

        for line_no, row in enumerate(reader, start=2):
            summary["total"] += 1
            for field, classifier in (("month", classify_month), ("count", classify_count)):
                status, reason = classifier(row.get(field))
                summary["%s_%s" % (field, status)] += 1
                if status == "invalid":
                    issues.append((line_no, field, row.get(field), reason))

    return summary, issues


def main(argv):
    if len(argv) != 2:
        print("用法: python check_service_volume.py <csv文件路径>", file=sys.stderr)
        return 2

    summary, issues = check_file(argv[1])
    if summary is None:
        return 1

    print("=== 检查摘要 ===")
    print("总行数: %d" % summary["total"])
    print("month 合规: %d, 缺失: %d, 非法: %d" % (
        summary["month_ok"], summary["month_missing"], summary["month_invalid"]))
    print("count 合规: %d, 缺失: %d, 非法: %d" % (
        summary["count_ok"], summary["count_missing"], summary["count_invalid"]))

    if issues:
        print("--- 非法值明细 ---")
        for line_no, field, raw, reason in issues:
            print("第 %d 行, 字段 %s, 原始值 %r, 原因: %s" % (
                line_no, field, raw, reason))
    else:
        print("未发现非法值。")

    print("说明：缺失值保持缺失，未补零；非法值已单独列出，未丢弃。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
```

最小运行示例：

```
python check_service_volume.py ./data/service_volume.csv
```

脚本将总行数、month 与 count 的合规/缺失/非法计数及非法值明细输出到标准输出；文件无法打开、缺少表头或缺少 month/count 列时，向标准错误输出明确错误信息并返回非零退出码。