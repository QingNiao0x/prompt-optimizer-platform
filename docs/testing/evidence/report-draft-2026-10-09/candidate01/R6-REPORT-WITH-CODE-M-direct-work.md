# 青禾服务中心月度服务量数据质量报告（初稿）

## 一、资料范围

本报告依据青禾服务中心提供的 CSV 数据字典（materials/csv-dictionary.md）撰写。该字典规定：CSV 文件包含 month、count 两列；month 采用 YYYY-MM 格式，月份部分须为 01 至 12；count 为非负整数；空单元表示缺失，不得替换为 0；负数、小数、非数字或非法月份应报告为非法值，不得静默丢弃。截至本稿撰写时，尚未提供任何实际 CSV 数据行，因此本报告仅覆盖数据质量检查框架、字段口径与结果呈现方式，不包含任何实际统计结果。本报告的研究对象为青禾服务中心按月汇总的服务量台账，指标定义为各自然月内登记的服务总量，统计口径以 CSV 中 month 与 count 两列的对应关系为准。

## 二、检查方法

检查以逐行读取 CSV 为基础，对 month 与 count 两列分别校验。month 列检查三项：是否为空、是否符合 YYYY-MM 格式、月份是否在 01 至 12 之间。count 列检查四项：是否为空、是否为整数、是否为非负、是否可解析为数字。检查结果分为三类：有效值、缺失值、非法值。缺失值指单元格为空，保持缺失状态，不参与计数，也不补零；非法值指格式或取值不符合字典规定，需单独列出并注明行号与原始内容，以便追溯。检查过程只读取操作者主动指定的文件，不写入、不修改原文件，不连接数据库，不发起网络请求，不执行文件删除。检查摘要输出到标准输出，包含总行数、有效行数、缺失数、非法数及非法明细。由于当前无实际数据，以下结果位置均以 [待补：具体内容] 标注所需输入。

## 三、结果位置

本次检查的预期结果位置如下：数据文件总行数 [待补：CSV 文件路径及总行数]；month 列有效值数量 [待补：month 有效计数]；month 列缺失值数量 [待补：month 缺失计数]；month 列非法值数量及明细 [待补：month 非法计数与行号、原始值]；count 列有效值数量 [待补：count 有效计数]；count 列缺失值数量 [待补：count 缺失计数]；count 列非法值数量及明细 [待补：count 非法计数与行号、原始值]；有效记录的服务量合计 [待补：有效 count 合计]；按月分布情况 [待补：各月有效 count 汇总]。上述位置在取得实际 CSV 后，由随附脚本运行输出填充。报告不预设任何数值，不推断缺失率、异常月份或趋势。

## 四、局限与下一步建议

本稿的主要局限在于缺乏实际数据行，无法验证字段实际取值分布、缺失比例及非法值类型，也无法判断数据采集环节是否存在系统性偏倚。此外，字典未规定重复月份的处理方式、count 的上限以及多文件合并规则，这些口径需在数据到位后确认。方法适用条件为：CSV 首行为表头且列名与字典一致；文件编码可被标准库正常解析；单文件检查，不涉及跨文件比对。下一步建议：第一，提供实际 CSV 文件后运行随附脚本，获取检查摘要；第二，根据非法值明细回溯采集环节，确认是录入错误还是口径差异；第三，明确重复月份与 count 上限的处理规则，补充到数据字典；第四，在连续多个月数据到位后，再开展趋势分析与环比同比比较。在数据未到位前，不对外发布任何具体数值结论。

---

## 附：Python 3 检查脚本

```python
#!/usr/bin/env python3
"""青禾服务中心月度服务量 CSV 数据质量检查脚本。

仅使用 Python 标准库。只读取操作者主动指定的文件，不修改原文件，
不连接数据库，不发起网络请求，不删除文件。
"""

import argparse
import csv
import re
import sys

MONTH_PATTERN = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")


def classify_month(raw):
    """返回 ('valid', value) / ('missing', None) / ('illegal', reason)。"""
    if raw is None or raw.strip() == "":
        return "missing", None
    value = raw.strip()
    if not MONTH_PATTERN.match(value):
        return "illegal", "月份格式非法或超出01-12范围"
    return "valid", value


def classify_count(raw):
    """返回 ('valid', int) / ('missing', None) / ('illegal', reason)。"""
    if raw is None or raw.strip() == "":
        return "missing", None
    value = raw.strip()
    if not re.match(r"^[+-]?\d+$", value):
        return "illegal", "非整数（可能为小数或非数字）"
    number = int(value)
    if number < 0:
        return "illegal", "负数"
    return "valid", number


def check_file(path):
    summary = {
        "total": 0,
        "month_valid": 0,
        "month_missing": 0,
        "month_illegal": 0,
        "count_valid": 0,
        "count_missing": 0,
        "count_illegal": 0,
        "illegal_details": [],
        "month_totals": {},
    }
    try:
        with open(path, "r", encoding="utf-8-sig", newline="") as handle:
            reader = csv.DictReader(handle)
            if reader.fieldnames is None:
                print("错误：文件为空或缺少表头。", file=sys.stderr)
                return None
            missing_cols = [c for c in ("month", "count") if c not in reader.fieldnames]
            if missing_cols:
                print("错误：缺少必需列：%s" % ", ".join(missing_cols), file=sys.stderr)
                return None
            for line_no, row in enumerate(reader, start=2):
                summary["total"] += 1
                m_status, m_value = classify_month(row.get("month"))
                c_status, c_value = classify_count(row.get("count"))
                if m_status == "valid":
                    summary["month_valid"] += 1
                elif m_status == "missing":
                    summary["month_missing"] += 1
                else:
                    summary["month_illegal"] += 1
                    summary["illegal_details"].append(
                        (line_no, "month", row.get("month"), m_value)
                    )
                if c_status == "valid":
                    summary["count_valid"] += 1
                elif c_status == "missing":
                    summary["count_missing"] += 1
                else:
                    summary["count_illegal"] += 1
                    summary["illegal_details"].append(
                        (line_no, "count", row.get("count"), c_value)
                    )
                if m_status == "valid" and c_status == "valid":
                    summary["month_totals"][m_value] = (
                        summary["month_totals"].get(m_value, 0) + c_value
                    )
    except FileNotFoundError:
        print("错误：文件不存在：%s" % path, file=sys.stderr)
        return None
    except PermissionError:
        print("错误：无权限读取文件：%s" % path, file=sys.stderr)
        return None
    except UnicodeDecodeError:
        print("错误：文件编码无法解析，请确认是否为 UTF-8。", file=sys.stderr)
        return None
    except csv.Error as exc:
        print("错误：CSV 解析失败：%s" % exc, file=sys.stderr)
        return None
    return summary


def print_summary(summary):
    print("=== 青禾服务中心月度服务量数据质量检查摘要 ===")
    print("总行数：%d" % summary["total"])
    print("month 有效：%d，缺失：%d，非法：%d" % (
        summary["month_valid"], summary["month_missing"], summary["month_illegal"]))
    print("count 有效：%d，缺失：%d，非法：%d" % (
        summary["count_valid"], summary["count_missing"], summary["count_illegal"]))
    if summary["illegal_details"]:
        print("--- 非法值明细（行号, 列, 原始值, 原因）---")
        for line_no, column, raw, reason in summary["illegal_details"]:
            print("第 %d 行 | %s | %r | %s" % (line_no, column, raw, reason))
    else:
        print("非法值明细：无")
    if summary["month_totals"]:
        print("--- 按月有效服务量合计 ---")
        for month in sorted(summary["month_totals"]):
            print("%s：%d" % (month, summary["month_totals"][month]))
    else:
        print("按月有效服务量合计：无有效记录")


def main():
    parser = argparse.ArgumentParser(
        description="检查月度服务量 CSV 的 month 与 count 字段质量。")
    parser.add_argument("csv_path", help="操作者主动指定的本地 CSV 文件路径")
    args = parser.parse_args()
    summary = check_file(args.csv_path)
    if summary is None:
        sys.exit(1)
    print_summary(summary)


if __name__ == "__main__":
    main()
```

## 最小运行示例

```bash
python3 check_service_volume.py /path/to/service_volume.csv
```

示例说明：将 `/path/to/service_volume.csv` 替换为操作者本地实际 CSV 路径。脚本读取该文件，校验 month 与 count 两列，将检查摘要输出到标准输出；缺失值保持缺失、不补零，非法值单独列出并注明行号与原因；原文件不被修改。若文件不存在、无权限、编码异常或缺少必需列，脚本输出清晰错误信息并以非零状态退出。