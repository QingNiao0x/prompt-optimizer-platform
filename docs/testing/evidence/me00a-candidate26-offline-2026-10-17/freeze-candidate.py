"""核对本轮隔离验证与账目后冻结候选二十六，不替换任何已冻结候选。"""
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path


def digest(path):
    """清单使用原始字节，不隐式规范换行或改写响应。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    """只在解析时兼容Windows UTF-8 BOM。"""
    return json.loads(path.read_text(encoding="utf-8-sig"))


def save(path, value):
    """公开文件只含合成回放、计数、来源与哈希，不保存凭据或环境值。"""
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def junit(directory):
    """逐份记录报告与其字节哈希，首轮工具错误与产品断言失败分开保存。"""
    rows = []
    for path in sorted(directory.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        rows.append({"class": suite.get("name"), **{key: int(suite.get(key, 0)) for key in
                    ("tests", "failures", "errors", "skipped")}, "originalReportSha256": digest(path)})
    return {"classes": len(rows), **{key: sum(row[key] for row in rows) for key in
            ("tests", "failures", "errors", "skipped")}, "reports": rows}


def copy_files(root, names, target):
    """复制前限制源路径，复制后逐个核对原始字节。"""
    rows = []
    for name in sorted(set(names)):
        source = (root / name).resolve()
        source.relative_to(root.resolve())
        destination = target / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        assert digest(source) == digest(destination), name
        rows.append({"path": name, "sha256": digest(source)})
    return rows


def tree_sha(rows):
    """沿用既有候选的排序路径、冒号、文件哈希与LF串联口径。"""
    return hashlib.sha256("".join(row["path"] + ":" + row["sha256"] + "\n" for row in rows).encode()).hexdigest()


def main():
    """只允许本节点的四个生产类和新增测试发生变化；账目与父候选原件保持不变。"""
    public = Path(__file__).resolve().parent
    root = public.parents[3]
    work = root / "tmp/me00a-candidate26-20261017"
    parent_path = root / "docs/testing/evidence/me00a-candidate25-offline-2026-10-17/candidate25-source.json"
    parent = read(parent_path)
    assert not (work / "frozen").exists(), "Refusing to overwrite a frozen candidate"
    inputs = read(work / "source-under-test.json")
    for row in inputs:
        assert digest(root / row["path"]) == row["sha256"], row["path"]
    started = read(work / "affected-started.json")
    verification = junit(work / "build/surefire-reports")
    assert {row["class"].split(".")[-1] for row in verification["reports"]} == set(started["classes"])
    assert verification["classes"] == 83 and verification["tests"] >= 1440
    assert not any(verification[key] for key in ("failures", "errors", "skipped"))
    started_at = datetime.fromisoformat(started["startedAt"])
    for row in verification["reports"]:
        path = work / "build/surefire-reports" / ("TEST-" + row["class"] + ".xml")
        assert datetime.fromtimestamp(path.stat().st_mtime, timezone.utc) >= started_at, row["class"]
    targeted = next(row for row in verification["reports"] if row["class"].endswith(".CandidateTwentySixStateReplayTest"))
    save(public / "verification.json", {"candidate": "candidate26", "java": verification,
         "newTargetedTests": targeted["tests"], "realModelCalls": 0,
         "sourceUnderTest": inputs, "scope": "Affected callers, not the whole repository or live acceptance"})
    for name, directory in [("product-red", "product-red-reports"), ("first-fix", "first-fix-reports"),
                            ("partial-delegation-red", "partial-delegation-red-reports"), ("affected-red", "affected-red-reports")]:
        save(public / (name + ".json"), {"candidate": "candidate26", "stage": name,
             "java": junit(work / directory), "realModelCalls": 0})
    variant = work / "variants-red.xml"
    suite = ET.parse(variant).getroot()
    save(public / "variants-red.json", {"stage": "Negation and equivalent reservation review",
        **{key: int(suite.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")},
        "originalReportSha256": digest(variant), "realModelCalls": 0})

    sources = copy_files(root, [row["path"] for row in inputs], work / "frozen/source")
    old_sources = {row["path"]: row["sha256"] for row in parent["sourceFiles"]}
    changed = [row["path"] for row in sources if row["path"] in old_sources and row["sha256"] != old_sources[row["path"]]]
    expected = {
        "services/api/src/main/java/com/promptoptimizer/enhancement/domain/EffectiveTaskView.java",
        "services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/ConfirmedDecisionSet.java",
        "services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/EffectiveTaskViewFactory.java",
        "services/api/src/main/java/com/promptoptimizer/enhancement/service/impl/PlanAnswerSemantics.java",
    }
    assert set(changed) == expected, changed
    classes = work / "build/classes"
    runtime = copy_files(classes, [path.relative_to(classes).as_posix() for path in classes.rglob("*") if path.is_file()],
                         work / "frozen/classes")
    old_runtime = {row["path"]: row["sha256"] for row in parent["runtimeFiles"]}
    for row in parent["runtimeFiles"]:
        assert digest(root / parent["runtimeSnapshot"] / row["path"]) == row["sha256"], row["path"]
    runtime_changes = [row["path"] for row in runtime if old_runtime.get(row["path"]) != row["sha256"]]
    stems = [path.removeprefix("services/api/src/main/java/").removesuffix(".java") for path in expected]
    assert all(any(path == stem + ".class" or path.startswith(stem + "$") for stem in stems) for path in runtime_changes), runtime_changes
    assert not set(old_runtime) - {row["path"] for row in runtime}, "Unexpected runtime deletions"
    for name in changed:
        destination = public / "source-changes" / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / name, destination)

    protocol_path = root / "tmp/me00a-20261012/protocol.json"
    protocol = read(protocol_path)
    traces = [read(path) for path in sorted((root / "tmp/me00a-20261012/traces").glob("[0-9]*.json"))]
    assert len(traces) == 327 and sum(trace["phase"] == "candidateValidation" for trace in traces) == 257
    assert protocol["maximumActualModelCalls"] == 338 and protocol["phaseLimits"] == {"baseline": 80, "candidateValidation": 258}
    assert protocol["activeBatch"]["status"] == "CLOSED"
    assert digest(protocol_path) == parent["protocolUnchangedSha256"]
    save(public / "budget-verification.json", {"actualTotal": 327, "baseline": 70, "candidateValidation": 257,
         "formalLimits": {"total": 338, "baseline": 80, "candidateValidation": 258},
         "candidatePhaseRemaining": 1, "activeBatchStatus": "CLOSED", "newBatchOpened": False,
         "closedBatchBalanceTransferred": False, "protocolSha256": digest(protocol_path), "realModelCalls": 0})
    current325 = read(work / "target/candidate25-trace325-assembled.json")
    previous325 = read(root / "docs/testing/evidence/me00a-candidate25-offline-2026-10-17/trace325-reassembled.json")
    for key in ("optimizedPrompt", "sections", "ambiguities", "appliedConstraints"):
        assert current325[key] == previous325[key], key
    save(public / "trace325-regression.json", {"trace": 325, "parentCandidate": "candidate25",
         "optimizedPromptEqual": True, "sectionsEqual": True, "ambiguitiesEqual": True, "appliedConstraintsEqual": True,
         "copySha256": hashlib.sha256(current325["optimizedPrompt"].encode()).hexdigest(), "realModelCalls": 0})
    shutil.copyfile(work / "target/candidate26-state-chain.json", public / "state-chain.json")
    save(public / "candidate26-source.json", {"version": "candidate26", "parentCandidate": "candidate25",
         "baseHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
         "frozenAt": datetime.now(timezone.utc).isoformat(), "parentManifestSha256": digest(parent_path),
         "sourceSnapshot": (work / "frozen/source").relative_to(root).as_posix(),
         "runtimeSnapshot": (work / "frozen/classes").relative_to(root).as_posix(),
         "sourceFiles": sources, "runtimeFiles": runtime, "sourceTreeSha256": tree_sha(sources),
         "runtimeTreeSha256": tree_sha(runtime), "treeHashAlgorithm": "SHA256(sorted relative-path + ':' + file-sha256 + LF, UTF-8)",
         "changedExistingSourceFiles": changed, "addedSourceFiles": [row["path"] for row in sources if row["path"] not in old_sources],
         "changedRuntimeFiles": runtime_changes, "removedRuntimeFiles": [], "verificationSha256": digest(public / "verification.json"),
         "evaluationTools": [{"path": path.relative_to(root).as_posix(), "sha256": digest(path)} for path in sorted(public.iterdir()) if path.suffix in (".py", ".ps1", ".java")],
         "realModelCalls": 0, "actualCumulativeCalls": 327, "protocolUnchangedSha256": digest(protocol_path),
         "noNewGlobalPromptInstruction": True, "noProtocolOrRetryChanges": True,
         "notClosed": ["New natural Plan/enhancement/native clipboard/real DB acceptance", "Downstream rubric, structure, information flow and professional accuracy",
                       "Requests credential echo, YLL, examples and complete frontend flow", "ME and G1-G8"]})
    print(json.dumps({"candidate": "candidate26", "classes": verification["classes"], "tests": verification["tests"],
          "newTargetedTests": targeted["tests"], "sourceFiles": len(sources), "runtimeFiles": len(runtime),
          "runtimeChanges": runtime_changes, "realModelCalls": 0}, ensure_ascii=False))


if __name__ == "__main__":
    main()
