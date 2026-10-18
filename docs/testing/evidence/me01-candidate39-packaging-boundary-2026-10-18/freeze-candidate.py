"""Freeze candidate39 after affected tests; budget remains closed and no live request is authorized here."""
from pathlib import Path
from datetime import datetime, timezone
from importlib.util import spec_from_file_location, module_from_spec
import json
import shutil
import subprocess

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
PRIVATE = ROOT / "tmp/me04-candidate39-20261018"
PARENT = ROOT / "docs/testing/evidence/me04-candidate38-coverage-plan-2026-10-18"
spec = spec_from_file_location("freeze_shared", PARENT / "freeze-candidate.py")
shared = module_from_spec(spec)
spec.loader.exec_module(shared)


def main():
    """Require the exact regression set, preserve raw evidence, and bind source/runtime independently."""
    parent = shared.load(PARENT / "candidate38r1-source.json")
    java = shared.suites(PRIVATE / "backend-tests/build/surefire-reports")
    expected = set((PRIVATE / "affected-tests.txt").read_text(encoding="utf-8").split(","))
    assert {row["name"].split(".")[-1] for row in java["suites"]} == expected
    assert len(java["suites"]) == 69 and java["tests"] == 1228
    assert java["failures"] == java["errors"] == java["skipped"] == 0
    source_paths = [path for directory in ("services/api/src", "apps/web/src", "apps/web/e2e")
                    for path in (ROOT / directory).rglob("*") if path.is_file()]
    source_paths += [ROOT / "scripts/me00a-execution-claims.mjs", ROOT / "scripts/me00a-execution-claims.test.mjs"]
    source = [{"path": path.relative_to(ROOT).as_posix(), "sha256": shared.sha(path)} for path in sorted(source_paths)]
    old = {row["path"]: row["sha256"] for row in parent["sourceFiles"]}
    frontend = [row for row in source if row["path"].startswith("apps/web/")]
    assert all(old[row["path"]] == row["sha256"] for row in frontend)
    snapshot = PRIVATE / "frozen"
    assert not snapshot.exists()
    for row in source:
        target = snapshot / "source" / row["path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / row["path"], target)
    classes = PRIVATE / "backend-tests/build/classes"
    shutil.copytree(classes, snapshot / "classes")
    runtime = [{"path": path.relative_to(classes).as_posix(), "sha256": shared.sha(path)}
               for path in sorted(classes.rglob("*")) if path.is_file()]
    old_runtime = {row["path"]: row["sha256"] for row in parent["runtimeFiles"]}
    changed = [row["path"] for row in source if old.get(row["path"]) != row["sha256"]]
    manifest = {"version": "candidate39", "baseHead": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                "frozenAt": datetime.now(timezone.utc).isoformat(), "parentCandidate": "candidate38r1",
                "parentManifestSha256": shared.sha(PARENT / "candidate38r1-source.json"),
                "sourceSnapshot": (snapshot / "source").relative_to(ROOT).as_posix(),
                "runtimeSnapshot": (snapshot / "classes").relative_to(ROOT).as_posix(), "sourceFiles": source,
                "runtimeFiles": runtime, "changedSourcePaths": changed,
                "changedRuntimePaths": [row["path"] for row in runtime if old_runtime.get(row["path"]) != row["sha256"]],
                "policyFiles": [row for row in source if row["path"] in changed and row["path"].startswith("services/api/src/main/")],
                "modelGuidanceChanged": False, "newRealCalls": 0}
    red = shared.suites(PRIVATE / "backend-tests/packaging-red-reports")
    assert red["tests"] == 21 and red["failures"] == 4 and red["errors"] == red["skipped"] == 0
    traces = list((ROOT / "tmp/me00a-20261012/traces").glob("*.json"))
    assert len(traces) == 415
    phases = {}
    for trace in traces:
        phase = shared.load(trace)["phase"]
        phases[phase] = phases.get(phase, 0) + 1
    assert phases == {"baseline": 70, "candidateValidation": 345}
    protocol = shared.load(ROOT / "tmp/me00a-20261012/protocol.json")
    assert protocol["maximumActualModelCalls"] == 425 and protocol["phaseLimits"] == {"baseline": 80, "candidateValidation": 345}
    assert protocol["activeBatch"]["status"] == "CLOSED"
    shared.save(PUBLIC / "candidate39-source.json", manifest)
    shared.save(PUBLIC / "verification.json", {"candidate": "candidate39", "java": java, "parentRed": red,
        "frontendFilesUnchanged": len(frontend), "frontendTestsRerun": False,
        "independentScriptToolsChanged": False, "independentScriptTestsRerun": False,
        "budget": {"actual": 415, "phases": phases, "limits": {"total": 425, "baseline": 80, "candidate": 345},
                   "candidateRemaining": 0, "newRealCalls": 0, "batchStatus": "CLOSED"},
        "qualityGate": "OPEN_NO_NEW_REAL_REQUESTS_OR_WORKS",
        "scope": "Offline replay and focused boundary correction; source/history permissions and previous failures preserved"})
    print(json.dumps({"sourceFiles": len(source), "runtimeFiles": len(runtime), "tests": java["tests"],
        "changedRuntimePaths": manifest["changedRuntimePaths"], "manifestSha256": shared.sha(PUBLIC / "candidate39-source.json"), "newRealCalls": 0}))


if __name__ == "__main__":
    main()
