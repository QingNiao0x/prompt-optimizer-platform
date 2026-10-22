"""只读复算公开原件；不读账号、调用模型、覆盖原件或重新评分。"""
from pathlib import Path
from collections import Counter
import json
import hashlib

folder = Path(__file__).resolve().parent
read = lambda name: json.loads((folder / name).read_text(encoding="utf-8-sig"))
traces = [json.loads(line) for line in (folder / "transports.jsonl").read_text(encoding="utf-8").splitlines() if line]
results = read("http-results.json")
protocol, closure = read("protocol.json"), read("closure.json")
phases = dict(Counter(t["phase"] for t in traces))
assert len(traces) == closure["actualCalls"] <= protocol["maximumNewActualCalls"]
assert phases == closure["phaseCounts"]
assert all(n <= protocol["phaseLimits"][phase] for phase, n in phases.items())
assert all(t["jobId"] in protocol["allowedJobs"] for t in traces)
assert sum(n - 1 for n in Counter(t["jobId"] for t in traces).values()) == closure["protocolRetries"]
by_id = {r["id"]: r for r in results}
pairs = []
for case in protocol["generation"] + protocol["confirmedPlanGeneration"]:
    suffix = "_PLAN_GEN" if case in protocol["confirmedPlanGeneration"] else "_GEN"
    pairs.append(by_id[case + "_V4" + suffix]["request"] == by_id[case + "_V20" + suffix]["request"])
assert len(pairs) == 14 and all(pairs)
history = read("history-checks.json")["checks"]
assert len(history) == 27 and all(r["status"] == 200 and all(r[k] for k in
    ["bodyEqual", "versionEqual", "noDefaultRules", "rulesEqual", "noInternalReviewOnWire"]) for r in history)
for row in read("candidate-source-checks.json")["checks"]:
    assert hashlib.sha256((folder / row["candidateFile"]).read_bytes()).hexdigest() == row["sha256"]
works = [r for r in results if r["phase"] == "EXECUTION"]
print(json.dumps({"actualCalls": len(traces), "logicalResults": len(results), "phaseCounts": phases,
    "reportedTokens": sum(t.get("response", {}).get("usage", {}).get("total_tokens", 0) for t in traces),
    "protocolRetries": closure["protocolRetries"], "qualityRetries": 0, "sameInputPairs": len(pairs),
    "historyChecks": len(history), "worksAttempted": len(works),
    "worksReturned": sum(r["status"] == 200 for r in works), "defaultVersion": closure["defaultVersionAfter"],
    "semanticAssessment": "See independent assessments; mechanical checks do not establish quality."},
    ensure_ascii=False, indent=2))
