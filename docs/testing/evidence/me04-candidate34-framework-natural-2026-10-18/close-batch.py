"""Preview candidate34 closure; only --apply with an exact ledger count may close it.

No model requests, trace edits or allowance transfers occur here. Failed calls remain
counted; closing the accounting round does not pass any product quality gate.
"""
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
import argparse
import copy
import hashlib
import json
import os


PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
LEDGER = ROOT / "tmp/me00a-20261012"
PRIVATE = ROOT / "tmp/me04-candidate34-live-20261018"
VERSION = "candidate34-live"
CAPS = {"PLATFORM_GENERATION": 3, "EXECUTION": 3, "REVIEW": 2}


def require(condition, message):
    """Abort with stable metadata only; never echo request or failure bodies."""
    if not condition:
        raise SystemExit(message)


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def write_new(path, value):
    """Keep earlier closure artifacts intact, including interrupted attempts."""
    with path.open("xb") as stream:
        stream.write(encoded(value))


def load_traces():
    """Check numerical continuity and retain byte hashes to detect late completion."""
    files = list((LEDGER / "traces").glob("*.json"))
    require(all(path.stem.isdecimal() for path in files), "Non-numeric trace name")
    files.sort(key=lambda path: int(path.stem))
    require([int(path.stem) for path in files] == list(range(1, len(files) + 1)),
            "Ledger is not contiguous")
    records = []
    fingerprints = []
    for path in files:
        data = path.read_bytes()
        records.append((int(path.stem), json.loads(data.decode("utf-8-sig"))))
        fingerprints.append((path.name, sha(data)))
    return records, fingerprints


def real_chain(records, manifest_sha):
    """Export allowlisted metadata; safety observations concern generated code only."""
    pages = []
    locks = []
    for mode in ["COMPACT", "STRUCTURED"]:
        path = PRIVATE / "live-results/REQUESTS" / mode / "check.json"
        data = path.read_bytes()
        row = json.loads(data.decode("utf-8-sig"))
        require(row["candidate"] == "candidate34" and row["caseId"] == "REQUESTS"
                and row["format"] == mode and row["sourceManifestSha256"] == manifest_sha,
                "Browser check source/format drift")
        require(row["copySha256"] == sha(path.with_name("native-copy.md").read_bytes()),
                "Native copy differs from browser check")
        permitted = ["candidate", "caseId", "format", "success", "actualBrowserGeneration",
                     "generationAction", "nativeCopyMatches", "currentStoreMatchesResponse",
                     "copySha256", "sectionsSha256", "originalInputSha256", "sourceManifestSha256",
                     "casesSha256", "requestSha256", "responseSha256", "accountReused",
                     "comparisonOnlyNormalizesCrLf", "finishedAt"]
        page = {key: row[key] for key in permitted}
        page["history"] = {key: row["history"][key] for key in [
            "bodyMatches", "formatMatches", "sectionsMatch", "ambiguitiesMatch",
            "constraintsMatch", "newRecordFound", "totalDelta"]}
        page["checkSha256"] = sha(data)
        pages.append(page)
        locks.append((path, sha(data)))

    report_lock = PRIVATE / "requests-checks/reports-lock.json"
    report_lock_bytes = report_lock.read_bytes()
    report_rows = json.loads(report_lock_bytes.decode("utf-8-sig"))
    require([row["arm"] for row in report_rows] == ["RAW", "COMPACT", "STRUCTURED"],
            "Independent checker arm binding drift")
    code = []
    for row in report_rows:
        path = PRIVATE / "requests-checks" / row["arm"] / "result.json"
        data = path.read_bytes()
        report = json.loads(data.decode("utf-8-sig"))
        require(sha(data) == row["reportSha256"] and report["sourceRepaired"] is False
                and report["externalModelCalls"] == 0 and report["actualNetworkRequests"] == 0,
                "Independent report hash or execution safety drift")
        work = PRIVATE / "works/REQUESTS" / row["arm"] / "work.md"
        require(sha(work.read_bytes()) == report["workSha256"], "Checked work hash drift")
        core = report["originalTaskChecks"]
        require(len(core) == row["coreTotal"]
                and sum(check["state"] == "PASS" for check in core) == row["corePassed"],
                "Core title-behavior counts differ from lock")
        permitted = ["arm", "syntax", "originalTaskMechanicalGate", "corePassed", "coreTotal",
                     "mechanicalGate", "authoredTests", "syntheticEchoObservations",
                     "explicitCommitments", "reportSha256"]
        checked = {key: row[key] for key in permitted}
        checked.update(workSha256=report["workSha256"], sourceRepaired=False,
                       externalModelCalls=0, actualNetworkRequests=0,
                       completeHardGate=report["completeHardGate"])
        checked["commitmentChecks"] = [{"name": check["name"], "state": check["state"]}
                                       for check in report.get("enhancedCommitments", {}).get("checks", [])]
        checked["scope"] = ("Supplementary synthetic credential observations; no added privacy promise in raw task"
                            if row["arm"] == "RAW" else
                            "Copied prompt's explicit non-echo and executable-test promises checked separately from core title behavior")
        code.append(checked)
        locks.extend([(path, sha(data)), (work, sha(work.read_bytes()))])
    locks.append((report_lock, sha(report_lock_bytes)))
    traces = []
    for number, row in records:
        request = row.get("request", {})
        response = row.get("response") or {}
        usage = response.get("usage") or {}
        traces.append({
            "trace": number, "purpose": row["purpose"], "requestId": row.get("requestId"),
            "operation": row.get("operation"), "requestModel": request.get("model"),
            "requestSha256": sha(encoded(request)),
            "responseModel": response.get("model"), "httpStatus": row.get("httpStatus"),
            "failureRecorded": bool(row.get("failure")), "startedAt": row.get("startedAt"),
            "elapsedMs": row.get("elapsedMs"),
            "usage": {key: usage.get(key) for key in ["prompt_tokens", "completion_tokens", "total_tokens"]},
            "traceSha256": sha((LEDGER / "traces" / f"{number}.json").read_bytes())})
    return {
        "candidate": "candidate34", "sourceManifestSha256": manifest_sha,
        "pages": pages, "calls": traces,
        "independentCodeChecks": {"reportsLockSha256": sha(report_lock_bytes), "arms": code,
                                  "realWebsiteBehaviorTested": False,
                                  "syntheticCredentialScope": "Offline generated-code return, output and exception behavior; not platform API-key leakage",
                                  "echoCountMeaning": "Number of exercised cases with synthetic echo; not number of unique vulnerabilities",
                                  "semanticOrQualityGateClosed": False},
        "reviewConclusion": "Independent AI review conclusions audited separately; no verdict inferred from HTTP status",
        "qualityPassed": False
    }, locks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Close only after root review")
    parser.add_argument("--expected-actual", type=int, help="Required with --apply; reject ledger drift")
    args = parser.parse_args()
    require(not args.apply or args.expected_actual is not None,
            "--apply requires --expected-actual")

    protocol_path = LEDGER / "protocol.json"
    before = protocol_path.read_bytes()
    protocol = json.loads(before.decode("utf-8-sig"))
    batch = protocol["activeBatch"]
    require(batch["version"] == VERSION and batch["status"] == "OPEN", "Candidate34 is not open")
    require(protocol["maximumActualModelCalls"] == 399
            and protocol["phaseLimits"] == {"baseline": 80, "candidateValidation": 319}, "Formal limit drift")
    require(batch["callsBefore"] == 381 and batch["candidateCallsBefore"] == 311
            and batch["maximumNewCalls"] == 8 and batch["maximumActualCallsAfterBatch"] == 389
            and batch["purposeMaximums"] == CAPS, "Approved batch limits drift")
    require(batch["authorizationAmendment"] == "protocol-amendment-27.json"
            and batch["qualityRetriesAllowed"] is False and batch["accountCreationAllowed"] is False,
            "Approved batch protection drift")
    amendment_bytes = (PUBLIC / "protocol-amendment-27.json").read_bytes()
    amendment = json.loads(amendment_bytes.decode("utf-8-sig"))
    require(amendment["amendment"] == 27
            and amendment["status"] == "APPLIED_BY_EXPLICIT_ROOT_EXECUTION"
            and amendment["candidate"] == "candidate34"
            and amendment["callsBefore"] == 381 and amendment["candidateCallsBefore"] == 311
            and amendment["maximumNewCalls"] == 8 and amendment["purposeMaximums"] == CAPS
            and amendment["formalLimitsAfter"] == {"total": 399, "baseline": 80, "candidate": 319}
            and amendment["reallocation"] == {"from": "candidate33-live", "closedUnused": 8, "allocated": 8}
            and amendment["additionalCalls"] == 0, "Amendment27 lineage drift")
    manifest_sha = sha((PUBLIC / "candidate34-source.json").read_bytes())
    cases_sha = sha((PUBLIC / "request-cases.json").read_bytes())
    require(batch["sourceManifestSha256"] == amendment["sourceManifestSha256"] == manifest_sha
            and batch["casesSha256"] == amendment["casesSha256"] == cases_sha,
            "Frozen source/case binding drift")
    require(sha((LEDGER / batch["casesFile"]).read_bytes()) == cases_sha, "Private case binding drift")
    previous = read(PUBLIC / "protocol-before-amendment-27.json")["activeBatch"]
    require(previous["version"] == "candidate33-live" and previous["status"] == "CLOSED"
            and previous["actualNewCalls"] == 12 and previous["unusedClosedNotTransferable"] == 8,
            "Closed candidate33 remainder lineage drift")

    records, fingerprints = load_traces()
    actual = len(records)
    require(381 <= actual <= 389, "Batch count outside approved scope")
    require(args.expected_actual is None or actual == args.expected_actual, "Actual count differs from explicit expected count")
    phases = Counter(row.get("phase") for _, row in records)
    require(phases == {"baseline": 70, "candidateValidation": actual - 70}, "Phase accounting drift")
    recent = [(number, row) for number, row in records if number > 381]
    require(all(row.get("version") == VERSION and row.get("phase") == "candidateValidation"
                for _, row in recent), "New trace belongs to another batch")
    counts = Counter(row.get("purpose") for _, row in recent)
    require(all(purpose in CAPS and count <= CAPS[purpose] for purpose, count in counts.items()),
            "Purpose allowance exceeded")
    pending = [number for number, row in recent
               if not (isinstance(row.get("httpStatus"), int) or row.get("failure"))]
    failures = [number for number, row in recent
                if row.get("failure") or isinstance(row.get("httpStatus"), int)
                and not 200 <= row["httpStatus"] < 300]
    generation_ids = [row.get("requestId") for _, row in recent if row.get("purpose") == "PLATFORM_GENERATION"]
    require(all(generation_ids), "Generation trace lacks request correlation")
    repair_calls = sum(count - 1 for count in Counter(generation_ids).values())
    used = len(recent)
    scope_complete = counts["PLATFORM_GENERATION"] >= 2 and counts["EXECUTION"] == 3 and counts["REVIEW"] == 2
    summary = {
        "candidate": "candidate34", "version": VERSION,
        "status": "CLOSED" if args.apply else "READ_ONLY_PREVIEW", "readyToClose": not pending and scope_complete,
        "plannedCallSlotsObserved": scope_complete,
        "actual": actual, "baseline": 70, "candidateCalls": actual - 70,
        "formalLimits": {"total": 399, "baseline": 80, "candidate": 319},
        "batchCalls": used, "maximumNewCalls": 8,
        "purposeCounts": {purpose: counts[purpose] for purpose in CAPS}, "purposeMaximums": CAPS,
        "httpSuccessfulCalls": used - len(pending) - len(failures), "failedCallsCounted": len(failures),
        "failedTraceNumbers": failures, "pendingTraceNumbers": pending,
        "traceNumbers": [number for number, _ in recent],
        "unusedClosedNotTransferable" if args.apply else "unusedIfClosedNow": 8 - used,
        "qualityRetriesPerformed": 0, "providerProtocolRepairCalls": repair_calls,
        "qualityGate": "OPEN_WITH_KNOWN_FAILURES", "qualityPassed": False,
        "qualityNote": "Accounting closure only; HTTP success does not prove semantic or safety acceptance",
        "sourceManifestSha256": manifest_sha, "casesSha256": cases_sha,
        "authorizationAmendment": "protocol-amendment-27.json", "amendmentSha256": sha(amendment_bytes),
        "accountCreationAllowed": False, "newModelCalls": 0,
        "allowanceTransfer": "NONE; any later reallocation requires a separately recorded authorized amendment"
    }
    if not args.apply:
        print(json.dumps(summary, ensure_ascii=False))
        return

    require(not pending, "Unresolved trace prevents closure")
    require(scope_complete, "Approved two-mode works/review sequence is not yet recorded")
    chain, evidence_locks = real_chain(recent, manifest_sha)
    summary["realChainSha256"] = sha(encoded(chain))
    closed_at = datetime.now(timezone.utc).isoformat()
    summary["closedAt"] = closed_at
    updated = copy.deepcopy(protocol)
    updated["activeBatch"].update(
        status="CLOSED", closedAt=closed_at, actualNewCalls=used,
        actualCallsAfterBatch=actual, actualCandidateCallsAfterBatch=actual - 70,
        actualPurposeCounts=summary["purposeCounts"], unusedAllowance=8 - used,
        unusedAllowanceStatus="CLOSED_NOT_TRANSFERABLE", unusedClosedNotTransferable=8 - used,
        failedCallsCounted=len(failures), qualityRetriesPerformed=0,
        providerProtocolRepairCalls=repair_calls, qualityGate=summary["qualityGate"])
    # Public closure contains safe accounting metadata, not request bodies or credentials.
    public_batch = {key: value for key, value in updated["activeBatch"].items()
                    if key not in {"expectedEnhancementInputs", "authorizationBasis"}}
    public_protocol = {"publicAccountingSnapshot": True, "maximumActualModelCalls": 399,
                       "phaseLimits": protocol["phaseLimits"], "activeBatch": public_batch}
    destinations = [PUBLIC / "batch-close-summary.json", PUBLIC / "protocol-closed.json",
                    LEDGER / "protocol-before-candidate34-close.json", LEDGER / "protocol-candidate34-closed.new.json",
                    PUBLIC / "real-chain.json"]
    require(not any(path.exists() for path in destinations), "A closure artifact already exists; inspect it instead of overwriting")
    require(protocol_path.read_bytes() == before and load_traces()[1] == fingerprints,
            "Protocol or trace changed before closure")
    require(all(sha(path.read_bytes()) == fingerprint for path, fingerprint in evidence_locks),
            "Browser or checker evidence changed before closure")
    write_new(destinations[0], summary)
    write_new(destinations[1], public_protocol)
    write_new(destinations[2], protocol)
    write_new(destinations[3], updated)
    write_new(destinations[4], chain)
    require(protocol_path.read_bytes() == before and load_traces()[1] == fingerprints,
            "Protocol or trace changed while staging closure")
    require(all(sha(path.read_bytes()) == fingerprint for path, fingerprint in evidence_locks),
            "Browser or checker evidence changed while staging closure")
    os.replace(destinations[3], protocol_path)
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
