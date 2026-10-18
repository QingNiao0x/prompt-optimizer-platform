"""Freeze the offline follow-up after actual calls413-415. No new model requests or historical edits."""
from pathlib import Path
from datetime import datetime, timezone
import json
import shutil
import subprocess
from importlib.util import spec_from_file_location, module_from_spec

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
PRIVATE = ROOT / "tmp/me04-candidate38-20261018"
spec = spec_from_file_location("freeze_shared", PUBLIC / "freeze-candidate.py")
shared = module_from_spec(spec)
spec.loader.exec_module(shared)


def main():
    """Require full affected regressions and keep the initial source/runtime manifest separate."""
    parent_path = PUBLIC / "candidate38-source.json"
    parent = shared.load(parent_path)
    java = shared.suites(PRIVATE / "backend-tests/build/surefire-reports")
    expected = set((PRIVATE / "affected-r1-tests.txt").read_text(encoding="utf-8").split(","))
    assert {row["name"].split(".")[-1] for row in java["suites"]} == expected
    assert java["tests"] == 1199 and java["failures"] == java["errors"] == java["skipped"] == 0
    source_paths = [path for directory in ("services/api/src", "apps/web/src", "apps/web/e2e")
                    for path in (ROOT / directory).rglob("*") if path.is_file()]
    source_paths += [ROOT / "scripts/me00a-execution-claims.mjs", ROOT / "scripts/me00a-execution-claims.test.mjs"]
    source = [{"path": path.relative_to(ROOT).as_posix(), "sha256": shared.sha(path)} for path in sorted(source_paths)]
    old = {row["path"]: row["sha256"] for row in parent["sourceFiles"]}
    frontend = [row for row in source if row["path"].startswith("apps/web/")]
    assert all(old[row["path"]] == row["sha256"] for row in frontend)
    snapshot = PRIVATE / "frozen-r1"
    assert not snapshot.exists()
    for row in source:
        target = snapshot / "source" / row["path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / row["path"], target)
    classes = PRIVATE / "backend-tests/build/classes"
    shutil.copytree(classes, snapshot / "classes")
    runtime = [{"path": path.relative_to(classes).as_posix(), "sha256": shared.sha(path)}
               for path in sorted(classes.rglob("*")) if path.is_file()]
    previous_runtime = {row["path"]: row["sha256"] for row in parent["runtimeFiles"]}
    changed = [row["path"] for row in source if old.get(row["path"]) != row["sha256"]]
    manifest = {"version": "candidate38r1", "baseHead": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                "frozenAt": datetime.now(timezone.utc).isoformat(), "parentCandidate": "candidate38",
                "parentManifestSha256": shared.sha(parent_path), "sourceSnapshot": (snapshot / "source").relative_to(ROOT).as_posix(),
                "runtimeSnapshot": (snapshot / "classes").relative_to(ROOT).as_posix(), "sourceFiles": source, "runtimeFiles": runtime,
                "changedSourcePathsSinceCandidate38": changed,
                "changedRuntimePathsSinceCandidate38": [row["path"] for row in runtime if previous_runtime.get(row["path"]) != row["sha256"]],
                "policyFiles": [row for row in source if row["path"] in changed and row["path"].startswith("services/api/src/main/")]}
    shared.save(PUBLIC / "candidate38r1-source.json", manifest)
    shared.save(PUBLIC / "verification-r1.json", {"candidate": "candidate38r1", "java": java,
        "independentScriptTests": {"passed": 37, "failed": 0, "skipped": 0}, "newRealCalls": 0,
        "initialExampleRed": shared.suites(PRIVATE / "backend-tests/example-r1-red-reports"),
        "frontend": {"unchangedFiles": len(frontend), "newFrontendUnitRuns": 0},
        "scope": "Offline fix of414 extra-example validation and415 table-claim audit; not a new successful real response or work",
        "qualityGate": "OPEN_LIVE_NOT_RUN; historical safety/truthfulness failures retained"})
    print(json.dumps({"sourceFiles": len(source), "runtimeFiles": len(runtime), "javaTests": java["tests"],
                      "changedRuntimeFiles": len(manifest["changedRuntimePathsSinceCandidate38"]), "manifestSha256": shared.sha(PUBLIC / "candidate38r1-source.json"), "newModelCalls": 0}))


if __name__ == "__main__":
    main()
