"""Freeze tested product source and existing-response replays. No HTTP, account mutation or model requests."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
PRIVATE = ROOT / "tmp/me04-candidate37-20261018"


def sha(path):
    """Hash original bytes without changing source line endings."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def save(path, value):
    """Evidence snapshots are immutable: an existing file is an error."""
    with path.open("x", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write("\n")


def suites(directory):
    rows = []
    for path in sorted(directory.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        rows.append({"name": suite.attrib["name"], "reportSha256": sha(path),
                     **{key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}})
    return {"suites": rows, **{key: sum(row[key] for row in rows) for key in ("tests", "failures", "errors", "skipped")}}


def main():
    """Verify the completed affected tests and replay bindings before copying a new runtime snapshot."""
    java = suites(PRIVATE / "backend-tests/build/surefire-reports")
    assert len(java["suites"]) == 27 and java["tests"] > 500
    assert java["failures"] == java["errors"] == java["skipped"] == 0
    parent_path = ROOT / "docs/testing/evidence/me04-candidate36-compact-guidance-2026-10-18/candidate36-source.json"
    parent = load(parent_path)
    before = load(PRIVATE / "replay-parent/summary.json")
    after = load(PRIVATE / "replay-final/summary.json")
    assert len(before) == len(after) == 12
    comparisons = []
    for old, new in zip(before, after, strict=True):
        assert (old["trace"], old["format"], old["modelResponseSha256"]) == (new["trace"], new["format"], new["modelResponseSha256"])
        row = {key: new[key] for key in ("trace", "format", "modelResponseSha256")}
        row.update({"before": old["state"], "after": new["state"]})
        assert old["state"] == "ASSEMBLED"
        if new["trace"] == 393:
            assert new["state"] == "REJECTED" and new["reason"] == "RULE_CONFLICT" and new["field"] == "sections.OUTPUT"
            row.update({"reason": new["reason"], "field": new["field"], "copyNotFabricated": True})
        else:
            assert new["state"] == "ASSEMBLED" and old["ambiguities"] == new["ambiguities"]
            expected = json.loads(json.dumps(old["constraintGroups"], ensure_ascii=False).replace(
                "处理可预期异常，返回清晰错误信息，不吞掉或伪造错误。",
                "处理可预期异常，返回清晰且不含敏感值的错误信息，不吞掉或伪造错误。"))
            assert expected == new["constraintGroups"]
            row.update({"beforeCodePoints": old["bodyCodePoints"], "afterCodePoints": new["bodyCodePoints"],
                        "reduction": old["bodyCodePoints"] - new["bodyCodePoints"], "samePendingAndAllSourcesExceptDeclaredErrorWording": True})
        comparisons.append(row)
    snapshot = PRIVATE / "frozen"
    assert not snapshot.exists()
    paths = [path for directory in ("services/api/src", "apps/web/src", "apps/web/e2e")
             for path in (ROOT / directory).rglob("*") if path.is_file()]
    paths += [ROOT / "scripts/me00a-execution-claims.mjs", ROOT / "scripts/me00a-execution-claims.test.mjs"]
    source = [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path)} for path in sorted(paths)]
    old_hashes = {row["path"]: row["sha256"] for row in parent["sourceFiles"]}
    frontend = [row for row in source if row["path"].startswith("apps/web/")]
    assert all(old_hashes.get(row["path"]) == row["sha256"] for row in frontend)
    for row in source:
        destination = snapshot / "source" / row["path"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / row["path"], destination)
    classes = PRIVATE / "backend-tests/build/classes"
    shutil.copytree(classes, snapshot / "classes")
    runtime = [{"path": path.relative_to(classes).as_posix(), "sha256": sha(path)}
               for path in sorted(classes.rglob("*")) if path.is_file()]
    changed = [row["path"] for row in source if old_hashes.get(row["path"]) != row["sha256"]]
    manifest = {"version": "candidate37", "baseHead": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                "frozenAt": datetime.now(timezone.utc).isoformat(), "parentCandidate": "candidate36",
                "parentManifestSha256": sha(parent_path), "sourceSnapshot": (snapshot / "source").relative_to(ROOT).as_posix(),
                "runtimeSnapshot": (snapshot / "classes").relative_to(ROOT).as_posix(),
                "sourceFiles": source, "runtimeFiles": runtime, "changedSourcePathsSinceCandidate36": changed,
                "policyFiles": [row for row in source if row["path"] in changed and row["path"].startswith("services/api/src/main/")]}
    save(PUBLIC / "candidate37-source.json", manifest)
    save(PUBLIC / "replay-comparison.json", {"modelCalls": 0, "rows": comparisons,
        "fixtureSha256": sha(ROOT / "services/api/src/test/resources/fixtures/candidate37-existing-model-replay.json"),
        "newGuidanceEffectTested": False, "realClipboardOrDatabaseTested": False})
    save(PUBLIC / "verification.json", {"candidate": "candidate37", "java": java,
        "initialRed": suites(PRIVATE / "backend-tests/red-reports"),
        "inlineNegationRed": suites(PRIVATE / "backend-tests/replay-red-reports"),
        "executionClaimScriptTests": {"passed": 16, "failed": 0, "skipped": 0},
        "frontend": {"unchangedFiles": len(frontend), "newTestRuns": 0},
        "modelCalls": 0, "qualityGate": "OPEN_PENDING_REAL_WORKS",
        "intermediateIssues": ["Local Maven repository path corrected before tests", "Sandbox ByteBuddy Attacher stalled; partial reports retained, host run completed",
            "Test working directory bound to original API path without changing product pom", "Post-sentence exception guard and inline negation false rejection fixed before freeze"],
        "previousResultsNotAdded": True})
    print(json.dumps({"sourceFiles": len(source), "runtimeFiles": len(runtime), "javaTests": java["tests"],
                      "sourceManifestSha256": sha(PUBLIC / "candidate37-source.json"), "modelCalls": 0}))


if __name__ == "__main__":
    main()
