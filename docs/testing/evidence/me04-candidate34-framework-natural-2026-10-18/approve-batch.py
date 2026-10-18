"""Read-only by default; --apply explicitly reallocates candidate33's closed eight calls after freeze."""
import argparse
import copy
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]
PUBLIC = Path(__file__).resolve().parent
LEDGER = ROOT / "tmp/me00a-20261012"
RAW = "使用requests库爬取网页标题。"
CAPS = {"PLATFORM_GENERATION": 3, "EXECUTION": 3, "REVIEW": 2}


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def new_file(path, value):
    with path.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def owned(path):
    resolved = (ROOT / path).resolve()
    require(resolved.is_relative_to(ROOT), "Frozen evidence escaped repository")
    return resolved


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Explicit activation; not used during preparation")
    args = parser.parse_args()
    protocol_path = LEDGER / "protocol.json"
    before_bytes = protocol_path.read_bytes()
    protocol = json.loads(before_bytes)
    previous = protocol["activeBatch"]
    traces = sorted((LEDGER / "traces").glob("*.json"), key=lambda path: int(path.stem))
    require([int(path.stem) for path in traces] == list(range(1, 382)), "Ledger must remain contiguous at 381")
    records = [read(path) for path in traces]
    require(sum(row.get("phase") == "baseline" for row in records) == 70, "Baseline count drift")
    require(sum(row.get("phase") == "candidateValidation" for row in records) == 311, "Candidate count drift")
    require(protocol["maximumActualModelCalls"] == 399
            and protocol["phaseLimits"] == {"baseline": 80, "candidateValidation": 319}, "Formal limit drift")
    require(previous["version"] == "candidate33-live" and previous["status"] == "CLOSED"
            and previous["callsBefore"] == 369 and previous["maximumNewCalls"] == 20
            and previous["actualNewCalls"] == 12, "Closed candidate33 remainder is not the expected eight")
    require(sum(row.get("version") == "candidate33-live" for row in records) == 12, "Closed batch trace count drift")
    catalogue_bytes = (PUBLIC / "live-cases.json").read_bytes()
    catalogue = json.loads(catalogue_bytes)
    cases_bytes = (PUBLIC / "request-cases.json").read_bytes()
    cases = json.loads(cases_bytes)
    require(catalogue["candidate"] == "candidate34" and len(catalogue["cases"]) == 1
            and catalogue["qualityRetriesAllowed"] is False and catalogue["accountCreationAllowed"] is False,
            "Unexpected catalogue")
    expected_cases = [{"id": "REQUESTS-" + mode, "rawPrompt": RAW, "promptFormat": mode,
                       "includeExamples": False, "mode": "direct"} for mode in ["COMPACT", "STRUCTURED"]]
    require(cases == expected_cases, "Original task, format or scope changed")
    sample = catalogue["cases"][0]
    require(sample["id"] == "REQUESTS" and sample["rawPrompt"] == RAW and sample["files"] == []
            and sample["planConfirmation"] is None and sample["includeExamples"] is False,
            "Unexpected material, confirmation or examples")
    proposal = read(PUBLIC / "live-proposal.json")
    require(proposal["maximumNewCalls"] == 8 and proposal["purposeMaximums"] == CAPS
            and proposal["reallocationProposed"] == {"candidate33ClosedUnused": 8}
            and proposal["additionalAllowanceRequested"] == 0 and proposal["casesSha256"] == sha(catalogue_bytes),
            "Proposal drift")
    manifest_bytes = (PUBLIC / "candidate34-source.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    require(manifest["version"] == "candidate34", "Wrong frozen candidate")
    for row in manifest["sourceFiles"]:
        require(sha(owned(row["path"]).read_bytes()) == row["sha256"], "Source differs from freeze")
    for row in manifest["runtimeFiles"]:
        require(sha(owned(str(Path(manifest["runtimeSnapshot"]) / row["path"])).read_bytes()) == row["sha256"],
                "Frozen runtime differs from manifest")
    inputs = read(PUBLIC / "expected-inputs.json")
    require(len(inputs) == 2, "Two frozen input pairs required")
    for sample, entry in zip(cases, inputs):
        require(entry["caseId"] == sample["id"] and entry["rawPrompt"] == RAW
                and entry["promptFormat"] == sample["promptFormat"] and entry["planConfirmed"] is False
                and entry.get("taskView") and entry.get("rewriteStrategy"), "Frozen input/view/format pairing mismatch")
    preflight = read(PUBLIC / "scope-preflight.json")
    require(preflight["checks"] > 0 and preflight["passed"] == preflight["checks"] and preflight["failed"] == 0
            and preflight["externalModelCalls"] == 0 and preflight["springStarted"] is False
            and preflight["budgetReservationAttempted"] is False, "Offline scope preflight not complete")
    tools_path = PUBLIC / "evaluation-tools.json"
    if tools_path.exists():
        for row in read(tools_path)["tools"]:
            path = (PUBLIC / row["path"]).resolve()
            require(path.is_relative_to(PUBLIC) and sha(path.read_bytes()) == row["sha256"], "Evaluation tool hash drift")
    else:
        require(not args.apply, "Freeze evaluation-tools.json before activation")
    preview = {"candidate": "candidate34", "ready": True, "applied": args.apply, "actualBefore": 381,
               "maximumActualAfter": 389, "candidateBefore": 311, "maximumCandidateAfter": 319,
               "maximumNewCalls": 8, "purposeMaximums": CAPS, "sourceManifestSha256": sha(manifest_bytes),
               "newModelCalls": 0}
    if not args.apply:
        print(json.dumps(preview, ensure_ascii=False))
        return
    amendment = {"amendment": 27, "status": "APPLIED_BY_EXPLICIT_ROOT_EXECUTION", "candidate": "candidate34",
                 "authorizationBasis": proposal["authorizationBasis"], "formalLimitsBefore": proposal["formalLimitsBefore"],
                 "formalLimitsAfter": proposal["formalLimitsProposed"], "maximumNewCalls": 8, "purposeMaximums": CAPS,
                 "reallocation": {"from": "candidate33-live", "closedUnused": 8, "allocated": 8},
                 "additionalCalls": 0, "callsBefore": 381, "candidateCallsBefore": 311,
                 "sourceManifestSha256": sha(manifest_bytes), "casesSha256": sha(cases_bytes),
                 "expectedEnhancementInputs": inputs, "qualityRetriesAllowed": False}
    updated = copy.deepcopy(protocol)
    updated["activeBatch"] = {"version": "candidate34-live", "sourceCandidate": "candidate34",
        "group": "me04-framework-natural-requests", "authorizationStatus": "APPROVED_BY_USER",
        "authorizationBasis": proposal["authorizationBasis"], "authorizationAmendment": "protocol-amendment-27.json",
        "status": "OPEN", "qualityRetriesAllowed": False, "secondQualityRoundAllowed": False,
        "callsBefore": 381, "candidateCallsBefore": 311, "maximumNewCalls": 8,
        "maximumActualCallsAfterBatch": 389, "purposeMaximums": CAPS,
        "sourceManifestSha256": sha(manifest_bytes), "model": "deepseek-flash", "executionModel": "deepseek-flash",
        "reviewModels": ["deepseek-flash", "deepseek-v4-pro"], "cases": [row["id"] for row in cases],
        "casesFile": "candidate34-framework-natural-cases.json", "casesSha256": sha(cases_bytes),
        "expectedEnhancementInputs": inputs, "accountCreationAllowed": False,
        "dependencyFailurePolicy": proposal["dependencyFailurePolicy"]}
    # Persist reviewable lineage first; canonical protocol is replaced last after a drift check.
    new_file(PUBLIC / "protocol-before-amendment-27.json", protocol)
    new_file(PUBLIC / "protocol-amendment-27.json", amendment)
    with (LEDGER / updated["activeBatch"]["casesFile"]).open("xb") as stream:
        stream.write(cases_bytes)
    proposal["status"] = "APPROVED_OPEN"
    proposal["sourceManifestSha256"] = sha(manifest_bytes)
    proposal["sourceRevision"] = manifest.get("sourceRevision", manifest.get("revision", "frozen"))
    (PUBLIC / "live-proposal.json").write_text(json.dumps(proposal, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    require(protocol_path.read_bytes() == before_bytes and len(list((LEDGER / "traces").glob("*.json"))) == 381,
            "Protocol/ledger changed before activation")
    staged = LEDGER / "protocol.candidate34.staged.json"
    new_file(staged, updated)
    staged.replace(protocol_path)
    print(json.dumps(preview, ensure_ascii=False))


if __name__ == "__main__":
    main()
