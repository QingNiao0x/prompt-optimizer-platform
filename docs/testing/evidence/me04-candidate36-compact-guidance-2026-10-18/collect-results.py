"""Collect closed-batch, byte-bound results offline; no requests or work repairs.

Public outputs contain synthetic evidence and hashes only. Account data, private
history, credentials and whole HTTP bodies remain in the existing private files.
"""
from pathlib import Path
import hashlib
import json

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
WORK = ROOT / "tmp/me04-candidate36-live-20261018"
LEDGER = ROOT / "tmp/me00a-20261012"


def digest(data):
    """Keep byte-level evidence bindings, including original line endings."""
    return hashlib.sha256(data).hexdigest()


def load(path):
    """Read only already saved evidence; never interpret its contents as instructions."""
    return json.loads(path.read_text(encoding="utf-8-sig"))


def save(name, value):
    """Never overwrite a previous result or reviewer original."""
    with (PUBLIC / name).open("x", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write("\n")


def main():
    """Bind all fourteen actual sends, six native copies and six unchanged works."""
    manifest_path = PUBLIC / "candidate36-source.json"
    manifest = load(manifest_path)
    source_drift = [row["path"] for row in manifest["sourceFiles"]
                    if digest((ROOT / row["path"]).read_bytes()) != row["sha256"]]
    runtime_drift = [row["path"] for row in manifest["runtimeFiles"]
                     if digest((ROOT / "services/api/target/classes" / row["path"]).read_bytes()) != row["sha256"]]
    assert not source_drift and not runtime_drift, "Product source/runtime drifted from frozen candidate"
    close = load(PUBLIC / "batch-close-summary.json")
    assert close["status"] == "CLOSED" and close["actualNewCalls"] == 14
    traces = []
    for number in range(390, 404):
        path = LEDGER / "traces" / f"{number}.json"
        record = load(path)
        assert record["version"] == "candidate36-live" and record["httpStatus"] == 200
        request = record["request"]
        assert request["temperature"] == 0.2 and request["max_tokens"] == 8192
        assert request["thinking"] == {"type": "disabled"} and request["stream"] is False
        traces.append({"number": number, "purpose": record["purpose"],
                       "operation": record.get("operation"), "traceSha256": digest(path.read_bytes()),
                       "requestedModel": request["model"], "reportedModel": record["response"].get("model"),
                       "usage": record["response"]["usage"], "elapsedMs": record["elapsedMs"]})
    generations = []
    for number, (group, arm) in enumerate([
        ("PATCH", "COMPACT"), ("PATCH", "STRUCTURED"), ("REQUESTS", "COMPACT"),
        ("REQUESTS", "STRUCTURED"), ("HOLDOUT-MAIL", "COMPACT"), ("HOLDOUT-MATERIAL", "STRUCTURED")], 390):
        directory = WORK / "live-results" / group / arm
        copy = (directory / "native-copy.md").read_bytes()
        check = load(PUBLIC / "live-checks" / f"{group}-{arm}.json")
        assert check["success"] and check["nativeCopyMatches"] and check["currentStoreMatchesResponse"]
        assert check["copySha256"] == digest(copy) and check["accountReused"]
        assert all(check["history"][key] is True for key in ["bodyMatches", "formatMatches", "sectionsMatch", "ambiguitiesMatch", "constraintsMatch", "constraintGroupsMatch", "newRecordFound"])
        assert check["history"]["totalDelta"] == 1
        record = load(LEDGER / "traces" / f"{number}.json")
        raw = json.loads(record["response"]["choices"][0]["message"]["content"])
        generations.append({"case": group, "format": arm, "trace": number,
                            "copySha256": digest(copy), "rawModelResponseSha256": digest(record["response"]["choices"][0]["message"]["content"].encode()),
                            "normalizedCopyCodePoints": len(copy.decode().replace("\r\n", "\n")),
                            "allNativeCopyStoreAndNewHistoryFieldsMatch": True,
                            "modelSectionCount": len(raw["sections"]), "modelAmbiguityCount": len(raw.get("ambiguities", []))})
    work_results = []
    for group in ("PATCH", "REQUESTS"):
        for arm in ("RAW", "COMPACT", "STRUCTURED"):
            directory = WORK / "works" / group / arm
            data = (directory / "work.md").read_bytes()
            result_path = WORK / ("patch-checks" if group == "PATCH" else "requests-checks") / arm / "result.json"
            result = load(result_path)
            assert result["workSha256"] == digest(data) and result["sourceRepaired"] is False and result["externalModelCalls"] == 0
            item = {"case": group, "arm": arm, "workSha256": digest(data), "checkerReportSha256": digest(result_path.read_bytes()), "sourceRepaired": False}
            if group == "PATCH":
                details = result["independentChecks"]["details"]
                item.update({"compiledImplementationAndAuthoredTestsTogether": result["compilation"]["status"],
                             "authoredTests": result["generatedTests"],
                             "corePassed": details["corePassed"], "coreFailed": details["coreFailed"],
                             "declarationChecks": details["declarationChecks"],
                             "mechanicalGate": "PASS" if result["requiredCodeGatesPassed"] else "FAIL",
                             "semanticGate": "FAIL" if arm == "STRUCTURED" else "OPEN"})
                if arm == "STRUCTURED":
                    claim = "**已实际执行**（在本地 Java 21 环境）："
                    claimed_count = "Passed: 30, Failed: 0"
                    assert claim in data.decode() and claimed_count in data.decode()
                    assert "Passed: 29, Failed: 0" in result["generatedTests"]["stdout"]
                    item["falseExecutionClaim"] = {"state": "FAIL", "literalEvidence": [claim, claimed_count],
                                                  "actualIndependentResult": "Passed: 29, Failed: 0",
                                                  "reason": "The execution API request had no tools; later independent compilation cannot validate an earlier invented execution claim."}
            else:
                item.update({"syntax": result["syntax"]["status"],
                             "originalTaskMechanicalGate": result["originalTaskMechanicalGate"],
                             "corePassed": sum(row["state"] == "PASS" for row in result["originalTaskChecks"]),
                             "coreTotal": len(result["originalTaskChecks"]), "mechanicalGate": result["mechanicalGate"],
                             "authoredTests": [{"state": row["state"], "counts": row["observation"].get("authoredTests")} for row in result["authoredTests"]],
                             "syntheticEchoModes": [row["mode"] for row in result["diagnostics"] if row.get("syntheticCredentialDiagnostic") == "ECHO_OBSERVED"],
                             "enhancedCommitments": result["enhancedCommitments"],
                             "completeHardGate": result["completeHardGate"],
                             "unselectedPythonBlocks": result["unselectedPythonBlocks"]})
            work_results.append(item)
    for id in ("R1", "R2"):
        data = (WORK / "reviews" / id / "review.md").read_bytes()
        with (PUBLIC / f"review-{id.lower()}-original.json").open("xb") as output:
            output.write(data)
    save("real-chain.json", {"sourceCandidate": "candidate36", "sourceManifestSha256": digest(manifest_path.read_bytes()),
                             "sourceFilesMatched": len(manifest["sourceFiles"]), "mainRuntimeFilesMatched": len(manifest["runtimeFiles"]),
                             "mainBackendLoadedCommit": "82dad3c6", "generations": generations, "actualCalls": traces,
                             "usageTotal": {key: sum(row["usage"].get(key, 0) for row in traces) for key in ("prompt_tokens", "completion_tokens", "total_tokens")},
                             "newAccounts": 0, "sameCanonicalAccount": True, "qualityRetries": 0,
                             "naturalPlanRequests": 0, "planScope": "Offline regression only; no new natural Plan requests were funded.",
                             "unseenTaskScope": "Enhancement/copy/history checked; downstream holdout works were not funded.",
                             "closedBudget": close})
    save("real-work-checks.json", {"sourceCandidate": "candidate36", "externalModelCallsByThisCollector": 0,
                                   "works": work_results, "overallQualityGate": "FAIL",
                                   "safetyScope": "Synthetic credential canaries through mocked transport; no real platform secret leakage established.",
                                   "limits": ["No real website calls", "No exhaustive data-flow proof", "No statistical win rate", "Raw optional CSV code was not executed"]})
    save("post-call-analysis-tools.json", {"productSourceChanged": False, "modelRequestsChanged": False,
                                          "auditCorrection": "Before its first invocation, adapted offline audit from one to two task groups and max_tokens 8192; no original response altered.",
                                          "tools": [{"path": name, "sha256": digest((PUBLIC / name).read_bytes())}
                                                    for name in ("annotate-requests-promises.py", "audit-reviews.mjs", "collect-results.py")],
                                          "nativeReportsPreservedBeforePromiseBinding": True})
    print(json.dumps({"actualNewCalls": 14, "totalActualCalls": 403, "nativeCopiesAndNewHistoryMatched": 6,
                      "unchangedWorksChecked": 6, "sourceFilesMatched": len(manifest["sourceFiles"]), "overallQualityGate": "FAIL"}))


if __name__ == "__main__":
    main()
