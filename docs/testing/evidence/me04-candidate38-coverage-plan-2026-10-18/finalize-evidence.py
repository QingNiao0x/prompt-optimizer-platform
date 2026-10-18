"""Derive public summaries from retained synthetic originals; never call a model or export account data."""
from pathlib import Path
import hashlib
import json

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
OFFLINE = ROOT / "tmp/me04-candidate38-20261018"
LIVE = ROOT / "tmp/me04-candidate38-live-20261018"
LEDGER = ROOT / "tmp/me00a-20261012"


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(name, value):
    # Existing evidence is immutable; reruns require a new, explicit destination.
    with (PUBLIC / name).open("x", encoding="utf-8", newline="\n") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write("\n")


def main():
    """Bind live candidate38 results separately from the untested-live candidate38r1 fix."""
    live_manifest = sha(PUBLIC / "candidate38-source.json")
    r1_manifest = sha(PUBLIC / "candidate38r1-source.json")
    calls = []
    for number in (413, 414, 415):
        path = LEDGER / f"traces/{number}.json"
        trace = read(path)
        request, response = trace["request"], trace["response"]
        calls.append({
            "trace": number, "phase": trace["phase"], "purpose": trace["purpose"],
            "sourceCandidate": "candidate38", "sourceManifestSha256": live_manifest,
            "traceFile": path.relative_to(ROOT).as_posix(), "traceSha256": sha(path),
            "httpStatus": trace["httpStatus"], "elapsedMs": trace["elapsedMs"],
            "model": response["model"], "usage": response["usage"],
            "finishReasons": [choice["finish_reason"] for choice in response["choices"]],
            "parameters": {key: request[key] for key in ("temperature", "max_tokens", "thinking")},
        })
    plan = read(LIVE / "flow/recovered-plan.private.json")["plan"]
    copy_check = read(LIVE / "live-results/REQUESTS/COMPACT/check.json")
    input_lock = read(LIVE / "work/input-lock.json")
    assert input_lock["copySha256"] == copy_check["copySha256"]
    assert input_lock["actualUserAnswers"] == [] and not input_lock["systemTaskInstructionAdded"]
    assert [row["id"] for row in plan["questions"]] == ["target_pages", "title_definition", "delivery_form"]
    save("real-chain.json", {
        "candidate": "candidate38", "candidate38r1RealCalls": 0, "calls": calls,
        "tokens": sum(row["usage"]["total_tokens"] for row in calls),
        "accountReused": True, "newAccountsCreated": 0, "mockUsed": False,
        "naturalPlan": {"trace": 413, "summary": plan["summary"], "questions": plan["questions"],
            "originalZeroQuestionDependency": "NOT_MET", "answersInvented": False,
            "dependentPlanEnhancementAndWork": "SKIPPED", "remainingQuestionNecessity": "REQUIRES_SEPARATE_SCOPE_REVIEW"},
        "directTrack": {"trace": 414, "amendment": 32, "nativeCopyAndHistory": copy_check,
            "extraExampleRequirement": "完整可运行的 Python 代码（含请求、解析、异常处理与入口调用示例）。",
            "exampleOffGate": "FAIL_ENHANCEMENT_AND_VALIDATION_OMISSION"},
        "work": {"trace": 415, "workSha256": sha(LIVE / "work/work.md"), "inputLock": input_lock,
            "sameInformationBaseline": "NOT_RUN", "newAIReviewers": 0},
        "qualityRetries": 0, "protocolRepairCalls": 0, "overallQualityGate": "OPEN",
    })

    parent_rows = read(OFFLINE / "r1-replay-parent/summary.json")
    fixed_rows = read(OFFLINE / "r1-replay-final/summary.json")
    comparisons = []
    for parent, fixed in zip(parent_rows, fixed_rows, strict=True):
        assert (parent["trace"], parent["format"], parent["modelResponseSha256"]) == (fixed["trace"], fixed["format"], fixed["modelResponseSha256"])
        unchanged = parent["trace"] != 414
        if unchanged:
            assert all(parent[key] == fixed[key] for key in ("bodySha256", "constraintGroups", "ambiguities"))
        else:
            assert parent["state"] == "ASSEMBLED" and fixed["state"] == "REJECTED"
            assert fixed["reason"] == "RULE_CONFLICT" and fixed["field"] == "sections.OUTPUT"
        comparisons.append({
            "trace": parent["trace"], "format": parent["format"], "originalModelResponseSha256": parent["modelResponseSha256"],
            "parentState": parent["state"], "fixedState": fixed["state"], "fixedReason": fixed.get("reason"),
            "fixedField": fixed.get("field"), "bodySourcesPendingUnchanged": True if unchanged else None,
            "copiedResponseFabricated": False,
        })
    save("r1-replay-comparison.json", {
        "parent": "candidate38", "fixed": "candidate38r1", "parentManifestSha256": live_manifest,
        "fixedManifestSha256": r1_manifest, "newModelCalls": 0, "entries": comparisons,
        "scope": "Offline original-response replay in both modes; rejected414 does not prove a new successful response",
    })

    checks = read(LIVE / "requests-checks/check/result.json")
    old_claims = read(LIVE / "execution-claims-before-r1.json")
    new_claims = read(LIVE / "execution-claims-r1.json")
    assert old_claims["state"] == "NOT_CLAIMED"
    assert new_claims["state"] == "FAIL_UNSUPPORTED_EXECUTION_CLAIM" and len(new_claims["claims"]) == 9
    echoes = [row for row in checks["diagnostics"] if row.get("syntheticCredentialDiagnostic") == "ECHO_OBSERVED"]
    assert len(echoes) == 12 and len(checks["originalTaskChecks"]) == 4
    assert all(row["state"] == "PASS" for row in checks["originalTaskChecks"])
    assert checks["authoredTests"][0]["observation"]["authoredTests"] == {"tests": 15, "failures": 0, "errors": 0, "skipped": 0}
    save("actual-work-checks.json", {
        "trace": 415, "sourceCandidate": "candidate38", "workSha256": sha(LIVE / "work/work.md"),
        "originalChecker": "docs/testing/evidence/me04-candidate37-rules-examples-2026-10-18/check-requests-work.py",
        "originalCheckerSha256": sha(ROOT / "docs/testing/evidence/me04-candidate37-rules-examples-2026-10-18/check-requests-work.py"),
        "checkResultSha256": sha(LIVE / "requests-checks/check/result.json"),
        "mappingSha256": sha(LIVE / "requests-checks/mapping.json"),
        "sourceRepaired": checks["sourceRepaired"], "syntax": checks["syntax"], "extractedFiles": checks["extraction"]["files"],
        "coreChecksPassed": 4, "authoredTests": checks["authoredTests"][0]["observation"]["authoredTests"],
        "syntheticCredentialEchoes": {"functionReturn": 6, "cliOutput": 6, "standardExceptionChain": 0, "gate": "FAIL"},
        "networkRequests": checks["actualNetworkRequests"], "syntheticValuesOnly": True,
        "originalExecutionClaimAudit": old_claims, "correctedExecutionClaimAudit": new_claims,
        "unselectedPythonBlocks": checks["unselectedPythonBlocks"],
        "executionScope": "Blocks1/6 implementation plus tests executed together; block5 usage not exhaustively executed",
        "overallWorkGate": "FAIL_SAFETY_AND_UNSUPPORTED_EXECUTION_CLAIMS",
        "newReviewsOrWinRate": "NOT_AVAILABLE",
    })
    save("tool-corrections.json", {
        "modelCallsIntroduced": 0,
        "planWait": {"observedFailure": read(LIVE / "flow/failure.json"),
            "actualEvidence": "Call413 and the same browser store contain a successful Plan with3 questions",
            "correction": "Exact optimization/plan route and explicit response/capture waits",
            "frozenOriginal": "run-flow-before-wait-fix.txt", "correctedValidation": "SYNTAX_ONLY_NOT_REAL_RERUN"},
        "workSetup": {"failure": "Missing empty context.files before reservation", "actualExtraCalls": 0,
            "correction": "Explicit empty local material list; user message still equals native copy"},
        "executionClaims": {"before": old_claims["state"], "after": new_claims["state"], "unsupportedAssertions": 9,
            "cause": "Old checker excluded all tables", "correction": "Only inspect explicit actual-test-status cells",
            "originalAuditPreserved": True, "laterTestsDoNotValidateEarlierClaim": True,
            "semanticCompleteness": "OPEN"},
    })
    # Preserve the original full directory snapshot, but distinguish the newly failing suite
    # from stale reports left by the preceding complete regression in that directory.
    verification = read(PUBLIC / "verification-r1.json")
    red = next(row for row in verification["initialExampleRed"]["suites"] if row["name"].endswith("ParentheticalExampleDeliveryTest"))
    save("r1-focused-red.json", {"suite": red, "staleOtherReportsExcludedFromNewRedCount": True,
        "finalPositiveNegativeCases": 20, "finalAffectedJavaTests": 1199, "independentScriptTests": 37})
    tool_paths = [path for path in PUBLIC.rglob("*") if path.is_file() and path.suffix in {".py", ".mjs", ".java", ".ps1", ".txt"}]
    save("evaluation-tools-after-r1.json", {"scope": "After evaluation corrections; does not replace before-live tool hashes",
        "files": [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path)} for path in sorted(tool_paths)]})
    print(json.dumps({"actualCalls": len(calls), "totalTokens": sum(row["usage"]["total_tokens"] for row in calls),
        "replayEntries": len(comparisons), "credentialEchoes": len(echoes), "unsupportedClaims": len(new_claims["claims"]), "newModelCalls": 0}))


if __name__ == "__main__":
    main()
