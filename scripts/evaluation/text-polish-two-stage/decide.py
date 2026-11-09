"""逐项人工评阅与成本门禁共同决定是否扩大实验。"""
import argparse
import json
from pathlib import Path

root = Path(__file__).resolve().parents[3] / "docs/testing/evidence/text-polish-two-stage-2026-11-09"
parser = argparse.ArgumentParser()
parser.add_argument("mode", choices=["template", "decide"])
mode = parser.parse_args().mode
cards = json.loads((root / "semantic-cards.json").read_text(encoding="utf-8"))["cards"]
if mode == "template":
    rows = [{"sampleId": card["sampleId"], "units": [dict(unit, baselineStatus=None, candidateStatus=None, outputEvidence="") for unit in card["units"]],
             "expressionGain": None, "styleDelivered": None, "newFacts": None, "sentenceEvidence": ""}
            for card in cards if card["sampleId"] in ["digital", "legal", "clean"]]
    with (root / "manual-review.json").open("x", encoding="utf-8") as stream:
        json.dump({"author": "待评阅", "rows": rows}, stream, ensure_ascii=False, indent=2)
    print("REVIEW_TEMPLATE_CREATED")
else:
    review = json.loads((root / "manual-review.json").read_text(encoding="utf-8"))
    result = {"pass": True, "rows": []}
    gold = {card["sampleId"]: {unit["id"] for unit in card["units"]} for card in cards}
    assert {row["sampleId"] for row in review["rows"]} == {"digital", "legal", "clean"}
    for row in review["rows"]:
        sid = row["sampleId"]
        assert {unit["id"] for unit in row["units"]} == gold[sid]
        stem = sid + "-CONVERSATIONAL-"
        baseline = json.loads((root / (stem + "v3-write.json")).read_text(encoding="utf-8"))
        plan = json.loads((root / (stem + "two-stage-plan.json")).read_text(encoding="utf-8"))
        candidate = json.loads((root / (stem + "two-stage-write.json")).read_text(encoding="utf-8"))
        cost = lambda value: value["response"]["usage"]["total_tokens"]
        time_ratio = (plan["elapsedMs"] + candidate["elapsedMs"]) / max(1, baseline["elapsedMs"])
        token_ratio = (cost(plan) + cost(candidate)) / max(1, cost(baseline))
        semantic_pass = all(unit["candidateStatus"] == "PRESERVED" and unit["outputEvidence"] for unit in row["units"])
        checked = all(unit["baselineStatus"] in ["PRESERVED", "OMITTED", "CHANGED"] for unit in row["units"])
        expression_pass = sid == "clean" or (row["expressionGain"] is True and row["styleDelivered"] is True and bool(row["sentenceEvidence"]))
        passed = semantic_pass and checked and expression_pass and row["newFacts"] is False and time_ratio <= 3 and token_ratio <= 3
        result["rows"].append({"sampleId": sid, "pass": bool(passed), "timeRatio": time_ratio, "tokenRatio": token_ratio})
        result["pass"] = result["pass"] and bool(passed)
    (root / "pilot-review.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PILOT_PASS" if result["pass"] else "PILOT_STOP")
