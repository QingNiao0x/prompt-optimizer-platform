"""Freeze the intent repair and its current/parent evidence without changing formal call limits."""
from pathlib import Path
from datetime import datetime, timezone
from importlib.util import spec_from_file_location, module_from_spec
import json
import shutil
import subprocess

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
PRIVATE = ROOT / "tmp/me01-candidate40-20261018"
PARENT = ROOT / "docs/testing/evidence/me01-candidate39-packaging-boundary-2026-10-18"
spec = spec_from_file_location("freeze_shared", ROOT / "docs/testing/evidence/me04-candidate38-coverage-plan-2026-10-18/freeze-candidate.py")
shared = module_from_spec(spec)
spec.loader.exec_module(shared)


def main():
    """Require affected regressions, preserve legacy failures, and keep live budget approval separate."""
    parent = shared.load(PARENT / "candidate39-source.json")
    java = shared.suites(PRIVATE / "backend-tests/build/surefire-reports")
    expected = set((PRIVATE / "affected-tests.txt").read_text(encoding="utf-8").split(","))
    assert {row["name"].split(".")[-1] for row in java["suites"]} == expected
    assert len(java["suites"]) == 96 and java["tests"] == 1838
    assert java["failures"] == java["errors"] == java["skipped"] == 0
    red = shared.suites(PRIVATE / "backend-tests/intent-red-reports")
    assert red["tests"] == 28 and red["failures"] == 9 and red["errors"] == red["skipped"] == 0
    legacy = shared.load(PRIVATE / "legacy-parent.json")
    assert len(legacy["observations"]) == 2 and all(not row["passed"] for row in legacy["observations"])
    paths = [path for folder in ("services/api/src", "apps/web/src", "apps/web/e2e")
             for path in (ROOT / folder).rglob("*") if path.is_file()]
    paths += [ROOT / "scripts/me00a-execution-claims.mjs", ROOT / "scripts/me00a-execution-claims.test.mjs"]
    source = [{"path": path.relative_to(ROOT).as_posix(), "sha256": shared.sha(path)} for path in sorted(paths)]
    previous = {row["path"]: row["sha256"] for row in parent["sourceFiles"]}
    frontend = [row for row in source if row["path"].startswith("apps/web/")]
    assert all(previous[row["path"]] == row["sha256"] for row in frontend)
    for unchanged in ("services/api/src/main/java/com/promptoptimizer/provider/domain/PromptOptimizationGuidance.java",
                      "services/api/src/main/java/com/promptoptimizer/template/service/impl/PromptTemplateRegistryImpl.java"):
        assert shared.sha(ROOT / unchanged) == previous[unchanged]
    frozen = PRIVATE / "frozen"
    assert not frozen.exists()
    for row in source:
        target = frozen / "source" / row["path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / row["path"], target)
    classes = PRIVATE / "backend-tests/build/classes"
    shutil.copytree(classes, frozen / "classes")
    runtime = [{"path": path.relative_to(classes).as_posix(), "sha256": shared.sha(path)}
               for path in sorted(classes.rglob("*")) if path.is_file()]
    old_runtime = {row["path"]: row["sha256"] for row in parent["runtimeFiles"]}
    changed = [row["path"] for row in source if previous.get(row["path"]) != row["sha256"]]
    manifest = {"version": "candidate40", "baseHead": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "frozenAt": datetime.now(timezone.utc).isoformat(), "parentCandidate": "candidate39",
        "parentManifestSha256": shared.sha(PARENT / "candidate39-source.json"),
        "sourceSnapshot": (frozen / "source").relative_to(ROOT).as_posix(),
        "runtimeSnapshot": (frozen / "classes").relative_to(ROOT).as_posix(), "sourceFiles": source, "runtimeFiles": runtime,
        "changedSourcePaths": changed, "changedRuntimePaths": [row["path"] for row in runtime if old_runtime.get(row["path"]) != row["sha256"]],
        "policyFiles": [row for row in source if row["path"] in changed and row["path"].startswith("services/api/src/main/")],
        "modelGuidanceChanged": False, "newRealCallsAtFreeze": 0}
    shared.save(PUBLIC / "candidate40-source.json", manifest)
    shared.save(PUBLIC / "verification.json", {"candidate": "candidate40", "java": java, "parentRed": red,
        "initialExpandedRegression": shared.suites(PRIVATE / "backend-tests/affected-first-reports"),
        "secondExpandedRegression": shared.suites(PRIVATE / "backend-tests/affected-second-reports"),
        "legacyParentProbe": legacy, "frontendFilesUnchanged": len(frontend), "frontendTestsRerun": False,
        "independentScriptToolsChanged": False, "independentScriptTestsRerun": False,
        "newRealCallsAtFreeze": 0, "qualityGate": "OPEN_NO_NEW_REAL_REQUESTS_OR_WORKS"})
    protocol = shared.load(ROOT / "tmp/me00a-20261012/protocol.json")
    traces = list((ROOT / "tmp/me00a-20261012/traces").glob("*.json"))
    assert len(traces) == 415 and protocol["maximumActualModelCalls"] == 425
    assert protocol["phaseLimits"] == {"baseline": 80, "candidateValidation": 345}
    assert protocol["activeBatch"]["status"] == "CLOSED"
    shared.save(PUBLIC / "budget-at-freeze.json", {"actual": 415, "baselineActual": 70, "candidateActual": 345,
        "formalLimits": {"total": 425, "baseline": 80, "candidate": 345}, "candidateRemaining": 0,
        "newBatchOpened": False, "numericExpansionApproved": False, "newRealCalls": 0})
    shutil.copy2(PRIVATE / "LegacyContractProbe.java", PUBLIC / "LegacyContractProbe.java")
    print(json.dumps({"sourceFiles": len(source), "runtimeFiles": len(runtime), "javaTests": java["tests"],
        "changedRuntimePaths": manifest["changedRuntimePaths"], "manifestSha256": shared.sha(PUBLIC / "candidate40-source.json"), "newRealCalls": 0}))


if __name__ == "__main__":
    main()
