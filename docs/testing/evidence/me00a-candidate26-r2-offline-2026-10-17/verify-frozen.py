"""只读核对候选二十六、JUnit、回放和账目，生成最终证据清单；不重新测试或调用模型。"""
import hashlib
import json
from pathlib import Path


def digest(path):
    """始终校验文件原始字节。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    """兼容UTF-8 BOM仅用于读取，不修改原件。"""
    return json.loads(path.read_text(encoding="utf-8-sig"))


def save(path, value):
    """保存可复核的派生记录，不记录本机凭据。"""
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    """不把三例状态修复、离线复制或旧作品转换为整体质量通过。"""
    public = Path(__file__).resolve().parent
    root = public.parents[3]
    frozen = read(public / "candidate26-r2-source.json")
    for row in frozen["sourceFiles"]:
        assert digest(root / row["path"]) == row["sha256"], row["path"]
        assert digest(root / frozen["sourceSnapshot"] / row["path"]) == row["sha256"], row["path"]
    for row in frozen["runtimeFiles"]:
        assert digest(root / frozen["runtimeSnapshot"] / row["path"]) == row["sha256"], row["path"]
    for row in frozen["evaluationTools"]:
        assert digest(root / row["path"]) == row["sha256"], row["path"]
    verified = read(public / "verification.json")
    assert verified["java"]["classes"] == 83 and verified["newTargetedTests"] >= 36
    assert not any(verified["java"][key] for key in ("failures", "errors", "skipped"))
    for row in verified["java"]["reports"]:
        path = root / "tmp/me00a-candidate26-r2-20261017/build/surefire-reports" / ("TEST-" + row["class"] + ".xml")
        assert digest(path) == row["originalReportSha256"], row["class"]
    prior = read(root / "docs/testing/evidence/me00a-candidate25-offline-2026-10-17/scope-classification-after.json")
    before = read(public / "scope-classification-before.json")["observations"]
    after = read(public / "scope-classification-after.json")["observations"]
    for old, left, right in zip(prior["observations"], before, after):
        assert old["answer"] == left["answer"] == right["answer"]
        assert old["scopes"] == left["scopes"] == ["CHOICE"] and old["pendingCount"] == left["pendingCount"] == 0
        assert not left["independentStateGatePass"] and right["independentStateGatePass"]
    chain = read(public / "state-chain.json")
    for row in chain["observations"]:
        assert row["task"]["confirmedDecisions"][0]["answer"] == row["answer"]
        assert row["task"]["confirmedDecisions"][0]["scope"] == "UNRESOLVED"
        assert row["modelInput"]["pendingDecisions"]
        assert row["result"]["ambiguities"]
    assert digest(root / "tmp/me00a-20261012/protocol.json") == frozen["protocolUnchangedSha256"]
    assert len(list((root / "tmp/me00a-20261012/traces").glob("[0-9]*.json"))) == 327
    fixture = read(root / "services/api/src/test/resources/enhancement/candidate25-trace325.json")
    assert digest(root / fixture["originalTrace"]) == fixture["originalTraceSha256"]
    assert read(public / "trace325-regression.json")["optimizedPromptEqual"]
    assert (public / "run-tests.ps1").read_bytes().startswith(b"\xef\xbb\xbf")
    for path in public.rglob("*.json"):
        read(path)
    result = {"candidate": "candidate26-r2", "sourceFilesVerified": len(frozen["sourceFiles"]),
              "runtimeFilesVerified": len(frozen["runtimeFiles"]), "evaluationToolsVerified": len(frozen["evaluationTools"]),
              "javaTestsPassed": verified["java"]["tests"], "newTargetedTests": verified["newTargetedTests"],
              "independentStateChecks": "PASS_3_OF_3", "parentFailedStateChecksRetained": True,
              "original325BytesUnchanged": True, "trace325CopyAndSectionsUnchangedFrom25": True,
              "protocolUnchanged": True, "actualCalls": 327, "realModelCalls": 0,
              "notLiveOrFullRepositoryAcceptance": True}
    save(public / "archive-verification.json", result)
    rows = [{"path": path.relative_to(root).as_posix(), "bytes": path.stat().st_size, "sha256": digest(path)}
            for path in sorted(public.rglob("*")) if path.is_file() and path.name != "evidence-manifest.json"]
    save(public / "evidence-manifest.json", {"candidate": "candidate26-r2", "files": rows,
                                          "bytePreservation": "gitattributes -text -whitespace"})
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
