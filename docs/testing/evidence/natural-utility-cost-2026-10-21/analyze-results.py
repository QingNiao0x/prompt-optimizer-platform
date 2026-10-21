"""离线核对本批全部传输、同信息作品、历史一致性与成本，不调用模型或重写原件。"""

import hashlib
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent
VERIFY = sys.argv[1:] == ["--verify"]
if sys.argv[1:] and not VERIFY:
    raise SystemExit("Only --verify is supported")


def read(name):
    return json.loads((ROOT / name).read_text(encoding="utf-8"))


def write(name, value):
    """分析结果只新增，不覆盖此前冻结的证据。"""
    write_exact(name, json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def write_exact(name, value):
    """复核时逐字节比较既有产物，不能覆盖原件来掩盖不一致。"""
    encoded = value.encode("utf-8")
    if VERIFY:
        assert (ROOT / name).read_bytes() == encoded, name
    else:
        with (ROOT / name).open("xb") as stream:
            stream.write(encoded)


def totals(rows):
    result = {"calls": len(rows), "promptTokens": 0, "completionTokens": 0,
              "totalTokens": 0, "cacheHitTokens": 0, "cacheMissTokens": 0, "upstreamElapsedMs": 0}
    for row in rows:
        usage = row["response"]["usage"]
        assert usage["total_tokens"] == usage["prompt_tokens"] + usage["completion_tokens"]
        result["promptTokens"] += usage["prompt_tokens"]
        result["completionTokens"] += usage["completion_tokens"]
        result["totalTokens"] += usage["total_tokens"]
        result["cacheHitTokens"] += usage["prompt_cache_hit_tokens"]
        result["cacheMissTokens"] += usage["prompt_cache_miss_tokens"]
        assert usage["prompt_tokens"] == usage["prompt_cache_hit_tokens"] + usage["prompt_cache_miss_tokens"]
        result["upstreamElapsedMs"] += row["elapsedMs"]
    return result


def change(old, new):
    return {key: round((new[key] / old[key] - 1) * 100, 3)
            for key in ["promptTokens", "completionTokens", "totalTokens", "upstreamElapsedMs"]}


def inside_response(row):
    """仅复现既有stop响应单个根闭合兼容；不修订留存的模型原文。"""
    choice = row["response"]["choices"][0]
    assert choice["finish_reason"] == "stop"
    content = choice["message"]["content"]
    try:
        return json.loads(content), False
    except json.JSONDecodeError:
        return json.loads(content + "}"), True


def by_job(rows, identifier):
    return [row for row in rows if row["jobId"] == identifier]


def main():
    traces = [json.loads(line) for line in (ROOT / "transports.jsonl").read_text(encoding="utf-8").splitlines()]
    results = {row["id"]: row for row in read("http-results.json")}
    cases = {row["id"]: row for row in read("cases.json")["cases"]}
    protocol = read("protocol.json")
    closure = read("closure.json")
    assert len(traces) == closure["actualCalls"] == 29
    assert len(results) == 27
    assert traces[:26] == [json.loads(line) for line in
            (ROOT / "transports-candidate1.jsonl").read_text(encoding="utf-8").splitlines()]
    assert all(row["httpStatus"] == 200 for row in traces)
    phases = {phase: sum(row["phase"] == phase for row in traces) for phase in closure["phases"]}
    assert phases == closure["phases"]

    source_checks = []
    for line, row in enumerate(traces, 1):
        if row["phase"] != "GENERATION":
            continue
        body, closed_root = inside_response(row)
        user = row["request"]["messages"][1]["content"]
        payload = json.loads(user[user.index("{"):])
        required = {"rawPrompt"} | {f"actualAnswers.{index}" for index in range(len(payload["actualAnswers"]))}
        cited = {evidence["sourceId"] for requirement in body["taskUnderstanding"]["requirements"]
                 for evidence in requirement["evidence"]}
        source_checks.append({"transportLine": line, "jobId": row["jobId"], "missingSourceIds": sorted(required - cited),
                              "singleRootClosureCompatibility": closed_root})
    assert [row["transportLine"] for row in source_checks if row["missingSourceIds"]] == [14, 15, 28]

    direct = {}
    per_case = []
    for version in ["4", "5"]:
        identifiers = {f"{case}_V{version}_GEN" for case in protocol["generation"]}
        direct[version] = totals([row for row in traces if row["jobId"] in identifiers])
        assert direct[version]["calls"] == 5
    for case in protocol["generation"]:
        old = totals(by_job(traces, f"{case}_V4_GEN"))
        new = totals(by_job(traces, f"{case}_V5_GEN"))
        per_case.append({"caseId": case, "v4": old, "v5": new, "changePercent": change(old, new),
                         "bodyCharactersV4": len(results[f"{case}_V4_GEN"]["body"]["data"]["optimizedPrompt"]),
                         "bodyCharactersV5": len(results[f"{case}_V5_GEN"]["body"]["data"]["optimizedPrompt"])})

    works = []
    (ROOT / "works").mkdir(exist_ok=True)
    for case in protocol["execution"]:
        sample = cases[case]
        common_system = None
        for version in ["RAW", "V4", "V5"]:
            identifier = f"{case}_{version}_EXEC"
            row = by_job(traces, identifier)[0]
            request = row["request"]
            assert request["model"] == protocol["upstreamModel"] and request["temperature"] == 0.2
            assert request["max_tokens"] == 8192 and request["thinking"] == {"type": "disabled"} and not request["stream"]
            system = request["messages"][0]["content"]
            if common_system is None:
                common_system = system
            assert system == common_system
            prompt = sample["rawPrompt"] if version == "RAW" else results[f"{case}_{version}_GEN"]["body"]["data"]["optimizedPrompt"]
            material = "\n\n用户同时提供的材料：\n" + json.dumps(sample["files"], ensure_ascii=False, separators=(",", ":")) if sample["files"] else ""
            assert request["messages"][1]["content"] == prompt + material
            content = row["response"]["choices"][0]["message"]["content"]
            suffix = "json" if case == "SUFFICIENT_JSON" else "md"
            relative = f"works/{identifier}.{suffix}"
            write_exact(relative, content)
            checks = {}
            if case == "SUFFICIENT_JSON":
                actual = json.loads(content)
                assert set(actual) == {"members", "pending"}
                assert sorted(actual["members"], key=lambda member: member["name"]) == sorted(sample["expected"]["members"], key=lambda member: member["name"])
                assert sorted(actual["pending"]) == sorted(sample["expected"]["pending"])
                checks = {"strictWholeResponseJson": True, "independentStatesCorrect": True, "extraKeys": False}
            if case == "DELEGATED_PPT":
                pages = re.findall(r"^## 第([1-6])页", content, flags=re.M)
                assert pages == [str(page) for page in range(1, 7)]
                checks = {"sixPageHeadings": True, "twoCorePointsAndSpeakerNotes": "CHECKED_MANUALLY",
                          "fullDurationFeasibility": "CHECKED_MANUALLY_NOT_A_STOPWATCH_TEST"}
            if case == "VAGUE_REPORT":
                checks = {"noNumericLengthRequirement": True, "factualAndPracticalQuality": "SEE_MANUAL_ASSESSMENT_NOT_A_PASS"}
            works.append({"jobId": identifier, "file": relative, "characters": len(content),
                          "nonWhitespaceCharacters": len(re.sub(r"\s", "", content)),
                          "sha256": hashlib.sha256(content.encode("utf-8")).hexdigest(), "sameEffectiveInformation": True,
                          "checks": checks})

    history = read("history-checks.json")["checks"] + read("history-checks-candidate2.json")["checks"]
    assert len(history) == 12
    assert all(row["status"] == 200 and all(row[key] for key in
               ["bodyEqual", "versionEqual", "noDefaultRules", "rulesEqual", "noInternalReviewOnWire"]) for row in history)
    for case in ["SUFFICIENT_MESSAGE", "SUFFICIENT_JSON"]:
        assert results[f"{case}_V5_GEN"]["body"]["data"]["optimizedPrompt"] == cases[case]["rawPrompt"]

    old_plan = totals([row for row in traces if row["jobId"] in {"PLAN_PURCHASE_V4_PLAN", "PLAN_PURCHASE_V4_PLAN_GEN"}])
    new_plan = totals([row for row in traces if row["jobId"] in {"PLAN_PURCHASE_V5_C2_PLAN", "PLAN_PURCHASE_V5_C2_PLAN_GEN"}])
    by_phase = {phase: totals([row for row in traces if row["phase"] == phase]) for phase in phases}
    source_a = {row["path"]: row["sha256"] for row in read("source-manifest.json")["files"] if row["path"].startswith("services/api/src/main/")}
    source_b = {row["path"]: row["sha256"] for row in read("source-manifest-candidate2.json")["files"] if row["path"].startswith("services/api/src/main/")}
    changed = [name for name in source_a.keys() | source_b.keys() if source_a.get(name) != source_b.get(name)]
    assert changed == ["services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatibleNaturalPromptProvider.java"]

    write("cost-analysis.json", {"allActualCallsIncludingFailures": totals(traces), "byPhase": by_phase,
           "direct": {"v4": direct["4"], "v5": direct["5"], "changePercent": change(direct["4"], direct["5"])},
           "directPerCase": per_case, "confirmedPlan": {"v4": old_plan, "v5Correction": new_plan, "changePercent": change(old_plan, new_plan),
           "freshPlanAndQuestionsDiffer": True, "failedCandidate1GenerationCallsAlsoInWholeBatch": 2},
           "moneyEstimated": False, "cacheNotControlled": True,
           "notes": ["Single samples and public model alias; no weight snapshot or stable latency inference.",
                     "Candidate2 only changes answered generation; fresh Plan is required after in-memory context restart, not an identical-question causal experiment.",
                     "Direct v5 has identical request bytes across C1 and C2; source contract correction does not touch direct generation or old versions."]})
    write("artifact-audit.json", {"works": works, "manualAssessment": "manual-assessment.json", "historyChecks": 12,
           "allHistoryBodiesVersionsRulesEqual": True, "newAccounts": 0, "sourceChecks": source_checks,
           "sourceGuardRejections": [14, 15, 28], "sameSevereHypothesisNotExpandedFurther": True,
           "candidate1To2ChangedProductionSource": changed, "adoption": "KEEP_DEFAULT_NATURAL_4_CANDIDATE_5_NOT_ADOPTED"})
    print(json.dumps({"calls": len(traces), "works": len(works), "historyChecks": len(history),
                      "directTokenChangePercent": change(direct["4"], direct["5"])["totalTokens"],
                      "default": "4", "candidateAdopted": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
