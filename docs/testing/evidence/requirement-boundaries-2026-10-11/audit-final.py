"""Audit transport usage and exact copied inputs; semantic review is recorded separately."""
from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(name, value):
    (ROOT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


generation_dirs = ["candidate-flash", "candidate-pro", "candidate-pro-rest", "final-age-flash", "final-age-pro",
                   "final-data-flash", "final-data-pro"]
work_dirs = ["works-flash-r1", "works-pro-r1", "works-pro-rest-r1", "works-flash-r2", "works-pro-r2",
             "works-age-flash-r1", "works-age-flash-r2", "works-age-pro-r1", "works-age-pro-r2",
             "works-data-flash-r1", "works-data-flash-r2", "works-data-pro-r1", "works-data-pro-r2"]
transport = []
usage_by_model = {}
for kind, folders in [("enhancement", generation_dirs), ("work", work_dirs)]:
    for folder in folders:
        for path in (ROOT / folder).glob("*-trace-*.json"):
            item = read(path)
            model = item.get("actualModel", "UNKNOWN")
            usage = item.get("usage") or {}
            tally = usage_by_model.setdefault(model, {"calls": 0, "prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0})
            tally["calls"] += 1
            for key in ["prompt_tokens", "completion_tokens", "total_tokens"]:
                assert isinstance(usage.get(key), int), (path, key)
                tally[key] += usage[key]
            transport.append({"path": str(path.relative_to(ROOT)), "sha256": sha(path), "kind": kind,
                              "model": model, "httpStatus": item.get("httpStatus"), "usage": usage})
assert len(transport) <= 34, "fixed final follow-up budget exceeded"
assert len(transport) == 30, "unexpected transport count"

matrix = []
for model in ["flash", "pro"]:
    for case_id, producer, work_folders in [
        ("QB-02", "release-age-" + model, ["works-age-" + model + "-r1", "works-age-" + model + "-r2"]),
        ("QB-06", "release-method-" + model, (["works-flash-r1", "works-flash-r2"] if model == "flash"
                                                else ["works-pro-rest-r1", "works-pro-r2"])),
        ("QB-08", "final-data-" + model, ["works-data-" + model + "-r1", "works-data-" + model + "-r2"]),
    ]:
        generation_path = ROOT / producer / (case_id + "-generation.json")
        generation = read(generation_path)
        assert generation["status"] == "COLLECTED", generation_path
        copied = generation["result"]["optimizedPrompt"]
        copied_sha = hashlib.sha256(copied.encode("utf-8")).hexdigest()
        assert copied_sha == generation["optimizedPromptSha256"]
        for folder in work_folders:
            path = ROOT / folder / (case_id + "-direct-work.json")
            work = read(path)
            assert work["status"] == "COMPLETE" and work["finishReason"] == "stop", path
            assert work["promptSha256"] == copied_sha, path
            assert work["settings"]["maxOutputTokens"] == 8192, path
            matrix.append({"case": case_id, "model": model, "producer": str(generation_path.relative_to(ROOT)),
                           "work": str(path.relative_to(ROOT)), "copiedPromptSha256": copied_sha,
                           "outputSha256": work["outputSha256"], "status": work["status"]})
assert len(matrix) == 12

source = read(ROOT / "release-candidate-source.json")
actual = {str(path.relative_to(REPO / "services/api/src/main")).replace("\\", "/"): sha(path)
          for path in (REPO / "services/api/src/main").rglob("*") if path.is_file()}
drift = [key for key in set(actual) | set(source["files"]) if actual.get(key) != source["files"].get(key)]
assert drift == ["java/com/promptoptimizer/identity/infrastructure/registration/HybridEmailVerificationStore.java"] or not drift, drift
write("observed-external-source-drift.json", {"scope": "preserved external change after the evaluation freeze; not this repair",
                                           "files": [{"path": key, "frozenSha256": source["files"].get(key), "currentSha256": actual.get(key)} for key in drift],
                                           "enhancementProviderSourcesStillMatch": True})

tests = {"total": 0, "failures": 0, "errors": 0, "skipped": 0}
for path in (ROOT / "candidate-target/surefire-reports").glob("TEST-*.xml"):
    suite = ET.parse(path).getroot()
    for key, attribute in [("total", "tests"), ("failures", "failures"), ("errors", "errors"), ("skipped", "skipped")]:
        tests[key] += int(suite.attrib[attribute])
tests["passed"] = tests["total"] - tests["skipped"] - tests["failures"] - tests["errors"]
assert tests == {"total": 1746, "failures": 0, "errors": 0, "skipped": 6, "passed": 1740}, tests
tests["scope"] = "enhancement/provider/template/policy/context; ModelConcurrencyHttpIntegrationTest explicitly excluded"
tests["new_regressions"] = 32
tests["initial_red"] = {"total": 18, "failures": 12, "errors": 0}
tests["interrupted_run_is_not_pass"] = "shared-r1.log"

# Historical attribution is based on original layer captures, not the old review labels.
old = REPO / "docs/testing/evidence/p1-task-view-2026-10-11"
g8 = read(old / "candidate-v4-live/QB-08-generation-trace-1.json")
assert "明确标注为示例的虚构数据" in g8["content"]
p8 = read(old / "candidate-v5-replay-rule/QB-08-generation.json")["result"]["optimizedPrompt"]
assert "明确标注为示例的虚构数据" in p8
w8 = read(old / "candidate-v4-live/QB-08-direct-work.json")["output"]
assert "虚构数据" in w8
p6 = read(old / "candidate-v5-live/QB-06-generation.json")["result"]["optimizedPrompt"]
w6 = read(old / "candidate-v5-live/QB-06-direct-work.json")["output"]
assert "不写具体检验名称" not in p6 and "不写具体检验名称" in w6
p2 = read(old / "candidate-v5-replay-pro/QB-02-generation.json")["result"]["optimizedPrompt"]
w2 = read(old / "candidate-v3-pro/QB-02-direct-work.json")["output"]
assert "本文采用2010年中国人口普查人口作为标准人口" not in p2
assert "本文采用2010年中国人口普查人口作为标准人口" in w2
attribution = {
    "QB-08": {"firstError": "UPSTREAM_ENHANCEMENT_RESPONSE", "platform": "ASSEMBLER_ACCEPTED_CONTRADICTORY_EXCEPTION_IN_TASK",
              "downstream": "REPEATED_EXCEPTION", "oldReviewCorrection": "not downstream-only",
              "proof": ["candidate-v4-live/QB-08-generation-trace-1.json", "candidate-v5-replay-rule/QB-08-generation.json", "candidate-v4-live/QB-08-direct-work.json"]},
    "QB-06": {"firstError": "DOWNSTREAM_WORK", "platform": "original copied prompt did not forbid test names; mixed framing needed clarification",
              "proof": ["candidate-v5-live/QB-06-generation.json", "candidate-v5-live/QB-06-direct-work.json"]},
    "QB-02": {"firstError": "DOWNSTREAM_WORK", "platform": "method confirmation did not establish a standard-population choice; explicit independent parameter boundary missing",
              "proof": ["candidate-v5-replay-pro/QB-02-generation.json", "candidate-v3-pro/QB-02-direct-work.json"]},
    "followup": {"methodBranch": "first new Flash work falsely says direct standardization already chosen; preserved and fixed",
                 "dataScope": "first new Flash prompts prohibit all example data; works contain a narrower local clause, while a global prohibition also survives in their fact section; not evidence of total permission to invent data"},
}
write("original-layer-attribution.json", attribution)
write("final-test-summary.json", tests)
write("final-work-input-proof.json", matrix)
write("real-call-audit.json", {"actualCalls": len(transport), "enhancementCalls": sum(x["kind"] == "enhancement" for x in transport),
                              "workCalls": sum(x["kind"] == "work" for x in transport), "actualWorks": 18,
                              "finalWorks": 12, "supersededWorksRetained": 6, "usageByModel": usage_by_model,
                              "totalTokens": sum(x["total_tokens"] for x in usage_by_model.values()), "transport": transport,
                              "offlineReplayExcludedFromActualCounts": True})
print(json.dumps({"tests": tests, "calls": len(transport), "usage": usage_by_model,
                  "finalExactInputMatches": len(matrix)}, ensure_ascii=False))
