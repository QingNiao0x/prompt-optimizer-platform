"""Bind observed failures to explicit native-copy promises without adding them to the raw task.

Only existing independent observations and actual supplied test files are used. No work is edited,
no request is made, and absence of historical interface/test requirements is never filled in.
"""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[4]
WORK = ROOT / "tmp/me04-candidate34-live-20261018"
SAFETY = "不得在代码、日志或响应中泄露密码、Token、API Key 或私钥。"
TESTS = "为核心逻辑补充正常、异常和边界场景测试。"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    summary = []
    for arm in ["RAW", "COMPACT", "STRUCTURED"]:
        path = WORK / "requests-checks" / arm / "result.json"
        report = json.loads(path.read_text(encoding="utf-8"))
        source = WORK / "works/REQUESTS" / arm / "work.md"
        assert report["workSha256"] == sha(source.read_bytes())
        assert report["sourceRepaired"] is False and report["externalModelCalls"] == 0
        echoes = [{"entry": row["entry"], "mode": row["mode"],
                   "canarySentToTransport": row["observation"]["canarySentToTransport"]}
                  for row in report["diagnostics"] if row.get("syntheticCredentialDiagnostic") == "ECHO_OBSERVED"]
        assert all(row["canarySentToTransport"] for row in echoes)
        if arm == "RAW":
            commitments = {"state": "NOT_APPLICABLE_TO_ORIGINAL_TASK", "checks": [],
                           "reason": "The exact original task has no supplied test-file, interface or explicit credential-redaction requirement; echo observations remain supplementary."}
        else:
            prompt_path = WORK / "live-results/REQUESTS" / arm / "native-copy.md"
            prompt_bytes = prompt_path.read_bytes()
            prompt = prompt_bytes.decode("utf-8")
            assert SAFETY in prompt and TESTS in prompt, "Exact commitment quotation missing"
            authored = report["authoredTests"]
            tests_passed = bool(authored) and all(row["state"] == "PASS" for row in authored)
            checks = [
                {"name": "explicit_credential_non_echo_commitment", "state": "FAIL" if echoes else "OPEN",
                 "promptEvidence": SAFETY, "evidence": echoes,
                 "reason": "Synthetic query-token/userinfo values occur in returned dictionaries, CLI output or exception messages; each listed canary URL actually reached mocked requests transport. No arbitrary-secret guarantee is inferred."},
                {"name": "supplied_executable_core_tests", "state": "PASS" if tests_passed else "FAIL",
                 "promptEvidence": TESTS, "authoredTestFiles": len(authored),
                 "reason": "Actual supplied unittest tests executed unchanged." if tests_passed else "Only suggested test scenarios were supplied; no authored executable tests were present. The checker did not create missing tests."}
            ]
            commitments = {"state": "FAIL" if any(row["state"] == "FAIL" for row in checks) else "OPEN",
                           "copiedPrompt": str(prompt_path.relative_to(ROOT)), "copiedPromptSha256": sha(prompt_bytes),
                           "checks": checks, "scope": "Commitments in the actual copied execution prompt, separate from original-task title behavior."}
        report["enhancedCommitments"] = commitments
        report["completeHardGate"] = "FAIL" if report["mechanicalGate"] == "FAIL" or commitments["state"] == "FAIL" else "OPEN"
        report["uncovered"].append("Full semantic compliance beyond the explicitly checked promises remains OPEN; the mechanical title pass is not overall quality acceptance")
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        summary.append({"arm": arm, "syntax": report["syntax"]["status"],
                        "originalTaskMechanicalGate": report["originalTaskMechanicalGate"],
                        "corePassed": sum(row["state"] == "PASS" for row in report["originalTaskChecks"]),
                        "coreTotal": len(report["originalTaskChecks"]), "mechanicalGate": report["mechanicalGate"],
                        "authoredTests": [{"state": row["state"], "counts": row["observation"].get("authoredTests")} for row in report["authoredTests"]],
                        "syntheticEchoObservations": len(echoes), "explicitCommitments": commitments["state"],
                        "reportSha256": sha(path.read_bytes())})
    (WORK / "requests-checks/reports-lock.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=True))


if __name__ == "__main__":
    main()
