"""离线重算本批全部成本、同信息条件和作品原件，不调用模型或替代语义验收。"""

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


def save(name, value):
    """复核不覆盖原件；首次只新增分析文件，UTF-8及LF保持可比字节。"""
    content = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    path = ROOT / name
    if VERIFY:
        assert path.read_bytes() == content, name
    else:
        with path.open("xb") as stream:
            stream.write(content)


def totals(rows):
    result = {"calls": len(rows), "inputTokens": 0, "outputTokens": 0, "totalTokens": 0,
              "cacheHitTokens": 0, "cacheMissTokens": 0, "upstreamElapsedMs": 0}
    for row in rows:
        usage = row["response"]["usage"]
        assert usage["total_tokens"] == usage["prompt_tokens"] + usage["completion_tokens"]
        assert usage["prompt_tokens"] == usage["prompt_cache_hit_tokens"] + usage["prompt_cache_miss_tokens"]
        for key, source in [("inputTokens", "prompt_tokens"), ("outputTokens", "completion_tokens"),
                            ("totalTokens", "total_tokens"), ("cacheHitTokens", "prompt_cache_hit_tokens"),
                            ("cacheMissTokens", "prompt_cache_miss_tokens")]:
            result[key] += usage[source]
        result["upstreamElapsedMs"] += row["elapsedMs"]
    return result


def inner(row):
    """只复现原有stop响应单个根闭合兼容，不改留存原文。"""
    choice = row["response"]["choices"][0]
    assert choice["finish_reason"] == "stop"
    text = choice["message"]["content"]
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return json.loads(text + "}")


def main():
    traces = [json.loads(line) for line in (ROOT / "transports.jsonl").read_text(encoding="utf-8").splitlines()]
    results = {row["id"]: row for row in read("http-results.json")}
    cases = {row["id"]: row for row in read("cases.json")["cases"]}
    protocol = read("protocol.json")
    closure = read("closure.json")
    assert len(traces) == closure["actualCalls"] == 27 and len(results) == 26
    assert all(row["status"] == 200 for row in results.values())
    assert all(row["httpStatus"] == 200 for row in traces)
    assert {phase: sum(row["phase"] == phase for row in traces) for phase in closure["phases"]} == closure["phases"]
    old = results["PLAN_PURCHASE_V4_PLAN_GEN"]["request"]
    new = results["PLAN_PURCHASE_V6_PLAN_GEN"]["request"]
    assert old == new and old["planConfirmation"] == read("common-confirmation.json")["confirmation"]

    source_checks = []
    for line, row in enumerate(traces, 1):
        if row["phase"] not in {"PLAN", "GENERATION"}:
            continue
        request = row["request"]
        user = request["messages"][1]["content"]
        payload = json.loads(user[user.index("{"):])
        if payload["strategyVersion"] == "6":
            expected_phase = "PLANNING" if row["phase"] == "PLAN" else "GENERATE_AFTER_ANSWERS" if payload["actualAnswers"] else "GENERATE_DIRECT"
            assert payload["executionContext"]["phase"] == expected_phase
            assert payload["executionContext"]["receivedAnswerSourceIds"] == [f"actualAnswers.{i}" for i in range(len(payload["actualAnswers"]))]
        else:
            assert "executionContext" not in payload
        task = inner(row)["taskUnderstanding"]
        required = {"rawPrompt"} | {f"actualAnswers.{i}" for i in range(len(payload["actualAnswers"]))}
        cited = {e["sourceId"] for r in task["requirements"] for e in r["evidence"]}
        source_checks.append({"transportLine": line, "jobId": row["jobId"], "missingSourceIds": sorted(required - cited)})
    assert [row["transportLine"] for row in source_checks if row["missingSourceIds"]] == [14]

    pairs = []
    for case in protocol["generation"] + ["PLAN_PURCHASE"]:
        suffix = "_PLAN_GEN" if case == "PLAN_PURCHASE" else "_GEN"
        pair = {"caseId": case}
        for version in ["4", "6"]:
            identifier = f"{case}_V{version}{suffix}"
            prompt = results[identifier]["body"]["data"]["optimizedPrompt"]
            pair["v" + version] = totals([row for row in traces if row["jobId"] == identifier]) | {"promptCharacters": len(prompt)}
        pair["totalTokenChangePercent"] = round((pair["v6"]["totalTokens"] / pair["v4"]["totalTokens"] - 1) * 100, 3)
        pairs.append(pair)

    works = []
    for case in protocol["execution"]:
        sample = cases[case]
        common_system = None
        for arm in ["RAW", "V4", "V6"]:
            identifier = f"{case}_{arm}_EXEC"
            row = next(row for row in traces if row["jobId"] == identifier)
            request = row["request"]
            assert request["model"] == protocol["upstreamModel"] and request["temperature"] == 0.2
            assert request["max_tokens"] == 8192 and request["thinking"] == {"type": "disabled"} and not request["stream"]
            system = request["messages"][0]["content"]
            if common_system is None:
                common_system = system
            assert system == common_system
            suffix = "_PLAN_GEN" if case == "PLAN_PURCHASE" else "_GEN"
            prompt = sample["rawPrompt"] if arm == "RAW" else results[f"{case}_{arm}{suffix}"]["body"]["data"]["optimizedPrompt"]
            material = "\n\n用户同时提供的材料：\n" + json.dumps(sample["files"], ensure_ascii=False, separators=(",", ":")) if sample["files"] else ""
            answers = "\n\n用户已提供的本次答复（未知项保持未知）：\n" + sample["answerBundle"] if sample.get("answerBundle") else ""
            assert request["messages"][1]["content"] == prompt + material + answers
            choice = row["response"]["choices"][0]
            assert choice["finish_reason"] == "stop"
            content = choice["message"]["content"]
            extension = "json" if case == "SUFFICIENT_JSON" else "md"
            filename = f"works/{identifier}.{extension}"
            assert (ROOT / filename).read_bytes() == content.encode("utf-8")
            checks = {"sameEffectiveInformation": True}
            if case == "SUFFICIENT_JSON":
                actual = json.loads(content)
                expected = sample["expected"]
                assert set(actual) == set(expected)
                assert sorted(actual["members"], key=lambda member: member["name"]) == sorted(expected["members"], key=lambda member: member["name"])
                assert sorted(actual["pending"]) == sorted(expected["pending"])
                checks["strictWholeJsonAndIndependentStates"] = True
            if case == "DELEGATED_PPT":
                assert re.findall(r"^## 第([1-6])页", content, flags=re.M) == [str(i) for i in range(1, 7)]
                checks["sixPageHeadings"] = True
            works.append({"jobId": identifier, "file": filename, "characters": len(content),
                          "sha256": hashlib.sha256(content.encode("utf-8")).hexdigest(), "checks": checks})

    history = read("history-checks.json")["checks"]
    assert len(history) == 10
    assert all(row["status"] == 200 and all(row[key] for key in ["bodyEqual", "versionEqual", "noDefaultRules", "rulesEqual", "noInternalReviewOnWire"]) for row in history)
    save("cost-analysis.json", {"allCallsIncludingRejectedResponse": totals(traces),
         "byPhase": {phase: totals([row for row in traces if row["phase"] == phase]) for phase in closure["phases"]},
         "generationPairs": pairs, "moneyEstimated": False, "cacheNotControlled": True,
         "sameActualPlanAndAnswersForConfirmedGeneration": True, "qualityCannotBeInferredFromTokens": True})
    save("artifact-audit.json", {"works": works, "sourceChecks": source_checks, "missingSourceResponseLines": [14],
         "sameActualPlanAndAnswersForConfirmedGeneration": True, "historyChecks": 10, "allHistoryChecksPassed": True,
         "manualAssessment": "manual-assessment.json", "adoption": "NOT_ADOPTED_KEEP_DEFAULT_4"})
    print(json.dumps({"calls": 27, "works": 12, "historyChecks": 10, "defaultVersion": "4", "candidateAdopted": False}))


if __name__ == "__main__":
    main()
