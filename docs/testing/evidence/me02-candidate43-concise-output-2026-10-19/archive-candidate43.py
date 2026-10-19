"""归档候选四十三原批次证据；仅白名单投影，不发布账号或服务端完整请求。

运行只读取已完成的本机原件，不发送模型请求、不修改总账。评审保持原件，
引文审计使用逐字连续匹配；不能把去掉 Markdown 后的近似匹配当成逐字引用。
"""

from __future__ import annotations

import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path


DEST = Path(__file__).resolve().parent
REPO = DEST.parents[3]
PRIVATE = REPO / "tmp/me02-candidate43-20261019"
WRITTEN: list[Path] = []


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def save_bytes(relative: str, data: bytes) -> None:
    target = DEST / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    WRITTEN.append(target)


def save_json(relative: str, data) -> None:
    save_bytes(relative, (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))


def copy(source: Path, relative: str) -> None:
    save_bytes(relative, source.read_bytes())


def select(data: dict, keys: tuple[str, ...]) -> dict:
    return {key: data[key] for key in keys if key in data}


def normalized(text: str) -> str:
    return text.replace("\r\n", "\n")


def archive_model_responses() -> list[dict]:
    rows = []
    for sequence in range(466, 482):
        source = REPO / f"tmp/me00a-20261012/traces/{sequence}.json"
        trace = read_json(source)
        response = trace["response"]
        content = response["choices"][0]["message"]["content"]
        request = trace.get("request", {})
        projected = select(trace, ("purpose", "caseId", "version", "phase", "operation",
                                  "sourceCandidate", "requestId", "startedAt", "httpStatus", "elapsedMs"))
        projected.update({
            "sequence": sequence,
            "sourceTraceSha256": digest(source.read_bytes()),
            "requestParameters": select(request, ("model", "temperature", "thinking", "stream", "max_tokens", "response_format")),
            "requestCanonicalSha256": digest(json.dumps(request, ensure_ascii=False, sort_keys=True,
                                                          separators=(",", ":")).encode("utf-8")),
            "requestHashEncoding": "UTF-8 JSON, ensure_ascii=false, sort_keys=true, separators=(',', ':')",
            "requestMessagesPublished": False,
            "response": response,
            "modelContentSha256": digest(content.encode("utf-8")),
            "upstream200DoesNotMeanPlatformAccepted": True,
        })
        save_json(f"model-responses/{sequence}.json", projected)
        save_bytes(f"model-responses/{sequence}-content.txt", content.encode("utf-8"))
        rows.append(select(projected, ("sequence", "purpose", "caseId", "phase", "httpStatus",
                                        "modelContentSha256", "sourceTraceSha256")))
    save_json("model-responses/index.json", {"sequences": rows, "count": len(rows)})
    return rows


def archive_flows() -> list[dict]:
    results = []
    result_keys = ("optimizedPrompt", "sections", "ambiguities", "appliedConstraints", "templateCode",
                   "provider", "latencyMs", "warnings", "evidenceCards", "promptFormat", "constraintGroups")
    history_keys = ("rawPrompt", "optimizedPrompt", "sections", "ambiguities", "appliedConstraints",
                   "templateCode", "providerName", "modelName", "mock", "latencyMs", "includePermissionBoundaries",
                   "includeExamples", "modelVersion", "promptFormat", "constraintGroups")
    for case_id, prompt_format in (("AI-USAGE", "COMPACT"), ("AI-USAGE", "STRUCTURED"), ("COURSE-CHOICE", "COMPACT")):
        source = PRIVATE / "flow" / case_id / prompt_format
        target = f"cases/{case_id}/{prompt_format}"
        response = read_json(source / "platform.private.json")
        history = read_json(source / "history.private.json")
        model_result = response["body"]["data"]
        history_result = history["body"]["data"]
        native = (source / "native-copy.md").read_text(encoding="utf-8")
        check = read_json(source / "check.json")
        archive_check = {
            "caseId": case_id, "promptFormat": prompt_format,
            "platformStatus": response["status"], "historyStatus": history["status"],
            "nativeCopyPlatformExactAfterNewlineNormalization": normalized(native) == normalized(model_result["optimizedPrompt"]),
            "nativeCopyHistoryExactAfterNewlineNormalization": normalized(native) == normalized(history_result["optimizedPrompt"]),
            "sectionsHistoryEqual": model_result["sections"] == history_result["sections"],
            "nativeCopyBytesSha256": digest((source / "native-copy.md").read_bytes()),
            "privateSourceHashes": {
                "assembledResponse": digest((source / "platform.private.json").read_bytes()),
                "historyResponse": digest((source / "history.private.json").read_bytes()),
            },
            "historyAndContextIdentifiersOmitted": True,
        }
        assert all(archive_check[key] for key in (
            "nativeCopyPlatformExactAfterNewlineNormalization", "nativeCopyHistoryExactAfterNewlineNormalization", "sectionsHistoryEqual"))
        copy(source / "native-copy.md", f"{target}/native-copy.md")
        copy(source / "check.json", f"{target}/check.json")
        save_json(f"{target}/assembled-result.json", select(model_result, result_keys))
        save_json(f"{target}/history-result.json", select(history_result, history_keys))
        save_json(f"{target}/archive-check.json", archive_check)
        results.append({**archive_check, "originalFlowCheck": check})
    for case_id in ("HANDOVER", "COURSE-CHOICE"):
        source = PRIVATE / "flow" / case_id
        plan = read_json(source / "plan.private.json")
        answers = read_json(source / "actual-confirmation.private.json")["answers"]
        save_json(f"cases/{case_id}/plan.json", {
            "status": plan["status"], "requestId": plan["body"].get("requestId"),
            "data": select(plan["body"]["data"], ("summary", "questions", "templateCode", "provider", "latencyMs")),
            "actualAnswers": answers, "sessionIdentifiersOmitted": True,
        })
    failure = read_json(PRIVATE / "flow/HANDOVER/COMPACT/platform.private.json")
    save_json("cases/HANDOVER/COMPACT/failure.json", {
        "traceSequence": 472, "status": failure["status"], "requestId": failure["body"]["requestId"],
        "error": failure["body"]["error"],
        "flowFailure": read_json(PRIVATE / "flow/HANDOVER/plan-COMPACT-failure.json"),
        "classification": "PLATFORM_VALIDATION_FAILURE_AFTER_UPSTREAM_200",
        "nativeCopyAndHistoryAvailable": False,
    })
    return results


def quote_nodes(value, path="$ "):
    if isinstance(value, dict):
        if "workId" in value and "quote" in value:
            yield path.strip(), value
        for key, item in value.items():
            yield from quote_nodes(item, f"{path.strip()}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            yield from quote_nodes(item, f"{path.strip()}[{index}]")


def archive_works_and_reviews() -> dict:
    lock = read_json(PRIVATE / "reviews/input-lock.json")
    work_by_id = {}
    for mapping in lock["privateMapping"]:
        work_name = mapping.get("arm", mapping.get("id", mapping.get("name")))
        # 盲评后公开组别映射；目录键来自既有锁定文件，不加入任何身份数据。
        if work_name is None:
            work_name = mapping["source"]
        work_path = PRIVATE / "works" / work_name / "work.md"
        raw = work_path.read_bytes()
        assert digest(raw) == mapping["sha256"]
        work_by_id[mapping["label"]] = raw.decode("utf-8")
        copy(work_path, f"works/{work_name}.md")
        copy(PRIVATE / "works" / work_name / "response.raw.bin", f"works/{work_name}-response.raw.bin")
    copy(PRIVATE / "works/input-lock.json", "works/input-lock.json")
    save_json("reviews/unblinded-input-lock.json", {
        "reviewers": lock["reviewers"], "workMapping": lock["privateMapping"],
        "hardChecksSha256": lock["hardChecksSha256"], "worksLockSha256": lock["worksLockSha256"],
        "mappingPublishedAfterReviewsCompleted": True,
    })
    reports = []
    for reviewer in ("R1", "R2"):
        source = PRIVATE / "reviews" / reviewer / "review.md"
        original = read_json(source)
        copy(source, f"reviews/{reviewer}-original.json")
        copy(PRIVATE / "reviews" / reviewer / "response.raw.bin", f"reviews/{reviewer}-response.raw.bin")
        rows = []
        for path, node in quote_nodes(original):
            work = work_by_id[node["workId"]]
            quote = node["quote"]
            offset = work.find(quote)
            rows.append({"path": path, "workId": node["workId"], "quote": quote,
                         "exactContinuousMatch": offset >= 0, "offsetCodePointsZeroBased": offset if offset >= 0 else None,
                         "classification": "EXACT" if offset >= 0 else "NON_VERBATIM",
                         "workUtf8Sha256": digest(work.encode("utf-8"))})
        reports.append({"reviewer": reviewer, "totalQuotes": len(rows),
                        "exactQuotes": sum(row["exactContinuousMatch"] for row in rows), "quotes": rows,
                        "originalComparisons": original["comparisons"], "originalReviewSha256": digest(source.read_bytes())})
    assert [(item["totalQuotes"], item["exactQuotes"]) for item in reports] == [(13, 13), (12, 6)]
    audit = {
        "method": "Exact continuous substring against original work.md; no Markdown/punctuation/whitespace normalization.",
        "reviewersAreIndependentAIAssistedNotHumanExperts": True,
        "reviewsUnchanged": True, "reports": reports,
        "manualFindings": [
            {"code": "R2_NON_VERBATIM", "finding": "R2 的 12 条引文中有 6 条不是连续原文。可能包括删除 Markdown 或表格分隔符的改述，不能作为逐字引文通过；不据此自动认定所有数字或评价均为编造。"},
            {"code": "R2_INTERNAL_CONCLUSION_CONFLICT", "finding": "R2 在 referenceObservations.W4.weaknesses 已明确识别 62.52% 与 76% 的错误比较及‘只有美国’的无依据断言，但 comparison 仍给 TIE、accuracy=4、severeFailures=[]，limitations 又称未发现硬约束失败。属于已识别问题未进入硬门禁，不能误记为完全没有发现。"},
            {"code": "R2_UNSUPPORTED_ABSENCE_PRAISED", "finding": "R2 将 W4‘无全球职业维度的代表性调查’作为符合硬约束的证据；固定资料包没有此类资料，不能证明世界上不存在此类资料。"},
            {"code": "DISAGREEMENT_RETAINED", "finding": "两位评审均判 W1 对 W2 为 TIE；W4 对 W2，R1 为 LOSS、R2 为 TIE。保留原始分歧，事实和口径错误单独阻断，不能由平均分或偏好放行。"},
        ],
        "gate": "AI reviews are advisory; hard-checks.json independently governs factual/scope failures. No overall quality pass.",
        "scope": "Same fixed synthetic source packet, no browsing during execution; one work per arm, not a statistical win-rate study.",
    }
    save_json("review-audit.json", audit)
    copy(PRIVATE / "hard-checks.json", "hard-checks.json")
    return audit


def archive_verification(flows: list[dict]) -> None:
    names_counts = [
        ("enhancement.service.impl.DraftDeliveryBoundaryTest", 77),
        ("template.domain.PromptFrameworkSelectorTest", 43),
        ("provider.infrastructure.openai.ResearchEvaluationRequestTest", 2),
        ("provider.infrastructure.openai.OptionalSectionContentContractTest", 12),
        ("provider.infrastructure.openai.OpenAiCompatiblePromptEnhancementProviderTest", 52),
        ("provider.infrastructure.openai.MissingPresentationResponseTest", 3),
        ("provider.domain.PromptFrameworkGenerationContractTest", 8),
        ("history.service.impl.MybatisOptimizationHistoryServiceTest", 14),
        ("enhancement.service.impl.TemplateGuidanceBoundaryTest", 4),
        ("enhancement.service.impl.SourceOwnershipProjectionTest", 21),
        ("enhancement.service.impl.ResearchEvaluationGuidanceTest", 7),
        ("enhancement.service.impl.RequirementFidelityWorkloadTest", 1),
        ("enhancement.service.impl.RequirementFidelityGuardTest", 66),
        ("enhancement.service.impl.ReportDraftGuidanceTest", 6),
        ("enhancement.service.impl.ReportDraftDeliveryAssemblyTest", 11),
        ("enhancement.service.impl.PlatformConstraintPresentationTest", 26),
        ("enhancement.service.impl.PlanScopeSafetyRegressionTest", 31),
        ("enhancement.service.impl.PlanQuestionFilterTest", 34),
        ("enhancement.service.impl.OptionalSectionAssemblyTest", 11),
        ("enhancement.service.impl.OptimizationResultAssemblerTest", 21),
        ("enhancement.service.impl.EffectiveTaskViewTest", 12),
        ("enhancement.service.impl.DynamicPlatformConstraintTest", 32),
    ]
    initial_path = REPO / "tmp/eval43/java-tests.log"
    initial = initial_path.read_text(encoding="utf-8", errors="replace")
    assert "Tests run: 494, Failures: 1, Errors: 0, Skipped: 0" in initial
    assert "DraftDeliveryBoundaryTest.theModelKeepsConcreteDeliverablesWithoutCopyingThePlatformDraftParagraph" in initial
    rerun_path = REPO / "services/api/target/surefire-reports/TEST-com.promptoptimizer.enhancement.service.impl.DraftDeliveryBoundaryTest.xml"
    rerun = ET.parse(rerun_path).getroot()
    assert [int(rerun.attrib[key]) for key in ("tests", "failures", "errors", "skipped")] == [77, 0, 0, 0]
    rerun_projection = {"suite": rerun.attrib["name"], "tests": 77, "failures": 0, "errors": 0, "skipped": 0,
                        "sourceXmlSha256": digest(rerun_path.read_bytes()),
                        "testcases": [{"name": node.attrib["name"], "timeSeconds": node.attrib.get("time"), "passed": True}
                                      for node in rerun.findall("testcase")],
                        "xmlPropertiesAndSystemOutputOmitted": True}
    save_json("local-verification/draft-delivery-rerun.json", rerun_projection)
    initial_extract = "\n".join(line for line in initial.splitlines() if
                                "Tests run: 494," in line or
                                "DraftDeliveryBoundaryTest.theModelKeepsConcreteDeliverablesWithoutCopyingThePlatformDraftParagraph" in line)
    save_bytes("local-verification/java-initial-summary.txt", (initial_extract + "\n").encode("utf-8"))
    front_path = REPO / "tmp/eval43/frontend-tests.log"
    frontend = front_path.read_text(encoding="utf-8-sig")
    assert "61 passed (61)" in frontend and "3 passed (3)" in frontend
    copy(front_path, "local-verification/frontend-tests.log")
    scope = read_json(DEST / "scope-preflight.json")
    assert scope["checks"] == scope["passed"] == 21
    assert sum(count for _, count in names_counts) == 494 and len(names_counts) == 22
    verification = {
        "candidate": "candidate43-original-batch-466-481",
        "java": {"tests": 494, "passed": 494, "failures": 0, "errors": 0, "skipped": 0, "selectedSuites": 22,
                 "aggregation": "Initial selected run 494/1 failure, then only failing DraftDeliveryBoundaryTest (77 tests) rerun passed. Sum distinct selected tests, not 494+77. Later r1 report files are excluded.",
                 "initialRun": {"tests": 494, "failures": 1, "errors": 0, "skipped": 0,
                                "reason": "An existing assertion expected the earlier TASK/OUTPUT instruction wording; assertion updated to the accepted optional-section contract.",
                                "fullLocalLogSha256": digest(initial_path.read_bytes()),
                                "summaryArtifact": "local-verification/java-initial-summary.txt"},
                 "rerunArtifact": "local-verification/draft-delivery-rerun.json",
                 "suites": [{"name": f"com.promptoptimizer.{name}", "tests": count} for name, count in names_counts],
                 "scope": "Affected selected suites only; not a complete repository regression."},
        "frontend": {"files": 3, "tests": 61, "passed": 61, "failed": 0,
                     "suites": {"constraintEditing.test.ts": 26, "optimizationLifecycle.test.ts": 12,
                                "optimizationPresentation.test.ts": 23},
                     "logSha256": digest(front_path.read_bytes()), "artifact": "local-verification/frontend-tests.log"},
        "scopeChecks": scope,
        "realFlow": {"completed": 3, "failed": 1, "completedCopiesAndHistoryIndependentlyMatched": len(flows),
                     "failedCase": "HANDOVER 472; CONFIRMED_DECISION_MISSING at sections.CONSTRAINTS.requirementReference"},
        "overallQualityGate": "NOT_PASSED",
        "limitations": ["Local checks do not prove downstream quality or business release readiness.",
                        "No live calls were made by this archive script.",
                        "No claim that all code-generation, examples, Plan or historical failures are closed."],
    }
    save_json("verification.json", verification)


def main() -> None:
    assert (REPO / "AGENTS.md").is_file() and PRIVATE.is_dir()
    responses = archive_model_responses()
    flows = archive_flows()
    audit = archive_works_and_reviews()
    archive_verification(flows)
    files = [{"path": str(path.relative_to(DEST)).replace("\\", "/"),
              "sha256": digest(path.read_bytes()), "bytes": path.stat().st_size} for path in WRITTEN]
    save_json("archive-manifest.json", {
        "scriptSha256": digest(Path(__file__).read_bytes()), "files": files,
        "rawModelResponses": len(responses), "successfulFlows": len(flows),
        "reviewQuoteAudit": [{key: report[key] for key in ("reviewer", "totalQuotes", "exactQuotes")} for report in audit["reports"]],
        "privacy": "Explicit allowlist only. No accounts, credentials, cookies, authorization headers, plan/session/history identity IDs, screenshots, server context or full requests published.",
    })
    print(json.dumps({"archivedFiles": len(WRITTEN), "modelResponses": len(responses), "successfulFlows": len(flows),
                      "reviewQuotes": [(row["totalQuotes"], row["exactQuotes"]) for row in audit["reports"]]}))


if __name__ == "__main__":
    main()
