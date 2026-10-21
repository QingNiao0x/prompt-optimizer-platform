"""离线复算公开原件的调用、用量、历史一致及本批篇幅；不调用模型，不改原评分。"""
from pathlib import Path
import collections
import json
import re

REPO = Path(__file__).resolve().parents[4]
EVIDENCE = REPO / "docs/testing/evidence"
BATCHES = ["natural-completion-exemplars", "natural-action-exemplars", "natural-prepared-exemplars"]


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def visible_length(text):
    """处理本批实际使用的标题、强调、列表和表格标记，标点数字仍计入；不是通用Markdown解析器。"""
    lines = []
    for line in text.splitlines():
        if re.fullmatch(r"\s*(?:[-*_]\s*){3,}", line) or re.fullmatch(r"\s*\|?[\s:|\-]+\|?\s*", line):
            continue
        line = re.sub(r"^\s*#{1,6}\s*", "", line)
        line = re.sub(r"^\s*[-*+]\s+", "", line)
        lines.append(line.replace("*", "").replace("`", "").replace("|", ""))
    return len(re.sub(r"\s", "", "\n".join(lines)))


total = tokens = histories = 0
stage_counts = []
for batch in BATCHES:
    root = EVIDENCE / (batch + "-2026-10-22")
    protocol = read(root / "protocol.json")
    traces = [json.loads(line) for line in (root / "transports.jsonl").read_text(encoding="utf-8").splitlines()]
    counts = collections.Counter(trace["phase"] for trace in traces)
    assert len(traces) <= protocol["maximumNewActualCalls"]
    assert all(count <= protocol["phaseLimits"][phase] for phase, count in counts.items())
    for trace in traces:
        assert trace["jobId"] in protocol["allowedJobs"]
        request = trace["request"]
        assert request["model"] == protocol["upstreamModel"] and request["temperature"] == .2
        assert request["thinking"]["type"] == "disabled" and request["stream"] is False
    checks = read(root / "history-checks.json")["checks"]
    assert all(check["status"] == 200 and all(value for value in check.values() if isinstance(value, bool)) for check in checks)
    total += len(traces)
    tokens += sum(trace.get("response", {}).get("usage", {}).get("total_tokens", 0) for trace in traces)
    histories += len(checks)
    stage_counts.append({"batch": batch, "calls": len(traces), "phases": dict(counts)})

assert (total, tokens, histories) == (208, 500215, 67)
latest = EVIDENCE / "natural-prepared-exemplars-2026-10-22"
results = {result["id"]: result for result in read(latest / "http-results.json")}
lengths = []
for check in read(latest / "length-checks.json")["checks"]:
    text = results[check["caseId"] + "_" + check["arm"] + "_EXEC"]["body"]["data"]["content"]
    count = visible_length(text)
    assert count == check["visibleNonWhitespaceCharacters"]
    assert (count <= check["limit"]) == check["withinFrozenLimit"]
    lengths.append({"case": check["caseId"], "arm": check["arm"], "visibleLength": count})

print(json.dumps({"actualCalls": total, "tokens": tokens, "historyChecks": histories,
                 "stages": stage_counts, "lengths": lengths, "qualityJudgmentAutomated": False}, ensure_ascii=False))
