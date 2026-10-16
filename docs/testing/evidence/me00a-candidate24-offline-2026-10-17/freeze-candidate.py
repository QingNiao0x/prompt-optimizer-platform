"""冻结候选二十四的实际源码和隔离运行文件；不启动服务或调用模型。"""

import hashlib
import json
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def sha256(path):
    """哈希原始字节，不归一化源码、响应或换行。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def copy_rows(root, paths, destination):
    """只复制根目录内的显式文件，并保存可独立重算的相对路径与哈希。"""
    rows = []
    for name in sorted(set(paths)):
        source = (root / name).resolve()
        source.relative_to(root.resolve())
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        assert sha256(source) == sha256(target), name
        rows.append({"path": name, "sha256": sha256(source)})
    return rows


def tree_sha(rows):
    """固定算法为排序路径、冒号和SHA256逐行串联的UTF-8字节。"""
    payload = "".join(row["path"] + ":" + row["sha256"] + "\n" for row in rows)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def main():
    """基于前候选文件集增加本次测试，冻结当前构建，登记全部变更而不复用旧运行版本。"""
    root = Path(__file__).resolve().parents[4]
    output = Path(__file__).resolve().parent
    previous = json.loads((root / "docs/testing/evidence/me00a-candidate23-offline-2026-10-16/candidate23-source-r2.json")
                          .read_text(encoding="utf-8-sig"))
    snapshot = root / "tmp/me00a-candidate24-20261017/frozen-r3"
    source_names = [row["path"] for row in previous["sourceFiles"]] + [
        "services/api/src/test/java/com/promptoptimizer/enhancement/service/impl/CandidateTwentyFourReplayTest.java",
        "services/api/src/test/resources/enhancement/candidate24-traces321-322.json",
    ]
    sources = copy_rows(root, source_names, snapshot / "source")
    runtime_root = root / "tmp/me00a-candidate24-20261017/build/classes"
    runtime = copy_rows(runtime_root, [p.relative_to(runtime_root).as_posix() for p in runtime_root.rglob("*")
                                      if p.is_file()], snapshot / "classes")
    old_sources = {row["path"]: row["sha256"] for row in previous["sourceFiles"]}
    changes = [row["path"] for row in sources if row["path"] in old_sources
               and row["sha256"] != old_sources[row["path"]]]
    assert changes == ["services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/TaskConstraintCoverage.java"], changes
    old_runtime = {row["path"]: row["sha256"] for row in previous["runtimeFiles"]}
    manifest = {
        "version": "candidate24-r3", "parentCandidate": "candidate24-r2", "comparisonCandidate": "candidate23-r2",
        "baseHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "frozenAt": datetime.now(timezone.utc).isoformat(),
        "sourceSnapshot": (snapshot / "source").relative_to(root).as_posix(),
        "runtimeSnapshot": (snapshot / "classes").relative_to(root).as_posix(),
        "sourceFiles": sources, "runtimeFiles": runtime,
        "sourceTreeSha256": tree_sha(sources), "runtimeTreeSha256": tree_sha(runtime),
        "treeHashAlgorithm": "SHA256(sorted relative-path + ':' + file-sha256 + LF, UTF-8)",
        "changedExistingSourceFiles": changes,
        "addedSourceFiles": [row["path"] for row in sources if row["path"] not in old_sources],
        "changedRuntimeFiles": [row["path"] for row in runtime if old_runtime.get(row["path"]) != row["sha256"]],
        "removedRuntimeFiles": sorted(set(old_runtime) - {row["path"] for row in runtime}),
        "strategy": "Only omit independently complete current-scope task rules; preserve all rule sources and unconfirmed choices",
        "noNewGlobalPromptInstruction": True, "maximumProtocolAttemptsUnchanged": 3,
        "realModelCalls": 0, "actualCumulativeCalls": 324,
        "formalLimits": {"total": 338, "baseline": 80, "candidateValidation": 258},
        "evaluationTools": [{"path": Path(__file__).relative_to(root).as_posix(), "sha256": sha256(Path(__file__))}],
        "notClosed": ["Full work quality", "Global question necessity", "Optional appendix simplification benefit",
                      "New natural Plan and new enhancement inference", "Native clipboard and real DB retest", "G1-G8"],
    }
    (output / "candidate24-source-r3.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"candidate": "candidate24-r3", "sourceFiles": len(sources), "runtimeFiles": len(runtime),
                      "changedRuntimeFiles": manifest["changedRuntimeFiles"], "removedRuntimeFiles": manifest["removedRuntimeFiles"],
                      "sourceTreeSha256": manifest["sourceTreeSha256"], "runtimeTreeSha256": manifest["runtimeTreeSha256"]}))


if __name__ == "__main__":
    main()
