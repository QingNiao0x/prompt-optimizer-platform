"""只读核对已冻结源码、运行、原件、JUnit与账目，生成证据清单；不重跑测试或模型。"""
import hashlib
import json
from pathlib import Path


def digest(path):
    """证据与候选统一按原始字节计算SHA256。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path):
    """允许Windows工具的UTF-8 BOM，不修改文件字节。"""
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main():
    """核对当前事实，不把独立状态失败或旧作品转换为通过。"""
    public = Path(__file__).resolve().parent
    root = public.parents[3]
    frozen = read_json(public / "candidate25-source.json")
    for row in frozen["sourceFiles"]:
        assert digest(root / row["path"]) == row["sha256"], row["path"]
        assert digest(root / frozen["sourceSnapshot"] / row["path"]) == row["sha256"], row["path"]
    for row in frozen["runtimeFiles"]:
        assert digest(root / frozen["runtimeSnapshot"] / row["path"]) == row["sha256"], row["path"]
    for row in frozen["evaluationTools"]:
        assert digest(root / row["path"]) == row["sha256"], row["path"]
    verified = read_json(public / "verification.json")
    assert verified["java"]["tests"] == 709 and verified["newTargetedTests"] == 57
    for row in verified["java"]["reports"]:
        report = root / "tmp/me00a-candidate25-20261017/build/surefire-reports" / ("TEST-" + row["class"] + ".xml")
        assert digest(report) == row["originalReportSha256"], row["class"]
    fixture = read_json(root / "services/api/src/test/resources/enhancement/candidate25-trace325.json")
    trace_path = root / fixture["originalTrace"]
    assert digest(trace_path) == fixture["originalTraceSha256"]
    raw = read_json(trace_path)["response"]["choices"][0]["message"]["content"]
    assert hashlib.sha256(raw.encode()).hexdigest() == fixture["originalResponseContentSha256"]
    assert json.loads(raw) == fixture["response"]
    result = read_json(public / "trace325-reassembled.json")
    assert result["optimizedPrompt"] == (public / "reassembled-copy.md").read_text(encoding="utf-8")
    assert result["ambiguities"] == fixture["originalPlatformResult"]["ambiguities"]
    assert result["appliedConstraints"] == fixture["originalPlatformResult"]["appliedConstraints"]
    before = read_json(public / "scope-classification-before.json")
    after = read_json(public / "scope-classification-after.json")
    assert before["observations"] == after["observations"]
    assert read_json(public / "known-scope-gap.json")["independentStateGate"] == "FAIL_3_OF_3_NOT_FIXED"
    protocol = root / "tmp/me00a-20261012/protocol.json"
    assert digest(protocol) == frozen["protocolUnchangedSha256"]
    assert len(list((root / "tmp/me00a-20261012/traces").glob("[0-9]*.json"))) == 327
    assert (public / "run-tests.ps1").read_bytes().startswith(b"\xef\xbb\xbf")
    for path in public.rglob("*.json"):
        read_json(path)
    final = {"candidate": "candidate25", "sourceFilesVerified": len(frozen["sourceFiles"]),
             "runtimeFilesVerified": len(frozen["runtimeFiles"]), "frozenToolsVerified": len(frozen["evaluationTools"]),
             "freshJUnitReportsUnchanged": len(verified["java"]["reports"]), "javaTestsPassed": 709,
             "independentStateChecksFailedAndOpen": 3, "original325BytesUnchanged": True,
             "protocolUnchanged": True, "actualCalls": 327, "realModelCalls": 0,
             "notNewLiveOrFullRepositoryAcceptance": True}
    (public / "archive-verification.json").write_text(json.dumps(final, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    rows = [{"path": p.relative_to(root).as_posix(), "bytes": p.stat().st_size, "sha256": digest(p)}
            for p in sorted(public.rglob("*")) if p.is_file() and p.name != "evidence-manifest.json"]
    (public / "evidence-manifest.json").write_text(json.dumps({"candidate": "candidate25", "files": rows,
        "bytePreservation": "gitattributes -text -whitespace"}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(final, ensure_ascii=False))


if __name__ == "__main__":
    main()
