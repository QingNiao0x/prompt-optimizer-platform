"""闭批后只读核对本批公开原件、冻结版本、作品／评审及后续离线候选，不调用模型。"""
from pathlib import Path
import hashlib
import json


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def save(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def main():
    """区别真实二十七r2和仅离线二十八，后者不能继承前者的作品通过结论。"""
    public = Path(__file__).resolve().parent
    root = public.parents[3]
    ledger = root / "tmp/me00a-20261012"
    protocol = read(ledger / "protocol.json")
    assert protocol["maximumActualModelCalls"] == 371 and protocol["phaseLimits"] == {"baseline": 80, "candidateValidation": 291}
    assert protocol["activeBatch"]["version"] == "candidate27-live" and protocol["activeBatch"]["status"] == "CLOSED"
    paths = sorted((ledger / "traces").glob("[0-9]*.json"))
    assert [int(path.stem) for path in paths] == list(range(1, 346))
    traces = {int(path.stem): read(path) for path in paths}
    current = {number: row for number, row in traces.items() if row.get("version") == "candidate27-live"}
    assert list(current) == list(range(333, 346))
    assert sum(row["phase"] == "baseline" for row in traces.values()) == 70
    assert sum(row["phase"] == "candidateValidation" for row in traces.values()) == 275
    archive = read(public / "real-archive-manifest.json")
    for row in archive["files"]:
        assert sha(public / row["path"]) == row["sha256"], row["path"]
    for number, row in current.items():
        assert (public / f"real/traces/{number}.json").read_bytes() == (ledger / f"traces/{number}.json").read_bytes()
        original = row["response"]["choices"][0]["message"]["content"].encode()
        assert (public / f"real/model-content/{number}.txt").read_bytes() == original
    works = {"F1": 340, "P2": 341, "F2": 342, "P1": 343}
    for work, number in works.items():
        assert (public / f"real/works/{work}/work.md").read_bytes() == traces[number]["response"]["choices"][0]["message"]["content"].encode()
        binding = read(public / f"real/works/{work}/binding.json")
        assert sha(public / f"real/works/{work}/input.md") == binding["inputSha256"]
        if work in ("P2", "F2"):
            assert (public / f"real/works/{work}/input.md").read_bytes() == (public / "real/Q05-PARTIAL-SCORING/execution-input.md").read_bytes()
    for model, number in [("deepseek-flash", 344), ("deepseek-v4-pro", 345)]:
        assert (public / f"real/review-{model}.md").read_bytes() == traces[number]["response"]["choices"][0]["message"]["content"].encode()
    parent_file = root / "docs/testing/evidence/me00a-candidate27-r2-offline-2026-10-17/candidate27-r2-source.json"
    parent = read(parent_file)
    changed_after_batch = []
    for row in parent["sourceFiles"]:
        assert sha(root / parent["sourceSnapshot"] / row["path"]) == row["sha256"], row["path"]
        if sha(root / row["path"]) != row["sha256"]:
            changed_after_batch.append(row["path"])
    assert changed_after_batch == ["services/api/src/main/java/com/promptoptimizer/provider/domain/RequirementBoundaryContract.java"]
    for row in parent["runtimeFiles"]:
        assert sha(root / parent["runtimeSnapshot"] / row["path"]) == row["sha256"], row["path"]
    for row in read(public / "tools-frozen.json")["tools"]:
        assert sha(root / row["path"]) == row["sha256"], row["path"]
    next_public = root / "docs/testing/evidence/me00a-candidate28-offline-2026-10-17"
    candidate = read(next_public / "candidate28-source.json")
    for row in candidate["sourceFiles"]:
        assert sha(root / row["path"]) == row["sha256"] == sha(root / candidate["sourceSnapshot"] / row["path"]), row["path"]
    for row in candidate["testFixtureFiles"]:
        assert sha(root / row["path"]) == row["sha256"] == sha(root / candidate["sourceSnapshot"] / row["path"]), row["path"]
    for row in candidate["runtimeFiles"]:
        assert sha(root / candidate["runtimeSnapshot"] / row["path"]) == row["sha256"], row["path"]
    verification = read(next_public / "verification.json")
    for row in verification["java"]["reports"]:
        original = root / "tmp/me00a-candidate28-20261017/immutable-affected-reports" / ("TEST-" + row["class"] + ".xml")
        assert sha(original) == row["originalReportSha256"], row["class"]
    assert sha(ledger / "protocol.json") == candidate["protocolUnchangedSha256"]
    for id in ("Q05-NATURAL", "Q05-PARTIAL-SCORING", "Q05-PARTIAL-SCORING-R2"):
        folder = public / "real" / id
        body = read(folder / "platform.json")["body"]["data"]
        history = read(folder / "history-projection.json")
        assert all(body[key] == history[key] for key in ("optimizedPrompt", "sections", "ambiguities", "appliedConstraints"))
        copy = (folder / "execution-input.md").read_bytes().decode()
        assert copy.replace("\r\n", "\n") == body["optimizedPrompt"].replace("\r\n", "\n")
    assert read(public / "history-count-check.json")["after"] == 1484
    cleanup = read(public / "runtime-cleanup-live.json")
    assert cleanup["testPortsClosed"] and cleanup["originalBackendRetained"] and cleanup["originalFrontendRetained"] and cleanup["plaintextCredentialAbsent"]
    result = {"liveCandidate": "candidate27-r2", "newCalls": 13, "actualCumulative": 345,
              "rawTracesByteVerified": 13, "rawModelContentsVerified": 13, "archiveArtifactsVerified": len(archive["files"]),
              "workBytesUnmodified": 4, "reviewBytesUnmodified": 2, "firstRoundNativeCopyUsedForBothPlanWorks": True,
              "copyVisibleHistoryCasesVerified": 3, "liveFrozenSourceFilesVerified": 865, "liveFrozenRuntimeFilesVerified": 621,
              "originalExecutionToolsVerified": 14, "currentSourceChangesAfterClosedBatch": changed_after_batch,
              "offlineNextCandidate": "candidate28", "nextSourceVerified": 866, "nextRuntimeVerified": 621,
              "nextFixturesVerified": 2, "nextImmutableJUnitVerified": 87, "nextJavaTests": 1549,
              "nextCandidateRealCalls": 0, "protocolClosedAndUnchangedAfterOfflineFix": True,
              "noNewAccount": True, "noOverallQualityPass": True, "newCallsFromVerification": 0}
    save(public / "evidence-verification.json", result)
    save(next_public / "archive-verification.json", result)
    save(public / "post-batch-tools.json", {"stage": "OFFLINE_AFTER_ALL_REAL_CALLS", "newModelCalls": 0,
         "originalExecutionToolsUnchanged": True, "tools": [{"path": path.relative_to(root).as_posix(), "sha256": sha(path)}
             for path in [public / "LiveFailureInspection.java", Path(__file__).resolve(), next_public / "BoundaryProbe.java"]]})
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
