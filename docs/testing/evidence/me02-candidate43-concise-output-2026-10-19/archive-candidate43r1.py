"""离线归档四十三 r1 的限定复验，不发请求、不改协议、不复用旧评审结论。

在 r1 闭批后运行。只发布本批模型响应和白名单流程字段；失败或缺失的
复制正文保留为未完成，不能用原批次结果补齐。产物全部放入独立 r1 目录。
"""

from __future__ import annotations

from collections import Counter
import hashlib
import json
from pathlib import Path


PUBLIC = Path(__file__).resolve().parent
REPO = PUBLIC.parents[3]
DEST = PUBLIC / "r1"
PRIVATE = REPO / "tmp/me02-candidate43r1-20261019"
LEDGER = REPO / "tmp/me00a-20261012"
VERSION = "candidate43r1-concise-output"
CAPS = {"PLANNING": 1, "PLATFORM_GENERATION": 6, "EXECUTION": 1, "REVIEW": 0}
WRITTEN: list[Path] = []


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def select(data: dict, keys: tuple[str, ...]) -> dict:
    return {key: data[key] for key in keys if key in data}


def normalized(text: str) -> str:
    return text.replace("\r\n", "\n")


def save_bytes(relative: str, data: bytes) -> None:
    target = DEST / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    # 已归档原件不可静默改写；完全相同的再次运行允许恢复中断的归档。
    require(not target.exists() or target.read_bytes() == data, f"Archive changed: {relative}")
    target.write_bytes(data)
    WRITTEN.append(target)


def save_json(relative: str, data) -> None:
    save_bytes(relative, (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))


def copy(source: Path, relative: str) -> None:
    save_bytes(relative, source.read_bytes())


def closed_batch() -> dict:
    """核对已闭批的只读快照，不替操作者激活、关闭或重分配额度。"""
    protocol = read_json(LEDGER / "protocol.json")
    batch = protocol.get("activeBatch", {})
    require(batch.get("version") == VERSION and batch.get("status") == "CLOSED", "Close r1 before archiving")
    require(batch.get("sourceCandidate") == "candidate43r1", "Unexpected candidate")
    require(batch.get("callsBefore") == 481 and batch.get("candidateCallsBefore") == 411, "Unexpected starting ledger")
    require(batch.get("maximumNewCalls") == 8 and batch.get("purposeMaximums") == CAPS, "Unexpected allocation")
    require(batch.get("qualityRetriesAllowed") is False and batch.get("accountCreationAllowed") is False, "Unexpected scope")
    require(protocol.get("maximumActualModelCalls") == 500 and protocol.get("phaseLimits") == {
        "baseline": 80, "candidateValidation": 420}, "Formal limits changed")
    for filename, key in (("candidate43r1-source.json", "sourceManifestSha256"),
                          ("request-cases-r1.json", "casesSha256"), ("source-pack.md", "commonSourcesSha256")):
        require(digest((PUBLIC / filename).read_bytes()) == batch.get(key), f"Frozen artifact changed: {filename}")
    return batch


def archive_responses(batch: dict) -> list[dict]:
    rows = []
    counts = Counter()
    for source in sorted((LEDGER / "traces").glob("*.json"), key=lambda path: int(path.stem)):
        sequence = int(source.stem)
        if sequence < 482:
            continue
        trace = read_json(source)
        if trace.get("version") != VERSION:
            continue
        purpose = trace.get("purpose")
        require(sequence <= 489 and trace.get("phase") == "candidateValidation", "Trace outside frozen scope")
        require(purpose in CAPS and CAPS[purpose] > 0, "Unapproved trace purpose")
        require(trace.get("sourceCandidate") in (None, "candidate43r1"), "Unexpected trace candidate")
        require(any(key in trace for key in ("response", "responseText", "failure")), "Unsettled reserved call")
        if purpose == "EXECUTION":
            require(trace.get("operation") == "COMPACT", "Unexpected work")
        else:
            require(trace.get("caseId") in ("AI-USAGE", "HANDOVER"), "Unexpected case")
        counts[purpose] += 1
        request = trace.get("request", {})
        projected = select(trace, ("purpose", "caseId", "version", "phase", "operation", "sourceCandidate",
                                   "requestId", "startedAt", "httpStatus", "elapsedMs", "failure", "response", "responseText"))
        projected.update({
            "sequence": sequence, "sourceTraceSha256": digest(source.read_bytes()),
            "requestParameters": select(request, ("model", "temperature", "thinking", "stream", "max_tokens", "response_format")),
            "requestCanonicalSha256": digest(json.dumps(request, ensure_ascii=False, sort_keys=True,
                                                          separators=(",", ":")).encode("utf-8")),
            "requestHashEncoding": "UTF-8 JSON, ensure_ascii=false, sort_keys=true, separators=(',', ':')",
            "requestMessagesPublished": False, "upstream200DoesNotMeanPlatformAccepted": True,
        })
        response = trace.get("response", {})
        choices = response.get("choices", []) if isinstance(response, dict) else []
        content = choices[0].get("message", {}).get("content") if choices else None
        if isinstance(content, str):
            projected["modelContentSha256"] = digest(content.encode("utf-8"))
            save_bytes(f"model-responses/{sequence}-content.txt", content.encode("utf-8"))
        save_json(f"model-responses/{sequence}.json", projected)
        rows.append(select(projected, ("sequence", "purpose", "caseId", "operation", "httpStatus", "failure",
                                        "modelContentSha256", "sourceTraceSha256")))
    require(0 < len(rows) <= 8, "No completed r1 calls or allocation exceeded")
    require(all(counts[key] <= value for key, value in CAPS.items()), "Purpose allocation exceeded")
    require(batch.get("actualNewCalls") == len(rows) and batch.get("unusedCalls") == 8 - len(rows), "Closed ledger differs")
    declared = batch.get("actualPurposeCounts", {})
    require(all(declared.get(key, 0) == counts[key] for key in CAPS), "Closed purpose counts differ")
    save_json("model-responses/index.json", {"sequences": rows, "count": len(rows), "purposeCounts": dict(counts)})
    return rows


def archive_flows() -> list[dict]:
    """失败也归档；没有复制或历史时不借用旧版内容、不伪造一致性通过。"""
    result_keys = ("optimizedPrompt", "sections", "ambiguities", "appliedConstraints", "templateCode",
                   "provider", "latencyMs", "warnings", "evidenceCards", "promptFormat", "constraintGroups")
    history_keys = ("rawPrompt", "optimizedPrompt", "sections", "ambiguities", "appliedConstraints",
                   "templateCode", "providerName", "modelName", "mock", "latencyMs", "includePermissionBoundaries",
                   "includeExamples", "modelVersion", "promptFormat", "constraintGroups")
    rows = []
    for case_id in ("AI-USAGE", "HANDOVER"):
        source = PRIVATE / "flow" / case_id
        stage, target = source / "COMPACT", f"cases/{case_id}/COMPACT"
        check = {"caseId": case_id, "promptFormat": "COMPACT", "stageStatus": "NOT_COMPLETED",
                 "nativeCopyAndHistoryAvailable": False, "historyAndContextIdentifiersOmitted": True}
        plan_path = source / "plan.private.json"
        if plan_path.exists():
            plan = read_json(plan_path)
            body = plan.get("body", {})
            actual_path = source / "actual-confirmation.private.json"
            save_json(f"cases/{case_id}/plan.json", {
                "status": plan.get("status"), "requestId": body.get("requestId"), "error": body.get("error"),
                "data": select(body.get("data") or {}, ("summary", "questions", "templateCode", "provider", "latencyMs")),
                "actualAnswers": read_json(actual_path).get("answers", []) if actual_path.exists() else None,
                "actualConfirmationAvailable": actual_path.exists(), "sessionIdentifiersOmitted": True,
                "sourcePlanSha256": digest(plan_path.read_bytes()),
            })
        response_path = stage / "platform.private.json"
        if response_path.exists():
            response = read_json(response_path)
            body = response.get("body", {})
            result = body.get("data") or {}
            check.update({"platformStatus": response.get("status"), "requestId": body.get("requestId"),
                          "sourceResponseSha256": digest(response_path.read_bytes())})
            if response.get("status") == 200:
                save_json(f"{target}/assembled-result.json", select(result, result_keys))
                check["stageStatus"] = "RESULT_WITHOUT_VERIFIED_COPY_HISTORY"
            else:
                check["stageStatus"] = "FAILED"
                save_json(f"{target}/failure.json", {"status": response.get("status"),
                          "requestId": body.get("requestId"), "error": body.get("error"),
                          "nativeCopyAndHistoryAvailable": False})
            history_path, copy_path = stage / "history.private.json", stage / "native-copy.md"
            if response.get("status") == 200 and history_path.exists() and copy_path.exists():
                history = read_json(history_path)
                history_result = history.get("body", {}).get("data") or {}
                native = copy_path.read_text(encoding="utf-8")
                check.update({"historyStatus": history.get("status"), "nativeCopyAndHistoryAvailable": True,
                    "nativeCopyPlatformExactAfterNewlineNormalization": normalized(native) == normalized(result.get("optimizedPrompt", "")),
                    "nativeCopyHistoryExactAfterNewlineNormalization": normalized(native) == normalized(history_result.get("optimizedPrompt", "")),
                    "sectionsHistoryEqual": result.get("sections") == history_result.get("sections"),
                    "nativeCopyBytesSha256": digest(copy_path.read_bytes()),
                    "sourceHistorySha256": digest(history_path.read_bytes())})
                passed = history.get("status") == 200 and all(check[key] for key in (
                    "nativeCopyPlatformExactAfterNewlineNormalization", "nativeCopyHistoryExactAfterNewlineNormalization", "sectionsHistoryEqual"))
                check["stageStatus"] = "COPY_HISTORY_MATCHED" if passed else "COPY_HISTORY_MISMATCH"
                copy(copy_path, f"{target}/native-copy.md")
                save_json(f"{target}/history-result.json", select(history_result, history_keys))
        failures = []
        for failure_path in sorted(source.glob("*-failure.json")):
            failure = select(read_json(failure_path), ("reason", "guardFailure", "counts"))
            failures.append({"file": failure_path.name, **failure, "sourceSha256": digest(failure_path.read_bytes())})
        if failures:
            save_json(f"cases/{case_id}/flow-failures.json", failures)
        original_check = stage / "check.json"
        if original_check.exists():
            save_json(f"{target}/flow-check.json", select(read_json(original_check), (
                "success", "caseId", "promptFormat", "sameAccount", "newAccountsCreated", "nativeCopyMatches",
                "historyMatches", "historyTotalDelta", "copySha256", "actualAnswers", "qualityRetriesAllowed")))
        save_json(f"{target}/archive-check.json", check)
        rows.append(check)
    return rows


def archive_work() -> dict:
    """只存新自然正文作品；旧 RAW 仅以锁定原件作参照，不生成新评审结论。"""
    lock_path = PUBLIC / "r1-raw-reference-lock.json"
    lock = read_json(lock_path)
    for item in lock["files"]:
        require(digest((REPO / item["path"]).read_bytes()) == item["sha256"], "Archived RAW or sources changed")
    copy(lock_path, "works/raw-reference-lock.json")
    stage = PRIVATE / "works/COMPACT"
    info = {"arm": "COMPACT", "status": "NOT_COMPLETED", "newRawCalls": 0, "newReviews": 0,
            "searchAvailable": False, "overallQualityGate": "NOT_CLOSED_BY_THIS_REPLAY"}
    request_path = stage / "request.json"
    if request_path.exists():
        request = read_json(request_path)
        messages = request.get("messages", [])
        # 请求中的 JSON 字符串保留 CRLF。这里按原始 UTF-8 字节解码，不能让
        # read_text 的通用换行转换制造差异，也不能先归一化再声称严格一致。
        native = (PRIVATE / "flow/AI-USAGE/COMPACT/native-copy.md").read_bytes().decode("utf-8")
        sources = (PUBLIC / "source-pack.md").read_bytes().decode("utf-8")
        require(len(messages) == 2 and all(item.get("role") == "user" for item in messages), "Unexpected execution messages")
        require(messages[0].get("content") == native and messages[1].get("content") == sources, "Copy or sources patched")
        info.update({"requestFileSha256": digest(request_path.read_bytes()),
                     "requestParameters": select(request, ("model", "temperature", "max_tokens", "thinking", "stream")),
                     "copySha256": digest(native.encode("utf-8")), "sourcePacketSha256": digest(sources.encode("utf-8")),
                     "requestUsesExactNativeCopyAndSameSourcePacket": True})
    response_path = stage / "response.json"
    if response_path.exists():
        response = read_json(response_path)
        save_json("works/COMPACT-response.json", select(response, (
            "version", "sourceCandidate", "phase", "purpose", "operation", "startedAt", "httpStatus", "elapsedMs", "failure", "response")))
    for filename in ("response.raw.bin", "work.md", "failure.json"):
        if (stage / filename).exists():
            copy(stage / filename, f"works/COMPACT-{filename}")
    if (stage / "work.md").exists():
        info.update({"status": "WORK_AVAILABLE_NOT_AUTOMATICALLY_PASSED",
                     "workSha256": digest((stage / "work.md").read_bytes())})
    elif (stage / "failure.json").exists():
        info["status"] = "FAILED"
    if (PRIVATE / "hard-checks.json").exists():
        copy(PRIVATE / "hard-checks.json", "hard-checks.json")
        info["independentChecksAvailable"] = True
    else:
        info["independentChecksAvailable"] = False
    save_json("works/index.json", info)
    return info


def main() -> None:
    require((REPO / "AGENTS.md").is_file() and PRIVATE.is_dir(), "Repository or private artifacts missing")
    batch = closed_batch()
    responses = archive_responses(batch)
    flows = archive_flows()
    work = archive_work()
    prior_tool_failure = DEST / "archive-tool-failure.json"
    if prior_tool_failure.exists():
        save_json("archive-tool-failure.json", read_json(prior_tool_failure))
    save_json("batch-snapshot.json", select(batch, (
        "version", "sourceCandidate", "authorizationAmendment", "status", "closedAt", "closureReason",
        "callsBefore", "candidateCallsBefore", "maximumNewCalls", "maximumActualCallsAfterBatch", "purposeMaximums",
        "actualNewCalls", "actualCallsAfterBatch", "actualPurposeCounts", "unusedCalls", "qualityRetriesAllowed",
        "accountCreationAllowed", "sourceManifestSha256", "casesSha256", "commonSourcesSha256")))
    files = [{"path": str(path.relative_to(DEST)).replace("\\", "/"), "sha256": digest(path.read_bytes()),
              "bytes": path.stat().st_size} for path in WRITTEN]
    save_json("archive-manifest.json", {
        "version": VERSION, "scriptSha256": digest(Path(__file__).read_bytes()), "files": files,
        "actualCalls": len(responses), "flowStates": flows, "workState": work, "newIndependentAIReviews": 0,
        "overallQualityGate": "NOT_CLOSED_BY_THIS_REPLAY",
        "scope": "Two fixed COMPACT flows and one same-source downstream work; prior RAW is a locked reference. No new three-arm comparison or dual reviews.",
        "privacy": "Allowlisted flow fields only; no accounts, credentials, cookies, headers, plan/session/history ownership identifiers or full platform requests.",
        "limitations": ["Missing artifacts and failed flows remain incomplete.",
                        "Prior dual AI reviews are not new-candidate review evidence.",
                        "This archive performs no model calls, product test runs, protocol mutations or semantic quality adjudication."],
    })
    print(json.dumps({"archivedFiles": len(WRITTEN), "actualCalls": len(responses),
                      "flowStates": {row["caseId"]: row["stageStatus"] for row in flows}, "workState": work["status"]}))


if __name__ == "__main__":
    main()
