"""核对新生成的受影响报告后冻结候选二十七，不覆盖任何历史候选或真实原件。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET


def sha(path):
    """所有来源、二进制及原件均按原字节计量。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def tree(rows):
    return hashlib.sha256("".join(row["path"] + ":" + row["sha256"] + "\n" for row in rows).encode()).hexdigest()


def main():
    """冻结前校验测试输入未变、报告新鲜、生产改动仅两条责任路径、无新增模型调用。"""
    public = Path(__file__).resolve().parent
    root = public.parents[3]
    work = root / "tmp/me00a-candidate27-20261017"
    parent_file = root / "docs/testing/evidence/me00a-candidate26-r2-offline-2026-10-17/candidate26-r2-source.json"
    parent = read(parent_file)
    assert not (work / "frozen").exists(), "Frozen candidate must never be overwritten"
    inputs = read(work / "source-under-test.json")
    for row in inputs:
        assert sha(root / row["path"]) == row["sha256"], row["path"]
    started = read(work / "affected-started.json")
    started_at = datetime.fromisoformat(started["startedAt"])
    reports = []
    for path in sorted((work / "build/surefire-reports").glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        assert datetime.fromtimestamp(path.stat().st_mtime, timezone.utc) >= started_at, path
        reports.append({"class": suite.get("name"), **{key: int(suite.get(key, 0)) for key in
                       ("tests", "failures", "errors", "skipped")}, "originalReportSha256": sha(path)})
    assert {row["class"].split(".")[-1] for row in reports} == set(started["classes"])
    counts = {"classes": len(reports), **{key: sum(row[key] for row in reports) for key in
              ("tests", "failures", "errors", "skipped")}, "reports": reports}
    assert counts["tests"] >= 1475 and not any(counts[key] for key in ("failures", "errors", "skipped"))
    targets = [row for row in reports if "CandidateTwentySeven" in row["class"]]
    save(public / "verification.json", {"candidate": "candidate27", "java": counts,
         "newTargetedTests": sum(row["tests"] for row in targets), "realModelCalls": 0,
         "sourceUnderTest": inputs, "scope": "Affected integration, provider, history and security paths; not full repository/live acceptance",
         "restrictedRunnerInterrupted": "Mockito instrumentation did not complete; only the fresh local runner counts as evidence"})
    parent_sources = {row["path"]: row["sha256"] for row in parent["sourceFiles"]}
    changed = [row["path"] for row in inputs if row["path"] in parent_sources and row["sha256"] != parent_sources[row["path"]]]
    expected = {
        "services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/PlanDecisionIntegrator.java",
        "services/api/src/main/java/com/promptoptimizer/provider/infrastructure/openai/OpenAiCompatiblePromptEnhancementProvider.java"}
    assert set(changed) == expected, changed
    for row in inputs:
        destination = work / "frozen/source" / row["path"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / row["path"], destination)
        assert sha(destination) == row["sha256"]
    runtime = []
    for path in sorted((work / "build/classes").rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(work / "build/classes").as_posix()
        destination = work / "frozen/classes" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        assert sha(path) == sha(destination)
        runtime.append({"path": relative, "sha256": sha(path)})
    previous_runtime = {row["path"]: row["sha256"] for row in parent["runtimeFiles"]}
    changes = [row["path"] for row in runtime if previous_runtime.get(row["path"]) != row["sha256"]]
    stems = [name.removeprefix("services/api/src/main/java/").removesuffix(".java") for name in expected]
    assert all(any(name == stem + ".class" or name.startswith(stem + "$") for stem in stems) for name in changes), changes
    assert not set(previous_runtime) - {row["path"] for row in runtime}
    protocol_path = root / "tmp/me00a-20261012/protocol.json"
    protocol = read(protocol_path)
    traces = [read(path) for path in (root / "tmp/me00a-20261012/traces").glob("[0-9]*.json")]
    assert len(traces) == 332 and sum(row["phase"] == "candidateValidation" for row in traces) == 262
    assert protocol["maximumActualModelCalls"] == 353 and protocol["phaseLimits"] == {"baseline": 80, "candidateValidation": 273}
    assert protocol["activeBatch"]["status"] == "CLOSED"
    save(public / "budget-verification.json", {"actualTotal": 332, "baseline": 70, "candidateValidation": 262,
         "formalLimits": {"total": 353, "baseline": 80, "candidateValidation": 273},
         "candidatePhaseRemaining": 11, "lastBatchUnusedClosedNotTransferable": 10,
         "protocolSha256": sha(protocol_path), "newBatchOpened": False, "realModelCalls": 0})
    shutil.copyfile(work / "target/candidate27-trace329-assembled.json", public / "trace329-reassembled.json")
    save(public / "candidate27-source.json", {"version": "candidate27", "parentCandidate": "candidate26-r2",
         "parentManifestSha256": sha(parent_file), "baseHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
         "frozenAt": datetime.now(timezone.utc).isoformat(),
         "sourceSnapshot": (work / "frozen/source").relative_to(root).as_posix(),
         "runtimeSnapshot": (work / "frozen/classes").relative_to(root).as_posix(),
         "sourceFiles": inputs, "runtimeFiles": runtime, "sourceTreeSha256": tree(inputs), "runtimeTreeSha256": tree(runtime),
         "changedExistingSourceFiles": changed, "addedSourceFiles": [row["path"] for row in inputs if row["path"] not in parent_sources],
         "changedRuntimeFiles": changes, "protocolUnchangedSha256": sha(protocol_path), "verificationSha256": sha(public / "verification.json"),
         "realModelCalls": 0, "actualCumulativeCalls": 332, "retryMaximumUnchanged": 3,
         "strictAssessmentAndJsonValidationPreserved": True,
         "strategy": "Task-bound coverage equivalence; complete repair contract; consistent pendingDecisions guidance",
         "notClosed": ["Live partial delegation availability and stable complete delegation", "Actual rubric, word count, structure and information flow",
                       "Requests credentials, YLL, example combinations and full frontend interaction", "ME and G1-G8"]})
    print(json.dumps({"candidate": "candidate27", "java": {key: value for key, value in counts.items() if key != "reports"},
          "newTests": sum(row["tests"] for row in targets), "sourceFiles": len(inputs), "runtimeFiles": len(runtime),
          "runtimeChanges": changes, "realModelCalls": 0}, ensure_ascii=False))


if __name__ == "__main__":
    main()
