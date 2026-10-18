"""Record the user's delegated three-call reallocation; formal caps stay unchanged. No model call."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import sys

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
LEDGER = ROOT / "tmp/me00a-20261012"


def digest(value):
    return hashlib.sha256(value).hexdigest()


def main():
    assert sys.argv[1:] == ["--apply"]
    path = LEDGER / "protocol.json"
    before = path.read_bytes()
    protocol = json.loads(before)
    old = protocol["activeBatch"]
    assert old["version"] == "candidate37-live" and old["status"] == "CLOSED" and old["unusedAllowance"] == 3
    assert protocol["maximumActualModelCalls"] == 425 and protocol["phaseLimits"] == {"baseline": 80, "candidateValidation": 345}
    files = sorted((LEDGER / "traces").glob("*.json"), key=lambda p: int(p.stem))
    rows = [json.loads(p.read_text(encoding="utf-8-sig")) for p in files]
    assert [int(p.stem) for p in files] == list(range(1, 413))
    assert sum(row.get("phase") == "baseline" for row in rows) == 70
    assert sum(row.get("phase") == "candidateValidation" for row in rows) == 342
    manifest = (PUBLIC / "candidate38-source.json").read_bytes()
    cases = (PUBLIC / "request-cases.json").read_bytes()
    scope = json.loads((PUBLIC / "scope-preflight.json").read_text(encoding="utf-8"))
    assert scope["checks"] == scope["passed"] == 19 and scope["modelCalls"] == scope["budgetReservations"] == 0
    inputs = json.loads((PUBLIC / "expected-inputs.json").read_text(encoding="utf-8"))
    assert len(inputs) == 1 and inputs[0]["planConfirmed"] is True
    basis = "User explicitly delegated subsequent candidate/reallocation choices and now authorized the reviewed remediation. Reallocate candidate37 closed unused3; no formal cap increase, no baseline borrowing."
    maximums = {"PLANNING": 1, "PLATFORM_GENERATION": 1, "EXECUTION": 1, "REVIEW": 0}
    batch = {"version": "candidate38-live", "sourceCandidate": "candidate38", "status": "OPEN",
             "authorizationStatus": "APPROVED_BY_USER", "authorizationAmendment": 31, "authorizationBasis": basis,
             "callsBefore": 412, "candidateCallsBefore": 342, "maximumNewCalls": 3, "maximumActualCallsAfterBatch": 415,
             "purposeMaximums": maximums, "qualityRetriesAllowed": False, "secondQualityRoundAllowed": False,
             "sourceManifestSha256": digest(manifest), "casesFile": "candidate38-request-cases.json", "casesSha256": digest(cases),
             "expectedEnhancementInputs": inputs, "model": "deepseek-flash", "executionModel": "deepseek-flash", "reviewModels": [],
             "accountCreationAllowed": False,
             "dependencyFailurePolicy": "Plan must naturally return zero questions; otherwise preserve it and skip enhancement/work. Each purpose has one inclusive attempt, no quality retry or invented answers.",
             "reviewScope": "No new reviews or paired baseline; diagnostic only, overall gates remain open."}
    amendment = {"number": 31, "candidate": "candidate38", "authorization": basis,
                 "formalBeforeAndAfter": {"total": 425, "baseline": 80, "candidate": 345},
                 "reallocatedClosedAllowance": {"from": "candidate37-live", "calls": 3}, "actualBefore": 412,
                 "maximumActualAfter": 415, "purposeMaximums": maximums, "protocolBeforeSha256": digest(before),
                 "sourceManifestSha256": digest(manifest), "casesSha256": digest(cases), "appliedAt": datetime.now(timezone.utc).isoformat()}
    with (LEDGER / "protocol-before-amendment-31.json").open("xb") as output:
        output.write(before)
    with (PUBLIC / "protocol-amendment-31.json").open("x", encoding="utf-8") as output:
        json.dump(amendment, output, ensure_ascii=False, indent=2); output.write("\n")
    with (LEDGER / batch["casesFile"]).open("xb") as output:
        output.write(cases)
    assert path.read_bytes() == before, "Concurrent budget change"
    protocol["activeBatch"] = batch
    staged = LEDGER / "protocol-candidate38-staged.json"
    with staged.open("x", encoding="utf-8") as output:
        json.dump(protocol, output, ensure_ascii=False, indent=2); output.write("\n")
    staged.replace(path)
    print(json.dumps({"activeBatch": batch["version"], "maximumNewCalls": 3, "formalCapChanged": False, "actualBefore": 412, "modelCalls": 0}))


if __name__ == "__main__":
    main()
