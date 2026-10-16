"""Freeze verified source/runtime; no model calls or credential reads."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[3]


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(name, value):
    with (OUT / name).open("x", encoding="utf-8") as target:
        json.dump(value, target, ensure_ascii=False, indent=2)
        target.write("\n")


if "--verify" in sys.argv:
    manifest = load(OUT / "candidate22-source.json")
    for row in manifest["sourceFiles"]:
        assert sha(ROOT / row["path"]) == sha(ROOT / manifest["sourceSnapshot"] / row["path"]) == row["sha256"], row["path"]
    for row in manifest["runtimeFiles"]:
        assert sha(ROOT / manifest["runtimeSnapshot"] / row["path"]) == row["sha256"], row["path"]
    print(json.dumps({"candidate": "candidate22", "verified": True, "modelCalls": 0}))
    sys.exit()

run = load(ROOT / "tmp/candidate22-affected-run.json")
started = datetime.fromisoformat(run["startedAt"]).timestamp()
reports = []
folder = OUT / "test-reports"
folder.mkdir(exist_ok=False)
for path in (ROOT / "services/api/target/surefire-reports").glob("TEST-*.xml"):
    tree = ET.parse(path).getroot()
    if tree.attrib["name"].split(".")[-1] not in run["classes"]:
        continue
    assert path.stat().st_mtime >= started - 3
    row = {key: int(tree.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
    assert row["failures"] == row["errors"] == row["skipped"] == 0
    row.update(name=tree.attrib["name"], originalSha256=sha(path))
    reports.append(row)
    safe = ET.Element("testsuite", tree.attrib)
    for case in tree.findall("testcase"):
        ET.SubElement(safe, "testcase", case.attrib)
    ET.ElementTree(safe).write(folder / path.name, encoding="utf-8", xml_declaration=True)
assert len(reports) == len(run["classes"])
totals = {key: sum(row[key] for row in reports) for key in ("tests", "failures", "errors", "skipped")}
save("verification.json", {"java": {"classes": len(reports), **totals, "reports": reports},
     "scriptTests": {"state": "UNCHANGED_CANDIDATE21_RESULTS_REUSED", "tests": 14, "failures": 0}, "realModelCalls": 0, "fullRepositoryRegression": "NOT_RUN"})
parent = load(ROOT / "docs/testing/evidence/me00a-candidate21-offline-2026-10-16/candidate21-source.json")
paths = {ROOT / row["path"] for row in parent["sourceFiles"]}
for base in ("services/api/src/main", "services/api/src/test"):
    paths.update(path for path in (ROOT / base).rglob("*") if path.is_file())
paths.update((ROOT / "scripts").glob("me00a-*.mjs"))
snapshot = ROOT / "tmp/me00a-candidate22-20261016"
snapshot.mkdir(exist_ok=False)
sources, runtime = [], []
for path in sorted(paths):
    assert path.resolve().is_relative_to(ROOT.resolve()) and path.is_file(), path
    relative = path.relative_to(ROOT)
    target = snapshot / "source" / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, target)
    sources.append({"path": relative.as_posix(), "sha256": sha(path)})
for path in sorted((ROOT / "services/api/target/classes").rglob("*")):
    if path.is_file():
        relative = path.relative_to(ROOT / "services/api/target/classes")
        target = snapshot / "classes" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        runtime.append({"path": relative.as_posix(), "sha256": sha(path)})
tree_hash = lambda rows: hashlib.sha256("\n".join(row["path"] + " " + row["sha256"] for row in rows).encode()).hexdigest()
save("candidate22-source.json", {"version": "candidate22", "frozenAt": datetime.now(timezone.utc).isoformat(),
     "baseHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
     "parentCandidate": "candidate21", "sourceSnapshot": (snapshot / "source").relative_to(ROOT).as_posix(),
     "runtimeSnapshot": (snapshot / "classes").relative_to(ROOT).as_posix(), "sourceFiles": sources,
     "runtimeFiles": runtime, "sourceTreeSha256": tree_hash(sources), "runtimeTreeSha256": tree_hash(runtime),
     "strategy": "Current scoring-form metadata projection; lossless common delegated-state rendering; bounded natural-question variants; candidate21 protections retained",
     "notImplemented": ["Exact provider tokenizers", "Generic professional truth checker", "Expert service", "Downstream automatic work revision"],
     "noNewGlobalPromptInstruction": True, "maximumProtocolAttemptsUnchanged": 3})
print(json.dumps({"candidate": "candidate22", "sourceFiles": len(sources), "runtimeFiles": len(runtime), **totals}))
