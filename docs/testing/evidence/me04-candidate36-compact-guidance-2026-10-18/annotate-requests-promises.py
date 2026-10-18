"""Bind existing observations to actual copied requirements; never edit or execute a work.

The initial checker result is preserved. Original-task checks, added promises and
supplementary synthetic observations stay separate, including incomplete coverage.
"""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[4]
WORK = ROOT / "tmp/me04-candidate36-live-20261018"
SAFETY = "不得在代码、日志或响应中泄露密码、Token、API Key 或私钥。"
TESTS = "为核心逻辑补充正常、异常和边界场景测试。"


def sha(data):
    """Hash the unchanged evidence bytes, without newline normalization."""
    return hashlib.sha256(data).hexdigest()


def main():
    """Project only observed promise failures; no unexercised behavior becomes PASS."""
    summary = []
    for arm in ("RAW", "COMPACT", "STRUCTURED"):
        path = WORK / "requests-checks" / arm / "result.json"
        before = path.read_bytes()
        report = json.loads(before)
        assert report["workSha256"] == sha((WORK / "works/REQUESTS" / arm / "work.md").read_bytes())
        assert report["sourceRepaired"] is False and report["externalModelCalls"] == 0
        assert report["enhancedCommitments"]["state"] == "OPEN_REQUIRES_EXACT_PROMPT_AND_WORK_REVIEW"
        echoes = [{"entry": row["entry"], "mode": row["mode"],
                   "canarySentToTransport": row["observation"]["canarySentToTransport"]}
                  for row in report["diagnostics"] if row.get("syntheticCredentialDiagnostic") == "ECHO_OBSERVED"]
        assert all(row["canarySentToTransport"] for row in echoes)
        if arm == "RAW":
            commitments = {"state": "NOT_APPLICABLE_TO_ORIGINAL_TASK", "checks": [],
                           "reason": "The original requests/title task did not require tests or explicitly prohibit credential echo. Observations remain supplementary; unselected code coverage remains OPEN."}
        else:
            prompt_path = WORK / "live-results/REQUESTS" / arm / "native-copy.md"
            prompt_bytes = prompt_path.read_bytes()
            prompt = prompt_bytes.decode("utf-8")
            assert SAFETY in prompt and TESTS in prompt
            authored = report["authoredTests"]
            tests_passed = bool(authored) and all(row["state"] == "PASS" for row in authored)
            checks = [
                {"name": "explicit_credential_non_echo_commitment", "state": "FAIL" if echoes else "OPEN",
                 "promptEvidence": SAFETY, "evidence": echoes,
                 "reason": "The supplied function propagated an exception containing a synthetic credential after its URL reached the mocked transport. This is not evidence of leaked real platform credentials."},
                {"name": "supplied_executable_core_tests", "state": "PASS" if tests_passed else "FAIL",
                 "promptEvidence": TESTS, "authoredTestFiles": len(authored),
                 "reason": "Supplied executable tests ran unchanged." if tests_passed else
                    "An authored test failed unchanged: assert_called_with expects one combined write, while print(title) writes title and newline separately. This is a generated test assertion defect, not a title-function failure or a checker repair."}
            ]
            commitments = {"state": "FAIL" if any(row["state"] == "FAIL" for row in checks) else "OPEN",
                           "copiedPrompt": str(prompt_path.relative_to(ROOT)), "copiedPromptSha256": sha(prompt_bytes),
                           "checks": checks, "scope": "Only explicit promises in this native copied execution input; never added to another arm."}
        report["enhancedCommitments"] = commitments
        report["completeHardGate"] = "FAIL" if report["mechanicalGate"] == "FAIL" or commitments["state"] == "FAIL" else "OPEN"
        with path.with_name("result-before-promise-binding.json").open("xb") as output:
            output.write(before)
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        summary.append({"arm": arm, "syntax": report["syntax"]["status"],
                        "originalTaskMechanicalGate": report["originalTaskMechanicalGate"],
                        "corePassed": sum(row["state"] == "PASS" for row in report["originalTaskChecks"]),
                        "coreTotal": len(report["originalTaskChecks"]), "mechanicalGate": report["mechanicalGate"],
                        "authoredTests": [{"state": row["state"], "counts": row["observation"].get("authoredTests")} for row in report["authoredTests"]],
                        "syntheticEchoObservations": len(echoes), "explicitCommitments": commitments["state"],
                        "completeHardGate": report["completeHardGate"],
                        "initialReportSha256": sha(before), "reportSha256": sha(path.read_bytes())})
    with (WORK / "requests-checks/reports-lock.json").open("x", encoding="utf-8") as output:
        json.dump(summary, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(json.dumps(summary, ensure_ascii=True))


if __name__ == "__main__":
    main()
