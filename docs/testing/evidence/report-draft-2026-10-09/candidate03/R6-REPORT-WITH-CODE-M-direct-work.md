# 青禾服务中心月度服务量数据质量报告（初稿）

## 一、资料范围与研究对象

本报告针对青禾服务中心月度服务量数据开展质量核查，研究对象为按自然月记录的服务量台账。截至本稿撰写时，可核实的资料仅为一份 CSV 数据字典（materials/csv-dictionary.md），其中约定该 CSV 包含 month、count 两列：month 采用 YYYY-MM 格式且月份须为 01 至 12；count 为非负整数；空单元表示缺失，不得替换为 0；负数、小数、非数字或非法月份应报告为非法值，不能静默丢弃。字典同时说明尚未提供任何数据行。因此，本报告只能就字段定义、检查口径与流程作出说明，凡涉及实际记录数、缺失率、异常月份分布或服务量趋势的结论，均须待实际 CSV 到位后方能得出，本稿不作推测，相应位置以所需输入标记。

## 二、指标定义与统计口径

本次核查以“行”为基本单位，以 month 与 count 两个字段为对象。month 的合规口径为：字符串严格匹配 YYYY-MM，年份为四位数字，月份为 01 至 12 的两位数字；不满足者记为非法月份。count 的合规口径为：可解析为非负整数；负数、小数、非数字文本均记为非法值。空单元（含仅空白）一律记为缺失，保持缺失状态，不补零、不参与数值汇总。缺失与非法值分属两类，分别计数、分别报告，避免将格式错误混入缺失统计。上述口径直接来自数据字典，未作扩展；若后续业务方对“合法年份范围”或“重复月份如何处理”另有约定，属尚未确认事项，需在正式版中补充说明。

## 三、检查方法与结果位置

检查拟采用 Python 3 标准库实现，流程为：由操作者主动指定本地 CSV 文件路径；以文本方式读取，不写回、不修改原文件；逐行解析 month 与 count；对每个字段分别判定“合规、缺失、非法”；汇总总行数、合规行数、缺失数、非法数，并按类别输出示例行号，便于回溯。检查摘要输出至标准输出，不生成中间文件。结果呈现位置安排如下：总体规模与合规情况见“核查结果”一节；缺失与非法值的分类计数见该节附表；异常示例见附表“示例行号”列。由于实际数据尚未提供，本节暂不能给出任何实证数值，所需输入为：待检查 CSV 文件路径及其数据行。在数据到位前，以下结论均标注为待补：总记录数[待补：实际行数]、month 缺失数[待补：实际计数]、month 非法数[待补：实际计数]、count 缺失数[待补：实际计数]、count 非法数[待补：实际计数]、异常示例行号[待补：实际行号]。

## 四、局限与下一步建议

本稿的主要局限有三：其一，仅有数据字典而无数据行，所有量化结论均无法核实，报告不具备实证效力；其二，字典未明确年份合理区间、重复月份的处理规则以及 count 是否存在业务上限，这些属口径未决项；其三，单文件检查无法发现跨月缺报或口径变更等系统性问题。下一步建议：第一，尽快提供实际 CSV，按本报告口径运行检查脚本，形成含具体计数的正式版；第二，由业务方确认年份范围与重复月份处理规则，确认后再固化校验逻辑；第三，对判定为缺失的记录，回溯采集环节，区分“确无服务量”与“漏报”，不得以 0 填充；第四，对非法值建立修正台账，保留原始值与修正依据，确保过程可复现。以上建议以数据字典现有约定为前提，若业务口径调整，需同步修订本报告。

## 附表：核查结果登记表（数据到位后填写）

| 项目 | 计数 | 说明 |
| --- | --- | --- |
| 总记录数 | [待补：实际行数] | 数据到位后统计 |
| month 合规数 | [待补：实际计数] | 严格匹配 YYYY-MM |
| month 缺失数 | [待补：实际计数] | 空单元，保持缺失 |
| month 非法数 | [待补：实际计数] | 格式或月份越界 |
| count 合规数 | [待补：实际计数] | 非负整数 |
| count 缺失数 | [待补：实际计数] | 空单元，不补零 |
| count 非法数 | [待补：实际计数] | 负数、小数、非数字 |
| 异常示例行号 | [待补：实际行号] | 供回溯核对 |

## 检查脚本（Python 3，仅标准库）

```python
#!/usr/bin/env python3
"""检查 CSV 中 month 与 count 字段的数据质量，仅读取指定文件，不修改原文件。"""

import csv
import re
import sys

MONTH_RE = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")


def classify_month(value):
    if value is None or value.strip() == "":
        return "missing"
    return "ok" if MONTH_RE.match(value.strip()) else "invalid"


def classify_count(value):
    if value is None or value.strip() == "":
        return "missing"
    text = value.strip()
    if not re.match(r"^[+-]?\d+$", text):
        return "invalid"
    return "ok" if int(text) >= 0 else "invalid"


def check_file(path):
    summary = {
        "total": 0,
        "month_ok": 0, "month_missing": 0, "month_invalid": 0,
        "count_ok": 0, "count_missing": 0, "count_invalid": 0,
        "examples": [],
    }
    try:
        with open(path, "r", encoding="utf-8-sig", newline="") as handle:
            reader = csv.DictReader(handle)
            if reader.fieldnames is None:
                print("错误：文件为空或缺少表头。", file=sys.stderr)
                return None
            missing_cols = [c for c in ("month", "count") if c not in reader.fieldnames]
            if missing_cols:
                print("错误：缺少必需列：" + ", ".join(missing_cols), file=sys.stderr)
                return None
            for line_no, row in enumerate(reader, start=2):
                summary["total"] += 1
                m_state = classify_month(row.get("month"))
                c_state = classify_count(row.get("count"))
                summary["month_" + m_state] += 1
                summary["count_" + c_state] += 1
                if m_state != "ok" or c_state != "ok":
                    if len(summary["examples"]) < 20:
                        summary["examples"].append(
                            (line_no, row.get("month"), row.get("count"), m_state, c_state)
                        )
    except FileNotFoundError:
        print("错误：找不到文件：" + path, file=sys.stderr)
        return None
    except PermissionError:
        print("错误：无读取权限：" + path, file=sys.stderr)
        return None
    except UnicodeDecodeError:
        print("错误：文件编码无法按 UTF-8 解析：" + path, file=sys.stderr)
        return None
    except csv.Error as exc:
        print("错误：CSV 解析失败：" + str(exc), file=sys.stderr)
        return None
    return summary


def main():
    if len(sys.argv) != 2:
        print("用法：python check_quality.py <CSV文件路径>", file=sys.stderr)
        return 2
    summary = check_file(sys.argv[1])
    if summary is None:
        return 1
    print("=== 数据质量检查摘要 ===")
    print("总记录数：%d" % summary["total"])
    print("month 合规：%d，缺失：%d，非法：%d" % (
        summary["month_ok"], summary["month_missing"], summary["month_invalid"]))
    print("count 合规：%d，缺失：%d，非法：%d" % (
        summary["count_ok"], summary["count_missing"], summary["count_invalid"]))
    if summary["examples"]:
        print("异常示例（行号, month, count, month状态, count状态）：")
        for item in summary["examples"]:
            print("  %d, %r, %r, %s, %s" % item)
    else:
        print("异常示例：无")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

最小运行示例：

```bash
python check_quality.py ./monthly_service.csv
```

脚本仅读取操作者指定的文件，输出摘要至标准输出，不写入、不删除、不修改任何文件；缺失值保持缺失，非法值与缺失分别计数。