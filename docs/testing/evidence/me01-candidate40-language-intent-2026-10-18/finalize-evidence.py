"""Bind the offline repair and unchanged downstream originals; never call models or export account data."""
from pathlib import Path
import hashlib
import json

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
PRIVATE = ROOT / "tmp/me01-candidate40-20261018"


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(name, data):
    with (PUBLIC / name).open("x", encoding="utf-8", newline="\n") as output:
        json.dump(data, output, ensure_ascii=False, indent=2)
        output.write("\n")


def main():
    """Separate source repair, stale tests, downstream violations, inference and pending live evidence."""
    parent = load(PRIVATE / "replay-parent/intent-replay.json")
    current = load(PRIVATE / "replay-current/intent-replay.json")
    rows = []
    for old, new in zip(parent["observations"], current["observations"], strict=True):
        assert old["rawPrompt"] == new["rawPrompt"]
        assert old["sharedIdentityMatches"] and new["sharedIdentityMatches"]
        rows.append({"rawPrompt": new["rawPrompt"], "parentIntent": old["intent"], "currentIntent": new["intent"],
            "parentPlatformRules": len(old["platformMandatory"]), "currentPlatformRules": len(new["platformMandatory"]),
            "parentPackagingQuestionRetained": old["packagingQuestionRetained"],
            "currentPackagingQuestionRetained": new["packagingQuestionRetained"]})
    assert len(rows) == 12
    assert rows[0]["parentPlatformRules"] == 1 and rows[0]["currentPlatformRules"] == 5
    assert not rows[0]["currentPackagingQuestionRetained"] and rows[3]["currentPackagingQuestionRetained"]
    save("intent-replay-parent.json", parent)
    save("intent-replay-current.json", current)
    save("replay-comparison.json", {"parent": "candidate39", "candidate": "candidate40", "newModelCalls": 0,
        "observations": rows, "actualCopyHistoryAndDownstreamWork": "NOT_NEWLY_VALIDATED", "qualityGate": "OPEN"})

    native = ROOT / "tmp/me04-candidate38-live-20261018/live-results/REQUESTS/COMPACT/native-copy.md"
    work = ROOT / "tmp/me04-candidate38-live-20261018/work/work.md"
    lock = load(ROOT / "tmp/me04-candidate38-live-20261018/work/input-lock.json")
    checks = load(ROOT / "tmp/me04-candidate38-live-20261018/requests-checks/check/result.json")
    claims = load(ROOT / "tmp/me04-candidate38-live-20261018/execution-claims-r1.json")
    assert sha(native) == lock["copySha256"] and lock["actualUserAnswers"] == []
    assert len([row for row in checks["diagnostics"] if row.get("syntheticCredentialDiagnostic") == "ECHO_OBSERVED"]) == 12
    assert claims["state"] == "FAIL_UNSUPPORTED_EXECUTION_CLAIM" and len(claims["claims"]) == 9
    body = native.read_text(encoding="utf-8")
    assert "按 URL 输出对应标题" in body and "不得在代码、日志或响应中泄露" in body
    save("downstream-strategy-review.json", {
        "reviewType": "Local source/original review, not a new AI review or model comparison",
        "sourceTraces": [414, 415], "copySha256": sha(native), "workSha256": sha(work),
        "inputLock": lock, "sourceRepaired": checks["sourceRepaired"],
        "credentialFinding": {"gate": "FAIL_RETAINED", "functionReturnEchoes": 6, "cliEchoes": 6,
            "platformActualSecretLeakEvidence": False,
            "promptEvidence": ["按 URL 输出对应标题，形式可为打印或返回字典/列表", "不得在代码、日志或响应中泄露密码、Token、API Key 或私钥"],
            "workEvidence": "fetch_title retains the original URL in the result; CLI prints that URL with the title",
            "attribution": "Downstream violates confidentiality; output-mapping wording may contribute, causality remains mixed or undetermined without a controlled trial"},
        "truthfulnessFinding": {"gate": claims["state"], "detectedUnsupportedAssertions": 9,
            "instructionReachedCopy": "未实际运行的验证不得写成通过", "executionToolsAtGeneration": False,
            "laterAuthoredTests": 15, "laterPassCannotValidateEarlierExecutionClaim": True,
            "semanticCoverage": "OPEN; nine detected assertions are not a claim of exhaustive prose coverage"},
        "strategies": [
            {"strategy": "Append more global confidentiality and execution bans", "decision": "NOT_ADOPTED",
             "reason": "Existing rules reached the copied prompt; repeated severe failures and no benefit/cost evidence"},
            {"strategy": "Simplify the existing output mapping requirement", "decision": "PENDING_CONTROLLED_EVIDENCE",
             "hypothesis": "Removing optional return-field ambiguity may reduce URL echo while preserving the title task",
             "risk": "May remove useful URL-to-title correspondence for explicit batch tasks; cannot force one function signature globally",
             "adoptionGate": "Same fixed data/model, originals retained, privacy and useful task output both improve, normal batch variants do not regress"},
            {"strategy": "Change downstream model with the same copied prompt", "decision": "OPTIONAL_DIAGNOSTIC_NOT_ASSUMED_BETTER",
             "risk": "Model variation and price/latency; no model-specific template without evidence"},
            {"strategy": "Rewrite generated downstream code or use SensitiveValueDetector as a code analyzer", "decision": "NOT_A_PROMPT_REPAIR",
             "reason": "Platform generates prompts; redacting source text cannot prove runtime return/log/exception safety"}
        ],
        "newProductInstructionOrRetryChange": False, "newModelCalls": 0,
        "reviewDisagreementsAndInaccurateQuotes": "ORIGINALS_RETAINED_NO_SCORE_BASED_RELEASE",
        "professionalRubricAndYllGates": "OPEN_NOT_REVALIDATED"
    })
    save("legacy-test-review.json", {"parentCandidate": "candidate39", "probe": load(PRIVATE / "legacy-parent.json"),
        "productionTemplateOrGuidanceChanged": False,
        "correctedAssertions": "Current code delivery and necessary run instructions, no plan-document substitution; normal/error/boundary coverage and truthful verification",
        "initialExpandedFailures": 2, "followupHiddenAcceptanceWordingFailure": 1,
        "finalExpandedTests": 1838, "finalFailures": 0, "errors": 0, "skipped": 0})
    save("tool-hashes.json", {"scope": "Offline helpers; numeric approval remains separate",
        "files": [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path)}
                  for path in sorted(PUBLIC.iterdir()) if path.suffix in {".java", ".py"}]})
    print(json.dumps({"intentComparisons": len(rows), "credentialFailuresRetained": 12,
        "unsupportedClaimsRetained": 9, "newModelCalls": 0}))


if __name__ == "__main__":
    main()
