"""Archive bound synthetic-work diagnostics without publishing account data or changing originals.

All model sends precede this offline analysis. RAW requirements, added commitments,
unselected code, and independently observed failures remain distinct.
"""
from pathlib import Path
import hashlib
import json

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
LIVE = ROOT / "tmp/me04-candidate37-live-20261018"
LEDGER = ROOT / "tmp/me00a-20261012"


def sha(data):
    """Bind original bytes, including newline conventions."""
    return hashlib.sha256(data).hexdigest()


def load(path):
    """Read an explicitly named local evidence file; no network or secret discovery."""
    return json.loads(path.read_text(encoding="utf-8-sig"))


def save(name, data):
    """Never replace an existing result or conceal a failed original."""
    with (PUBLIC / name).open("x", encoding="utf-8") as stream:
        json.dump(data, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def main():
    """Verify frozen source, summarize actual calls and retain independent hard failures."""
    manifest = load(PUBLIC / "candidate37-source.json")
    assert all(sha((ROOT / row["path"]).read_bytes()) == row["sha256"] for row in manifest["sourceFiles"])
    assert all(sha((ROOT / manifest["runtimeSnapshot"] / row["path"]).read_bytes()) == row["sha256"]
               for row in manifest["runtimeFiles"])
    main_runtime_matches = all(sha((ROOT / "services/api/target/classes" / row["path"]).read_bytes()) == row["sha256"]
                               for row in manifest["runtimeFiles"])
    assert main_runtime_matches
    traces = [load(LEDGER / "traces" / f"{number:03}.json") for number in range(404, 413)]
    assert all(row["version"] == "candidate37-live" and row["httpStatus"] == 200
               and row["response"]["choices"][0]["finish_reason"] == "stop" for row in traces)
    calls = [{"trace": number, "purpose": row["purpose"], "requestedModel": row["request"]["model"],
              "providerReportedModel": row["response"].get("model"), "httpStatus": row["httpStatus"],
              "finishReason": row["response"]["choices"][0]["finish_reason"], "elapsedMs": row["elapsedMs"],
              "usage": row["response"].get("usage"),
              "requestSha256": sha(json.dumps(row["request"], ensure_ascii=False, separators=(",", ":")).encode()),
              "traceFileSha256": sha((LEDGER / "traces" / f"{number:03}.json").read_bytes())}
             for number, row in zip(range(404, 413), traces)]
    browser = [load(PUBLIC / "live-checks" / f"{group}-{arm}.json") for group, arm in
               [("PATCH", "STRUCTURED"), ("REQUESTS", "COMPACT"), ("REQUESTS", "STRUCTURED")]]
    assert all(row["nativeCopyMatches"] and row["currentStoreMatchesResponse"]
               and all(value is True for key, value in row["history"].items() if key != "totalDelta")
               and row["history"]["totalDelta"] == 1 for row in browser)
    save("real-chain.json", {"candidate": "candidate37", "actualNewCalls": 9, "calls": calls,
         "sourceManifestSha256": sha((PUBLIC / "candidate37-source.json").read_bytes()),
         "sourceFilesVerified": len(manifest["sourceFiles"]), "runtimeFilesVerified": len(manifest["runtimeFiles"]),
         "mainBackendRuntimeMatchesFrozen": main_runtime_matches,
         "browserGenerations": len(browser), "allNativeCopyStoreNewHistoryMatch": True,
         "accountReused": True, "accountsCreated": 0, "qualityRetries": 0,
         "scope": "Three direct generations; no new natural Plan, editor save, real-site scrape, or professional-domain claim."})

    reports = []
    for arm in ("RAW", "COMPACT", "STRUCTURED"):
        path = LIVE / "requests-checks" / arm / "result.json"
        report = load(path)
        assert report["workSha256"] == sha((LIVE / "works/REQUESTS" / arm / "work.md").read_bytes())
        diagnostics = report["diagnostics"]
        reports.append({"task": "REQUESTS", "arm": arm, "workSha256": report["workSha256"],
             "reportSha256": sha(path.read_bytes()), "sourceRepaired": False, "actualNetworkRequests": 0,
             "syntax": report["syntax"]["status"],
             "coreChecksPassed": sum(row["state"] == "PASS" for row in report["originalTaskChecks"]),
             "coreChecksTotal": len(report["originalTaskChecks"]),
             "authoredTests": [row["observation"]["authoredTests"] for row in report["authoredTests"]],
             "unselectedPythonBlocks": report["unselectedPythonBlocks"],
             "directCredentialEchoCases": [{"entry": row["entry"], "mode": row["mode"]} for row in diagnostics
                                            if row["observation"].get("canaryObserved")],
             "standardTracebackCredentialEchoCases": [{"entry": row["entry"], "mode": row["mode"]} for row in diagnostics
                                                       if row["observation"].get("canaryObservedInStandardTraceback")],
             "declaredCommitments": report["enhancedCommitments"]["state"],
             "completeHardGate": report["completeHardGate"],
             "executionClaimsState": report["executionClaimsAudit"]["state"]})
    patch_path = LIVE / "patch-checks/STRUCTURED/result.json"
    patch = load(patch_path)
    assert patch["workSha256"] == sha((LIVE / "works/PATCH/STRUCTURED/work.md").read_bytes())
    reports.append({"task": "PATCH", "arm": "STRUCTURED", "workSha256": patch["workSha256"],
         "reportSha256": sha(patch_path.read_bytes()), "sourceRepaired": False,
         "compilation": patch["compilation"]["status"], "generatedTests": patch["generatedTests"],
         "independentChecks": patch["independentChecks"]["details"],
         "requiredCodeGatesPassed": patch["requiredCodeGatesPassed"],
         "scope": "Single fixed-copy diagnosis; no fresh paired RAW baseline or PATCH AI review in this batch."})
    save("real-work-checks.json", {"candidate": "candidate37", "works": reports,
         "executionClaimsAudit": load(LIVE / "execution-claims.json"),
         "allWorkHashesUnchanged": True, "noWorksRepaired": True, "realModelQualityGate": "FAIL",
         "limitations": ["Unselected invocation/batch code was not fully executed; whole-work gates remain OPEN or FAIL.",
                         "RAW task did not require authored tests or a credential rule; no added rule is silently applied to it.",
                         "No positive execution claim found is not proof of all possible truthfulness properties.",
                         "New generated tests pass in this sample; old failed tests and false claims remain unchanged."]})

    # Publish original synthetic reviews, not private account/history material or the unblinding map.
    for label in ("R1", "R2"):
        save(f"review-{label.lower()}-original.json", load(LIVE / "reviews" / label / "review.md"))
    audit = load(LIVE / "reviews/audit.private.json")
    left, right = audit["reviewers"]["R1"]["audit"], audit["reviewers"]["R2"]["audit"]
    all_pairs = []
    for pair1, pair2 in zip(left["comparisons"], right["comparisons"]):
        assert (pair1["left"], pair1["right"]) == (pair2["left"], pair2["right"])
        all_pairs.append({"left": pair1["left"], "right": pair1["right"],
                          "R1": pair1["originalVerdict"], "R2": pair2["originalVerdict"],
                          "disagree": pair1["originalVerdict"] != pair2["originalVerdict"],
                          "independentGate": pair1["independentGate"]})
    save("review-adjudication.json", {"candidate": "candidate37", "modelCalls": 0,
         "sameSupplierIndependentAiReviews": True, "allBlindPairs": all_pairs,
         "literalQuoteErrors": {"R1": len(left["errors"]), "R2": len(right["errors"])},
         "scoresAndVerdictsUnchanged": True, "acceptanceGate": "FAIL",
         "evidenceAssessment": [
             "Both reviewers noticed credential observations, but minimized enhanced-input hard failures; neither score is a release gate.",
             "R1 has one non-literal quotation; R2 has four. Original quotations are not silently repaired.",
             "STRUCTURED versus COMPACT differs between reviewers (win versus tie); both independently fail a safety commitment.",
             "Claims about empty/nested title parsing or apparent_encoding remain unverified semantic hypotheses in the reviews.",
             "Absence of authored tests was not a RAW-task violation; added tests are not proof that enhanced code meets all promises.",
             "Legitimate not-run declarations are not execution defects; subsequent independent tests establish only their own later execution."
         ]})
    print(json.dumps({"realCalls": 9, "sourceFilesVerified": len(manifest["sourceFiles"]),
                      "runtimeFilesVerified": len(manifest["runtimeFiles"]), "works": 4,
                      "qualityGate": "FAIL", "modelCallsFromThisTool": 0}))


if __name__ == "__main__":
    main()
