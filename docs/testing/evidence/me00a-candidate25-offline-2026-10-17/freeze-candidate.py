"""冻结候选二十五实际源码与隔离运行文件；不重跑模型、不修改既有候选。"""
import hashlib
import json
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def sha256(path):
    """所有清单使用原始字节SHA256，不隐式转换换行、响应或源码。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def copy_rows(root, names, destination):
    """逐个验证源路径属于显式根目录，复制后独立核对字节哈希。"""
    rows = []
    for name in sorted(set(names)):
        source = (root / name).resolve()
        source.relative_to(root.resolve())
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        assert sha256(source) == sha256(target), name
        rows.append({"path": name, "sha256": sha256(source)})
    return rows


def tree_sha(rows):
    """沿用父候选的排序路径、冒号、文件SHA256与LF串联算法。"""
    return hashlib.sha256("".join(row["path"] + ":" + row["sha256"] + "\n" for row in rows).encode()).hexdigest()


def main():
    """验证当前测试与原件，再冻结一次；任何已有快照都不能被本脚本覆盖。"""
    root = Path(__file__).resolve().parents[4]
    public = Path(__file__).resolve().parent
    parent_path = root / "docs/testing/evidence/me00a-candidate24-offline-2026-10-17/candidate24-source-r3.json"
    parent = json.loads(parent_path.read_text(encoding="utf-8"))
    for row in parent["runtimeFiles"]:
        assert sha256(root / parent["runtimeSnapshot"] / row["path"]) == row["sha256"], row["path"]
    verified = json.loads((public / "verification.json").read_text(encoding="utf-8"))
    assert verified["java"]["tests"] == 709 and verified["java"]["classes"] == 35
    assert not any(verified["java"][key] for key in ["failures", "errors", "skipped"])
    snapshot = root / "tmp/me00a-candidate25-20261017/frozen"
    if snapshot.exists():
        raise RuntimeError("Candidate snapshot already exists; refusing replacement")
    names = [row["path"] for row in parent["sourceFiles"]] + [
        "services/api/src/test/java/com/promptoptimizer/enhancement/service/impl/CandidateTwentyFiveReplayTest.java",
        "services/api/src/test/resources/enhancement/candidate25-trace325.json",
    ]
    sources = copy_rows(root, names, snapshot / "source")
    old_sources = {row["path"]: row["sha256"] for row in parent["sourceFiles"]}
    changed_sources = [row["path"] for row in sources if row["path"] in old_sources and row["sha256"] != old_sources[row["path"]]]
    assert changed_sources == ["services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/PlanDecisionIntegrator.java"], changed_sources
    runtime_root = root / "tmp/me00a-candidate25-20261017/build/classes"
    runtime = copy_rows(runtime_root, [p.relative_to(runtime_root).as_posix() for p in runtime_root.rglob("*") if p.is_file()], snapshot / "classes")
    old_runtime = {row["path"]: row["sha256"] for row in parent["runtimeFiles"]}
    runtime_changes = [row["path"] for row in runtime if old_runtime.get(row["path"]) != row["sha256"]]
    removed = sorted(set(old_runtime) - {row["path"] for row in runtime})
    assert not removed
    assert set(runtime_changes) == {
        "com/promptoptimizer/enhancement/service/impl/PlanDecisionIntegrator.class",
        "com/promptoptimizer/enhancement/service/impl/PlanDecisionIntegrator$DelegatedDesignField.class",
    }, runtime_changes
    for name in changed_sources:
        archived = public / "source-changes" / name
        archived.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / name, archived)
        assert sha256(archived) == sha256(root / name)
    protocol_path = root / "tmp/me00a-20261012/protocol.json"
    protocol = json.loads(protocol_path.read_text(encoding="utf-8"))
    traces = [json.loads(p.read_text(encoding="utf-8")) for p in sorted((root / "tmp/me00a-20261012/traces").glob("[0-9]*.json"))]
    assert len(traces) == 327 and sum(t["phase"] == "candidateValidation" for t in traces) == 257
    assert protocol["maximumActualModelCalls"] == 338 and protocol["phaseLimits"] == {"baseline": 80, "candidateValidation": 258}
    assert protocol["activeBatch"]["status"] == "CLOSED"
    assert sha256(protocol_path) == sha256(root / "docs/testing/evidence/me00a-candidate24-r3-live-2026-10-17/protocol-final.json")
    manifest = {
        "version": "candidate25", "parentCandidate": "candidate24-r3",
        "baseHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "frozenAt": datetime.now(timezone.utc).isoformat(),
        "sourceSnapshot": (snapshot / "source").relative_to(root).as_posix(),
        "runtimeSnapshot": (snapshot / "classes").relative_to(root).as_posix(),
        "sourceFiles": sources, "runtimeFiles": runtime,
        "sourceTreeSha256": tree_sha(sources), "runtimeTreeSha256": tree_sha(runtime),
        "treeHashAlgorithm": "SHA256(sorted relative-path + ':' + file-sha256 + LF, UTF-8)",
        "changedExistingSourceFiles": changed_sources,
        "addedSourceFiles": [row["path"] for row in sources if row["path"] not in old_sources],
        "changedRuntimeFiles": runtime_changes, "removedRuntimeFiles": removed,
        "parentManifestSha256": sha256(parent_path),
        "verificationSha256": sha256(public / "verification.json"),
        "evaluationTools": [{"path": p.relative_to(root).as_posix(), "sha256": sha256(p)} for p in sorted(public.iterdir()) if p.suffix in [".ps1", ".py", ".java"]],
        "realModelCalls": 0, "actualCumulativeCalls": 327,
        "formalLimits": {"total": 338, "baseline": 80, "candidateValidation": 258},
        "protocolUnchangedSha256": sha256(protocol_path), "newBatchOpened": False,
        "noNewGlobalPromptInstruction": True, "maximumProtocolAttemptsUnchanged": 3,
        "notClosed": ["New natural Plan, enhancement inference and native clipboard/real DB retest",
                      "Actual downstream rubric and method quality", "Preexisting future/partial answer CHOICE classification",
                      "New three-arm comparison and two independent AI reviews", "G1-G8"],
    }
    (public / "candidate25-source.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: manifest[key] for key in ["version", "sourceTreeSha256", "runtimeTreeSha256", "changedExistingSourceFiles", "changedRuntimeFiles"]}
                     | {"sourceFiles": len(sources), "runtimeFiles": len(runtime), "realModelCalls": 0}, ensure_ascii=False))


if __name__ == "__main__":
    main()
