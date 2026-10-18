"""Apply the user's delegated twelve-call reallocation, within unchanged formal limits. No model requests."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import sys

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
LEDGER = ROOT / "tmp/me00a-20261012"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    """Preserve the closed source allowance and record a new explicit, source-bound batch."""
    assert sys.argv[1:] == ["--apply"], "Explicit activation flag required; no implicit mutations"
    path = LEDGER / "protocol.json"
    before = path.read_bytes()
    protocol = json.loads(before)
    old = protocol["activeBatch"]
    assert old["version"] == "candidate36-live" and old["status"] == "CLOSED" and old["unusedAllowance"] == 12
    assert protocol["maximumActualModelCalls"] == 425 and protocol["phaseLimits"] == {"baseline": 80, "candidateValidation": 345}
    files = sorted((LEDGER / "traces").glob("*.json"), key=lambda p: int(p.stem))
    records = [json.loads(p.read_text(encoding="utf-8-sig")) for p in files]
    assert [int(p.stem) for p in files] == list(range(1, 404))
    assert sum(row.get("phase") == "baseline" for row in records) == 70
    assert sum(row.get("phase") == "candidateValidation" for row in records) == 333
    manifest = (PUBLIC / "candidate37-source.json").read_bytes()
    cases = (PUBLIC / "request-cases.json").read_bytes()
    inputs = json.loads((PUBLIC / "expected-inputs.json").read_text("utf-8"))
    assert len(inputs) == 3
    batch = {"version": "candidate37-live", "sourceCandidate": "candidate37", "status": "OPEN",
             "authorizationStatus": "APPROVED_BY_USER", "authorizationAmendment": 30,
             "authorizationBasis": "Current user explicitly delegated subsequent candidate/reallocation choices for these five unresolved defects. Reallocate closed candidate36 twelve calls, no formal cap increase.",
             "callsBefore": 403, "candidateCallsBefore": 333, "maximumNewCalls": 12,
             "maximumActualCallsAfterBatch": 415,
             "purposeMaximums": {"PLATFORM_GENERATION": 6, "EXECUTION": 4, "REVIEW": 2},
             "qualityRetriesAllowed": False, "secondQualityRoundAllowed": False,
             "sourceManifestSha256": sha(manifest), "casesFile": "candidate37-request-cases.json",
             "casesSha256": sha(cases), "expectedEnhancementInputs": inputs,
             "model": "deepseek-flash", "executionModel": "deepseek-flash", "reviewModels": ["deepseek-flash", "deepseek-v4-pro"],
             "accountCreationAllowed": False,
             "dependencyFailurePolicy": "Three enhancement requests share six repair-inclusive calls; missing native copies skip dependent works, never reconstruct them.",
             "reviewScope": "Three REQUESTS works only; PATCH STRUCTURED is an independent diagnostic without a new paired baseline."}
    amendment = {"number": 30, "candidate": "candidate37", "authorization": batch["authorizationBasis"],
                 "formalBeforeAndAfter": {"total": 425, "baseline": 80, "candidate": 345},
                 "reallocatedClosedAllowance": {"from": "candidate36-live", "calls": 12},
                 "actualBefore": 403, "maximumActualAfter": 415,
                 "purposeMaximums": batch["purposeMaximums"], "protocolBeforeSha256": sha(before),
                 "sourceManifestSha256": sha(manifest), "casesSha256": sha(cases), "appliedAt": datetime.now(timezone.utc).isoformat()}
    private_backup = LEDGER / "protocol-before-amendment-30.json"
    with private_backup.open("xb") as output:
        output.write(before)
    with (PUBLIC / "protocol-amendment-30.json").open("x", encoding="utf-8") as output:
        json.dump(amendment, output, ensure_ascii=False, indent=2); output.write("\n")
    with (LEDGER / batch["casesFile"]).open("xb") as output:
        output.write(cases)
    protocol["activeBatch"] = batch
    assert path.read_bytes() == before, "Concurrent ledger change"
    staged = LEDGER / "protocol-candidate37-staged.json"
    with staged.open("x", encoding="utf-8") as output:
        json.dump(protocol, output, ensure_ascii=False, indent=2); output.write("\n")
    staged.replace(path)
    print(json.dumps({"activeBatch": "candidate37-live", "maximumNewCalls": 12, "formalCapChanged": False, "modelCalls": 0}))


if __name__ == "__main__":
    main()
