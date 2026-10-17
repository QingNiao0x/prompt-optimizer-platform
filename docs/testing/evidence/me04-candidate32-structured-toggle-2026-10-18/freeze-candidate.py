"""按本轮实际源码、运行文件和测试报告冻结开关候选；不改真实调用协议。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_new(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def digest(rows):
    return hashlib.sha256("".join(row["path"] + ":" + row["sha256"] + "\n" for row in rows).encode()).hexdigest()


def main():
    """只冻结已完成的本地验证；缺少真实作品时明确保持质量门禁开放。"""
    root = Path(__file__).resolve().parents[4]
    public = Path(__file__).resolve().parent
    work = root / "tmp/me00a-candidate32-20261018"
    parent_file = root / "docs/testing/evidence/me00a-candidate31-contract-lookup-2026-10-17/candidate31r2-source.json"
    parent = read(parent_file)
    backend = work / "backend-final"
    for row in read(backend / "source-under-test.json"):
        assert sha(root / row["path"]) == row["sha256"], "Backend source changed: " + row["path"]
    for row in read(work / "frontend-source-under-test.json"):
        assert sha(root / row["path"]) == row["sha256"], "Frontend source changed: " + row["path"]

    java = read(backend / "verification.json")
    assert java["tests"] > 0 and java["failures"] == java["errors"] == java["skipped"] == 0
    frontend = ET.parse(work / "frontend-vitest.xml").getroot()
    frontend_totals = {key: sum(int(suite.get(key, "0")) for suite in frontend.iter("testsuite"))
                       for key in ("tests", "failures", "errors", "skipped")}
    assert frontend_totals["tests"] > 0
    assert frontend_totals["failures"] == frontend_totals["errors"] == frontend_totals["skipped"] == 0
    browser = read(work / "browser-results-final.json")
    assert browser["stats"]["expected"] == 8
    assert browser["stats"]["unexpected"] == browser["stats"]["skipped"] == browser["stats"]["flaky"] == 0

    names = {row["path"] for row in parent["sourceFiles"]}
    for directory in ("apps/web/src", "apps/web/e2e", "services/api/src"):
        names.update(path.relative_to(root).as_posix() for path in (root / directory).rglob("*") if path.is_file())
    names.update(("apps/web/playwright.config.ts", "docs/testing/evidence/me04-candidate32-structured-toggle-2026-10-18/freeze-candidate.py"))
    sources = [{"path": name, "sha256": sha(root / name)} for name in sorted(names)]
    classes = backend / "build/classes"
    runtime = [{"path": path.relative_to(classes).as_posix(), "sha256": sha(path)}
               for path in sorted(classes.rglob("*")) if path.is_file()]
    assert runtime
    snapshot = work / "frozen"
    assert not snapshot.exists(), "Existing frozen snapshot must remain immutable"
    for rows, origin, destination in ((sources, root, snapshot / "source"), (runtime, classes, snapshot / "classes")):
        for row in rows:
            target = destination / row["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(origin / row["path"], target)
            assert sha(target) == row["sha256"]

    traces = root / "tmp/me00a-20261012/traces"
    actual = len(list(traces.glob("*.json")))
    protocol_file = root / "tmp/me00a-20261012/protocol.json"
    protocol = read(protocol_file)
    verification = {"candidate": "candidate32", "java": java,
        "frontend": frontend_totals, "browser": browser["stats"],
        "browserApi": "controlled fixtures, real browser and clipboard; no live model",
        "newModelCalls": 0, "actualModelCalls": actual,
        "activeBatchStatus": protocol.get("activeBatch", {}).get("status"),
        "notClosed": ["Real candidate32 model generation and downstream works", "Existing quality, security and professional gates", "G1-G8"]}
    write_new(public / "verification.json", verification)
    old = {row["path"]: row["sha256"] for row in parent["sourceFiles"]}
    manifest = {"version": "candidate32", "parentCandidate": "candidate31r2",
        "parentManifestSha256": sha(parent_file), "frozenAt": datetime.now(timezone.utc).isoformat(),
        "baseHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "sourceSnapshot": (snapshot / "source").relative_to(root).as_posix(),
        "runtimeSnapshot": (snapshot / "classes").relative_to(root).as_posix(),
        "sourceFiles": sources, "runtimeFiles": runtime,
        "sourceTreeSha256": digest(sources), "runtimeTreeSha256": digest(runtime),
        "changedExistingSourceFiles": [row["path"] for row in sources if row["path"] in old and row["sha256"] != old[row["path"]]],
        "addedSourceFiles": [row["path"] for row in sources if row["path"] not in old],
        "verificationSha256": sha(public / "verification.json"), "protocolUnchangedSha256": sha(protocol_file),
        "actualModelCalls": actual, "newModelCalls": 0, "serviceReloaded": False,
        "scope": "Explicit presentation preference, local switching, draft/Plan/history compatibility; same provider input",
        "qualityGatesClosed": False}
    write_new(public / "candidate32-source.json", manifest)
    print(json.dumps({"candidate": "candidate32", "sources": len(sources), "runtime": len(runtime),
                      "java": java["tests"], "frontend": frontend_totals["tests"], "browser": browser["stats"]["expected"], "newModelCalls": 0}))


if __name__ == "__main__":
    main()
