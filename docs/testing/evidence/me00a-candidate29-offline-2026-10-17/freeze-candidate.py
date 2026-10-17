"""冻结本次离线修复，逐项验证源码、完整JUnit及未发生真实调用。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def tree(rows):
    return hashlib.sha256("".join(row["path"] + ":" + row["sha256"] + "\n" for row in rows).encode()).hexdigest()


def main():
    root = Path(__file__).resolve().parents[4]
    out = Path(__file__).resolve().parent
    work = root / "tmp/me00a-candidate29-20261017"
    parent_path = root / "docs/testing/evidence/me00a-candidate28-offline-2026-10-17/candidate28-source.json"
    parent = json.loads(parent_path.read_text(encoding="utf-8-sig"))
    sources = json.loads((work / "source-under-test.json").read_text(encoding="utf-8-sig"))
    for row in sources:
        assert sha(root / row["path"]) == row["sha256"], row["path"]
    selection = json.loads((work / "affected-selection.json").read_text(encoding="utf-8-sig"))
    started = json.loads((work / "affected-started.json").read_text(encoding="utf-8-sig"))["startedAt"]
    started_time = datetime.fromisoformat(started.replace("Z", "+00:00")).timestamp()
    log_bytes = (work / "affected-build.log").read_bytes()
    # Windows PowerShell的重定向日志为UTF-16；保持原字节，仅按BOM读取完成标记。
    log_text = log_bytes.decode("utf-16" if log_bytes.startswith(b"\xff\xfe") else "utf-8-sig")
    assert "[INFO] BUILD SUCCESS" in log_text
    reports = []
    for name in selection:
        path = next((work / "build/surefire-reports").glob("TEST-*." + name + ".xml"))
        assert path.stat().st_mtime >= started_time - 2, path
        suite = ET.parse(path).getroot()
        row = {k: int(suite.attrib[k]) for k in ("tests", "failures", "errors", "skipped")}
        assert row["failures"] == row["errors"] == row["skipped"] == 0, name
        reports.append({"class": suite.attrib["name"], **row, "originalReportSha256": sha(path)})
        destination = work / "immutable-affected-reports" / path.name
        destination.parent.mkdir(exist_ok=True)
        shutil.copy2(path, destination)
    protocol = root / "tmp/me00a-20261012/protocol.json"
    assert sha(protocol) == parent["protocolUnchangedSha256"]
    assert len(list((protocol.parent / "traces").glob("*.json"))) == 345
    verification = {"candidate": "candidate29", "java": {"classes": len(reports),
        **{key: sum(row[key] for row in reports) for key in ("tests", "failures", "errors", "skipped")},
        "reports": reports}, "newTests": 11, "red": {"tests": 11, "failures": 0, "errors": 5, "skipped": 0},
        "firstFixCompileError": "Missing qualified EffectiveTaskView type; corrected before successful validation",
        "interruptedRun": "Not passed; Mockito external attach blocked; startup test javaagent used for final run",
        "realModelCalls": 0, "automaticEvaluationCumulative": 345,
        "userIncidentCallsOutsideAutomaticEvaluation": 3,
        "originalUserRequestReplay": "Not available: original responses and payload not persisted",
        "serviceReloaded": False, "overallQualityGateClosed": False}
    write(out / "verification.json", verification)
    runtime_root = work / "build/classes"
    runtime = [{"path": p.relative_to(runtime_root).as_posix(), "sha256": sha(p)}
               for p in sorted(runtime_root.rglob("*")) if p.is_file()]
    previous_sources = {row["path"]: row["sha256"] for row in parent["sourceFiles"]}
    previous_runtime = {row["path"]: row["sha256"] for row in parent["runtimeFiles"]}
    changed_sources = [row["path"] for row in sources if row["path"] in previous_sources
                       and row["sha256"] != previous_sources[row["path"]]]
    assert sorted(changed_sources) == ["services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/OptimizationResultAssembler.java",
                                       "services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/PlanQuestionFilter.java"]
    source_snapshot = work / "frozen/source"
    runtime_snapshot = work / "frozen/classes"
    assert not source_snapshot.exists() and not runtime_snapshot.exists()
    for rows, origin, destination in ((sources, root, source_snapshot), (runtime, runtime_root, runtime_snapshot)):
        for row in rows:
            target = destination / row["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(origin / row["path"], target)
            assert sha(target) == row["sha256"]
    manifest = {"version": "candidate29", "parentCandidate": "candidate28", "parentManifestSha256": sha(parent_path),
        "baseHead": "eef1c4b5", "frozenAt": datetime.now(timezone.utc).isoformat(),
        "sourceSnapshot": source_snapshot.relative_to(root).as_posix(),
        "runtimeSnapshot": runtime_snapshot.relative_to(root).as_posix(),
        "sourceFiles": sources, "runtimeFiles": runtime,
        "sourceTreeSha256": tree(sources), "runtimeTreeSha256": tree(runtime),
        "changedExistingSourceFiles": changed_sources,
        "addedSourceFiles": [row["path"] for row in sources if row["path"] not in previous_sources],
        "changedRuntimeFiles": [row["path"] for row in runtime if row["sha256"] != previous_runtime.get(row["path"])],
        "protocolUnchangedSha256": sha(protocol), "verificationSha256": sha(out / "verification.json"),
        "realModelCalls": 0, "automaticEvaluationCumulative": 345, "serviceReloaded": False,
        "scope": "Existing known-fact resolution precedes model-only assessment consistency; no keyword expansion",
        "notClosed": ["Original request exact attribution", "Real candidate29 verification", "Previous quality gates"]}
    write(out / "candidate29-source.json", manifest)
    print(json.dumps({"classes": len(reports), "tests": verification["java"]["tests"], "newTests": 11,
                      "sourceFiles": len(sources), "runtimeFiles": len(runtime),
                      "changedRuntimeFiles": manifest["changedRuntimeFiles"], "realCalls": 0}))


if __name__ == "__main__":
    main()
