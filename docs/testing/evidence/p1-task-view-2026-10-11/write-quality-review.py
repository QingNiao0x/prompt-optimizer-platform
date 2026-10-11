"""Record bounded, evidence-based review; do not call a model or rewrite works."""
import collections
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def evidence(path, text):
    """Attach the exact occurrence and line to an unchanged captured artifact."""
    lines = (ROOT / path).read_text(encoding="utf-8-sig").splitlines()
    found = [(number, line) for number, line in enumerate(lines, 1) if text in line]
    if not found:
        raise ValueError(f"Review evidence absent: {path}")
    number, line = found[0]
    return {"artifact": path, "line": number, "excerpt": line}


def main():
    audit = json.loads((ROOT / "execution-audit-final.json").read_text(encoding="utf8"))
    rows = []
    for pair in audit["finalPairs"]:
        item = dict(pair)
        case = item["caseId"]
        item["reviewer"] = "AI_ASSISTED_EVIDENCE_REVIEW_NOT_INDEPENDENT_BLIND_REVIEW"
        item["unmeasured"] = ["humanEditingTime", "independentProfessionalRating", "productionCost"]
        if item["model"] == "deepseek-v4-pro":
            item.update(outcome="BOTH_FAIL", scopedImprovements=["bound Plan answers integrated without a full questionnaire"],
                        unresolved=["two conservative Arriaga execution clauses reappended", "unselected standard population asserted as adopted"],
                        attribution="Prompt retains an unknown-parameter boundary; both downstream works choose a population without confirmed evidence.")
            item["evidence"] = [evidence(item["work"], "本文采用2010年中国人口普查人口作为标准人口"),
                                evidence(item["oldWork"], "本报告采用2010年中国人口普查标准人口构成")]
        elif case == "QB-01":
            item.update(outcome="BOTH_FAIL", scopedImprovements=["report objective and four original analysis directions preserved"],
                        unresolved=["YLL remaining-life interpretation is incorrect or contradictory", "repeated missing-data explanations"],
                        attribution="Professional accuracy failure in both actual downstream works; prompt structure validity is insufficient.")
            item["evidence"] = [evidence(item["work"], "预期余寿减去实际存活年数"),
                                evidence(item["oldWork"], "死亡年龄与参考寿命表中该年龄对应的预期余命之差")]
        elif case == "QB-02":
            item.update(outcome="BOTH_FAIL", scopedImprovements=["confirmed facts integrated into execution text", "known choices no longer reasked as a full questionnaire", "independent unknowns preserved"],
                        unresolved=["YLL remaining-life interpretation is incorrect", "underlying life-table version and standard population remain unselected"],
                        attribution="Platform integration improves; actual downstream professional formula is still wrong in both versions.")
            item["evidence"] = [evidence(item["work"], "YLL = Σ(标准期望寿命 - 死亡年龄)"),
                                evidence(item["oldWork"], "死亡年龄与标准期望寿命表中对应年龄的剩余期望寿命之差")]
        elif case == "QB-03":
            item.update(outcome="BOTH_FAIL", scopedImprovements=["final report includes paired input and expected-output examples", "examples marked fictional and placed after report body"],
                        unresolved=["a lifetime upper limit is substituted for remaining life at age of death", "Arriaga comparison selected without confirming its scope", "missing-data statements repeated"],
                        attribution="Example contract improves, but professional errors and unselected method parameters remain in the downstream report; old work also fails professional/example gates.")
            item["evidence"] = [evidence(item["work"], "参考寿命表上限"), evidence(item["work"], "期望寿命为78.5岁"),
                                evidence(item["work"], "以2015年和2025年为两个时点"),
                                evidence(item["oldWork"], "YLL为各死亡者的死亡年龄与参考寿命之差的总和")]
        elif case == "QB-04":
            item.update(outcome="TIE", scope="core title extraction only, not real-website/CLI/timeout integration",
                        scopedImprovements=["first-title handling passes the independently checked edge case"],
                        unresolved=["final generated function defaults timeout to None", "real HTTP and CLI integration not assessed"],
                        attribution="Core function works in both; edge-case improvement and an unbounded-wait risk do not establish overall superiority.")
            item["evidence"] = [evidence(item["work"], "def fetch_title(url, timeout=None, headers=None)")]
        elif case == "QB-05":
            item.update(outcome="TIE", scope="actual quicksort function and original bound Plan requirements",
                        scopedImprovements=["Python and quicksort come from reviewed bound answers", "paired examples present", "no full questionnaire reappend"],
                        unresolved=["generic fictional-data warning is unnecessary for this code-learning task"],
                        attribution="Raw, old and final generated functions all pass 108 independent correctness/input-preservation cases each; no performance proof or general language inference claim.")
            item["evidence"] = [{"artifact": "code-work-checks.json", "checksPerArtifact": 108, "artifacts": 3, "failures": 0}]
        elif case == "QB-06":
            item.update(outcome="BOTH_FAIL", scopedImprovements=["deliverable remains a writing prompt", "paired examples inside the delivered prompt", "4x5=20, three independent repetitions, two blind reviewers and equal-information controls retained", "1200-Chinese-character outline limit and separately counted tables remain at the target-work layer"],
                        unresolved=["final work newly forbids naming statistical tests", "design constraints repeatedly restated", "old work lacks paired examples"],
                        attribution="Example and prompt-layer contracts improve; final downstream writing prompt adds a stronger prohibition than the user requested, so overall fidelity/quality is not accepted.")
            item["evidence"] = [evidence(item["work"], "不写具体检验名称"), evidence(item["work"], "输入与预期输出示例")]
        elif case == "QB-07":
            item.update(outcome="TIE", scopedImprovements=["explicit no-example/translation-only instruction wins over the switch"],
                        unresolved=[], attribution="Old and final actual works are the identical seven-character translation; correct behavior preserved.")
            item["evidence"] = [evidence(item["work"], "会议九点开始。")]
        elif case == "QB-08":
            item.update(outcome="LOSS", scopedImprovements=["2024/2025 institutions, > versus >= and reviewer roles retained", "no unrequested report/code-generator deliverable"],
                        unresolved=["final downstream writing prompt permits fictional verification data despite an explicit no-hypothetical-data requirement"],
                        attribution="Final optimized prompt preserves the prohibition and the example switch is off; the downstream work contradicts it. This is a real downstream regression, not proof that ExampleContract granted permission.")
            item["evidence"] = [evidence(item["work"], "如确需构造数据用于验证"), evidence(item["work"], "不得使用假设数据")]
        else:
            raise ValueError("Unreviewed case")
        rows.append(item)
    review = {
        "status": "OVERALL_QUALITY_NOT_ACCEPTED",
        "candidate": "candidate-v5-final",
        "rules": {
            "order": "Check hard requirements, unsupported facts and professional errors before relative benefit.",
            "bothFail": "Both versions have at least one evidenced quality/requirement gate failure, even if a scoped dimension improves.",
            "tie": "No established overall advantage in the explicitly assessed core scope; limitations are retained.",
            "loss": "Final introduces an evidenced violation absent in the old work.",
            "notStatistical": "Small heterogeneous sample and AI-assisted review; not independent blind review, calibrated ratings or a population win-rate estimate."
        },
        "counts": dict(collections.Counter(x["outcome"] for x in rows)),
        "pairs": rows,
        "formulaReferences": [
            {"url": "https://cdn.who.int/media/docs/default-source/foodborne-diseases/webinar-on-burden-of-foodborne-diseases-estimate-29jun2021-pires-presentation.pdf?sfvrsn=856ac33c_5", "page": 29,
             "note": "YLL equals deaths multiplied by remaining life expectancy at death, not remaining life minus death age."},
            {"url": "https://pmc.ncbi.nlm.nih.gov/articles/PMC5605707/", "note": "GBD 2016 primary analysis uses reference life-table remaining life expectancy at age of death."}
        ],
        "proRepeat": {"artifact": "final-pro-repeat/QB-02-direct-work.json", "role": "same frozen copied prompt repeated once, not a replacement for the failed main work", "conclusion": "parameter choices and organization vary; insufficient to establish stability"},
        "rawControls": {"role": "reference only, main outcome compares old platform-enhanced against final platform-enhanced", "exampleMatched": "raw-examples-matched/QB-03 and QB-06 include original toggle guidance; not byte-identical to the later placement clarification"},
        "monetaryCost": "Not calculated: billed/cache-specific prices not frozen. Actual provider token usage retained.",
        "noFurtherModelCalls": True
    }
    (ROOT / "quality-review.json").write_text(json.dumps(review, ensure_ascii=False, indent=2) + "\n", encoding="utf8")
    print(json.dumps({"status": review["status"], "counts": review["counts"], "pairs": len(rows)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
