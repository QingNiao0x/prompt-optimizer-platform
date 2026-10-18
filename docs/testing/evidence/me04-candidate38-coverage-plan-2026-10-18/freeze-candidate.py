"""Freeze tested source/runtime and scope-bound replay evidence; no network or model requests."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
PRIVATE = ROOT / "tmp/me04-candidate38-20261018"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def save(path, value):
    with path.open("x", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write("\n")


def suites(directory):
    rows = []
    for path in sorted(directory.glob("TEST-*.xml")):
        data = ET.parse(path).getroot()
        rows.append({"name": data.attrib["name"], "reportSha256": sha(path),
                     **{key: int(data.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}})
    return {"suites": rows, **{key: sum(row[key] for row in rows) for key in ("tests", "failures", "errors", "skipped")}}


def main():
    """Require the exact completed affected suites; old runtime and initial failures remain immutable."""
    java = suites(PRIVATE / "backend-tests/build/surefire-reports")
    expected = set((PRIVATE / "affected-tests.txt").read_text(encoding="utf-8").split(","))
    assert {row["name"].split(".")[-1] for row in java["suites"]} == expected
    assert java["tests"] == 1178 and java["failures"] == java["errors"] == java["skipped"] == 0
    parent_path = ROOT / "docs/testing/evidence/me04-candidate37-rules-examples-2026-10-18/candidate37-source.json"
    parent = load(parent_path)
    parent_red = suites(PRIVATE / "parent-tests/build/surefire-reports")
    assert parent_red["tests"] == 33 and parent_red["failures"] == 8 and parent_red["errors"] == parent_red["skipped"] == 0
    assert all(sha(PRIVATE / "parent-tests/build/classes" / row["path"]) == row["sha256"] for row in parent["runtimeFiles"])
    source_paths = [path for directory in ("services/api/src", "apps/web/src", "apps/web/e2e")
                    for path in (ROOT / directory).rglob("*") if path.is_file()]
    source_paths += [ROOT / "scripts/me00a-execution-claims.mjs", ROOT / "scripts/me00a-execution-claims.test.mjs"]
    source = [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path)} for path in sorted(source_paths)]
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
    runtime = [{"path": path.relative_to(classes).as_posix(), "sha256": sha(path)}
               for path in sorted(classes.rglob("*")) if path.is_file()]
    changed = [row["path"] for row in source if old.get(row["path"]) != row["sha256"]]
    manifest = {"version": "candidate38", "baseHead": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                "frozenAt": datetime.now(timezone.utc).isoformat(), "parentCandidate": "candidate37",
                "parentManifestSha256": sha(parent_path), "sourceSnapshot": (snapshot / "source").relative_to(ROOT).as_posix(),
                "runtimeSnapshot": (snapshot / "classes").relative_to(ROOT).as_posix(), "sourceFiles": source, "runtimeFiles": runtime,
                "changedSourcePathsSinceCandidate37": changed,
                "policyFiles": [row for row in source if row["path"] in changed and row["path"].startswith("services/api/src/main/")]}
    save(PUBLIC / "candidate38-source.json", manifest)
    save(PUBLIC / "verification.json", {"candidate": "candidate38", "java": java, "parentRed": parent_red,
        "parentRuntimeVerified": True, "independentScriptTests": {"passed": 30, "failed": 0, "skipped": 0},
        "frontend": {"unchangedFiles": len(frontend), "newFrontendUnitRuns": 0}, "externalModelCalls": 0,
        "limits": ["Saved-response replay and unit HTTP stubs are not live calls or clipboard checks",
                   "Initial replay bypassed provider presentation normalization; corrected to the production adapter without editing originals",
                   "Initial summary fixture used unsupported plain Java-version evidence; replaced by an existing supported scoped fact label",
                   "Affected regression retained project-material basis in the new summary; original assertion unchanged"],
        "qualityGate": "OPEN_PENDING_LIVE_DIAGNOSTIC"})
    print(json.dumps({"javaTests": java["tests"], "sourceFiles": len(source), "runtimeFiles": len(runtime),
                      "manifestSha256": sha(PUBLIC / "candidate38-source.json"), "modelCalls": 0}))


if __name__ == "__main__":
    main()
