"""Publish only synthetic offline observations; account files, plans in Redis and model budgets are read-only."""
from pathlib import Path
import hashlib
import json

PUBLIC = Path(__file__).resolve().parent
ROOT = PUBLIC.parents[3]
PRIVATE = ROOT / "tmp/me04-candidate39-20261018"


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(name, data):
    with (PUBLIC / name).open("x", encoding="utf-8", newline="\n") as output:
        json.dump(data, output, ensure_ascii=False, indent=2)
        output.write("\n")


def main():
    """Keep parent/current observations, old failures and the lack of live acceptance explicit."""
    parent = load(PRIVATE / "replay-parent/plan-replay.json")
    current = load(PRIVATE / "replay-current/plan-replay.json")
    comparisons = []
    for old, new in zip(parent["observations"], current["observations"], strict=True):
        assert old["preparedEmptyContext"] == new["preparedEmptyContext"]
        assert [row["id"] for row in old["questions"]] == ["target_pages", "title_definition", "delivery_form"]
        assert new["questions"] == old["questions"][:2]
        assert old["providerFixtureInvocations"] == new["providerFixtureInvocations"] == 1
        assert old["contextReferenceMatches"] and new["contextReferenceMatches"]
        assert old["planRegistered"] and new["planRegistered"]
        comparisons.append({"preparedEmptyContext": new["preparedEmptyContext"], "parentQuestionCount": 3,
            "candidateQuestionCount": 2, "remainingQuestionsByteEquivalentAfterJsonSerialization": True,
            "removedQuestion": "delivery_form", "summary": new["summary"],
            "actualUserConfirmationAdded": False, "newRealCalls": 0})
    save("plan-replay-parent.json", parent)
    save("plan-replay-current.json", current)
    save("replay-comparison.json", {"parent": "candidate38r1", "candidate": "candidate39", "sourceTrace": 413,
        "fixtureSha256": sha(ROOT / "services/api/src/test/resources/fixtures/candidate39-planning-packaging-replay.json"),
        "observations": comparisons, "actualBrowserOrHistory": "NOT_REVALIDATED", "newRealModelCalls": 0,
        "qualityGate": "OPEN_OFFLINE_ONLY"})
    save("scope-and-attribution.json", {
        "candidate": "candidate39", "rootCause": "PlanQuestionFilter has no pure code-packaging responsibility rule; ordinary script/function/CLI packaging remains a user prerequisite",
        "evidence": "Retained413 response; parent red21/4 and frozen parent/current service replays",
        "adoptedFix": {"class": "RoutineCodePackaging", "callSite": "PlanQuestionFilter.filter",
            "scope": "Independent software implementation, no material/history/bound sources, complete recognized packaging options only",
            "doesNotChooseAnOption": True, "doesNotModifyModelGuidanceOrRetryCounts": True,
            "fallback": "Keep whole question when scope, wording, object, condition, language or evidence is unrecognized"},
        "duplication": {"newDeletionRuleAdopted": False,
            "reason": "Remaining414 composite/synonym and safety differences cannot prove complete equal scope; model examples conflict is already rejected by38r1",
            "status": "OPEN_PREVIOUS_TWO_DETERMINISTIC_FIXES_PRESERVED"},
        "newBoundaryFound": {"rawPrompt": "用TypeScript实现文本去重。", "currentProfile": "GENERAL",
            "parentDefectOrLimitation": True, "modifiedClassifier": False,
            "handling": "Keep the packaging question; do not infer a software identity only to make filtering pass",
            "positiveExplicitArtifactVariant": "用TypeScript实现文本去重函数。",
            "broaderTaskIntentWork": "OPEN_SEPARATE_SOURCE_FAITHFUL_ACTION_OBJECT_EVALUATION_REQUIRED"},
        "toolCorrections": [
            {"issue": "Test incorrectly required a non-null context reference on a legacy request without prepared context",
             "correction": "Exercise both legacy-null and prepared-empty-context reference contracts", "productionChangeForThisIssue": False},
            {"issue": "Standalone harness could not call package-private test-clock constructor",
             "correction": "Compile the harness in the same package", "publicProductionApiWidened": False}
        ],
        "roi": {"observedOfflineBenefit": "One fewer unnecessary packaging question in413; independent pages/title questions unchanged",
            "realInteractionBenefit": "UNVERIFIED", "frequencyAndSupportCost": "UNKNOWN",
            "maintenanceRisk": "Finite grammar may retain paraphrases; prefer conservative fallback to silent new-condition loss",
            "newModelTokensOrCalls": 0, "productionCpuMeasurement": "NOT_PERFORMED"},
        "preservedFailures": {"trace415CredentialEchoes": 12, "trace415UnsupportedExecutionClaims": 9,
            "oldAuthorTestFailures": "RETAINED", "rubricAndYllProfessionalGates": "OPEN",
            "aiReviewDisagreementAndInaccurateQuotes": "RETAINED_NO_NEW_REVIEWS"},
        "downstreamStrategyDecision": {"additionalGlobalBans": "NOT_ADOPTED",
            "reason": "Credential and execution-truthfulness failures recur after existing guidance; same-hypothesis sampling stop condition applies",
            "automaticGeneratedCodeRewrite": "OUTSIDE_PROMPT_ONLY_PRODUCT_AND_NOT_EVIDENCE_OF_ENHANCEMENT_SUCCESS",
            "strongerModelOrSimplificationTrial": "PENDING_BOUND_SCOPE_AND_NEW_NUMERIC_BUDGET; NOT_ASSUMED_BETTER",
            "status": "FAILURES_RETAINED_NO_QUALITY_GATE_CLOSED"}
    })
    save("tool-hashes.json", {"scope": "Offline tooling after correction; no new external model request",
        "files": [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path)}
                  for path in sorted(PUBLIC.iterdir()) if path.suffix in {".java", ".py"}]})
    print(json.dumps({"replayComparisons": len(comparisons), "oldQuestionCount": 3, "newQuestionCount": 2,
        "unchangedRemainingQuestions": True, "newModelCalls": 0}))


if __name__ == "__main__":
    main()
