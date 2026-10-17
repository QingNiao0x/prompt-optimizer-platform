"""冻结三十一r2；保留初版快照及失败，不覆盖初版JUnit或既有真实原件。"""
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
    """核对实际受测源码和完整成功报告；无真实额度时仅冻结，不替换正式协议。"""
    root = Path(__file__).resolve().parents[4]
    out = Path(__file__).resolve().parent
    work = root / "tmp/me00a-candidate31-20261017"
    parent_path = out / "candidate31-source.json"
    parent = json.loads(parent_path.read_text(encoding="utf-8"))
    sources = json.loads((work / "source-under-test.json").read_text(encoding="utf-8-sig"))
    for row in sources:
        assert sha(root / row["path"]) == row["sha256"], row["path"]
    selection = json.loads((work / "affected-selection.json").read_text(encoding="utf-8-sig"))
    started = datetime.fromisoformat(json.loads((work / "affected-started.json").read_text(encoding="utf-8-sig"))["startedAt"]).timestamp()
    raw = (work / "affected-build.log").read_bytes()
    assert "[INFO] BUILD SUCCESS" in raw.decode("utf-16" if raw.startswith(b"\xff\xfe") else "utf-8-sig")
    reports = []
    for name in selection:
        path = next((work / "build/surefire-reports").glob("TEST-*." + name + ".xml"))
        assert path.stat().st_mtime >= started - 2
        suite = ET.parse(path).getroot()
        row = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
        assert row["failures"] == row["errors"] == row["skipped"] == 0
        reports.append({"class": suite.attrib["name"], **row, "originalReportSha256": sha(path)})
        target = work / "immutable-r2-reports" / path.name
        target.parent.mkdir(exist_ok=True)
        assert not target.exists()
        shutil.copy2(path, target)
    protocol = root / "tmp/me00a-20261012/protocol.json"
    assert sha(protocol) == parent["protocolUnchangedSha256"]
    assert len(list((protocol.parent / "traces").glob("*.json"))) == 351
    verification = {"candidate": "candidate31r2", "java": {"classes": len(reports),
        **{key: sum(row[key] for row in reports) for key in ("tests", "failures", "errors", "skipped")},
        "reports": reports}, "newBoundaryTests": 36, "newTimeoutTests": 2,
        "initialRed": {"tests": 30, "failures": 7, "errors": 0, "skipped": 0},
        "scopeCounterexamplesFailed": 2, "attributeAndQuotationCounterexamplesFailed": 3,
        "frontend": {"unitTests": 36, "browserTests": 4, "typecheckPassed": True,
                     "browserEvidence": "browser-verification.json", "controlledApiFixtures": True},
        "harnessScopeChecks": 8, "realModelCalls": 0, "automaticEvaluationCumulative": 351,
        "liveBudgetStatus": "Closed allowances require explicit reallocation; proposal only",
        "qualityGateClosed": False}
    write(out / "verification-r2.json", verification)
    runtime_root = work / "build/classes"
    runtime = [{"path": path.relative_to(runtime_root).as_posix(), "sha256": sha(path)}
               for path in sorted(runtime_root.rglob("*")) if path.is_file()]
    snapshot = work / "frozen-r2"
    assert not snapshot.exists()
    for rows, origin, destination in ((sources, root, snapshot / "source"), (runtime, runtime_root, snapshot / "classes")):
        for row in rows:
            target = destination / row["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(origin / row["path"], target)
            assert sha(target) == row["sha256"]
    old_sources = {row["path"]: row["sha256"] for row in parent["sourceFiles"]}
    old_runtime = {row["path"]: row["sha256"] for row in parent["runtimeFiles"]}
    manifest = {"version": "candidate31r2", "parentCandidate": "candidate31",
        "parentManifestSha256": sha(parent_path), "frozenAt": datetime.now(timezone.utc).isoformat(),
        "baseHead": parent["baseHead"], "sourceSnapshot": (snapshot / "source").relative_to(root).as_posix(),
        "runtimeSnapshot": (snapshot / "classes").relative_to(root).as_posix(),
        "sourceFiles": sources, "runtimeFiles": runtime, "sourceTreeSha256": tree(sources), "runtimeTreeSha256": tree(runtime),
        "changedExistingSourceFiles": [row["path"] for row in sources if row["path"] in old_sources and row["sha256"] != old_sources[row["path"]]],
        "addedSourceFiles": [row["path"] for row in sources if row["path"] not in old_sources],
        "changedRuntimeFiles": [row["path"] for row in runtime if row["sha256"] != old_runtime.get(row["path"])],
        "verificationSha256": sha(out / "verification-r2.json"), "protocolUnchangedSha256": sha(protocol),
        "initialCandidateSupersededBeforeLiveCalls": True,
        "scope": "Explicit contract lookup: object, input/output attribute, current instruction and independent conditions",
        "realModelCalls": 0, "automaticEvaluationCumulative": 351, "serviceReloaded": False,
        "notClosed": ["Real candidate31r2 flow and downstream works", "Existing credential/scoring/professional gates"]}
    write(out / "candidate31r2-source.json", manifest)
    print(json.dumps({"candidate": "candidate31r2", "javaTests": verification["java"]["tests"],
                      "sources": len(sources), "runtime": len(runtime), "realCalls": 0}))


if __name__ == "__main__":
    main()
