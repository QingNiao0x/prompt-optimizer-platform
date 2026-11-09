"""冻结两阶段实验及语义清单，检查预算和来源，不调用模型。"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = ROOT / "docs/testing/evidence/text-polish-two-stage-2026-11-09"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare():
    samples = json.loads((EVIDENCE / "samples.json").read_text(encoding="utf-8"))
    cards = json.loads((EVIDENCE / "semantic-cards.json").read_text(encoding="utf-8"))["cards"]
    sample_map = {sample["id"]: sample for sample in samples}
    for card in cards:
        text = sample_map[card["sampleId"]]["text"]
        assert hashlib.sha256(text.encode()).hexdigest() == card["textSha256"]
        assert len({unit["id"] for unit in card["units"]}) == len(card["units"])
        assert all(unit["sourceQuote"] in text for unit in card["units"])
    legal = next(card for card in cards if card["sampleId"] == "legal")
    assert any(unit["kind"] == "目的" and "权益" in unit["requiredMeaning"] for unit in legal["units"])
    assert any(unit["kind"] == "目标" and "体验" in unit["requiredMeaning"] for unit in legal["units"])
    previous = json.loads((ROOT / "docs/testing/evidence/text-polish-comparison-2026-11-09/ledger.json").read_text(encoding="utf-8"))
    assert previous["closed"] and previous["actualCalls"] == 45
    jobs = []
    for phase, ids, styles in [("pilot", ["digital", "legal", "clean"], ["CONVERSATIONAL"]),
                               ("expand", ["business", "essay"], ["FORMAL", "NATURAL", "CONVERSATIONAL"])]:
        for sid in ids:
            for style in styles:
                for baseline in [True, False]:
                    sample = sample_map[sid]
                    jobs.append({"id": f"{sid}-{style}-{'v3' if baseline else 'two-stage'}",
                                 "phase": phase, "baseline": baseline,
                                 "input": {"sourceText": sample["text"], "style": style, "depth": "RESTRUCTURE",
                                           "audience": "", "requirements": "", "lockedTexts": sample["locked"],
                                           "modelId": "deepseek:deepseek-flash"}})
    files = [ROOT / "scripts/evaluation/text-polish-two-stage/TwoStageEvaluation.java", Path(__file__),
             EVIDENCE / "samples.json", EVIDENCE / "semantic-cards.json",
             ROOT / "scripts/evaluation/text-polish-two-stage/run.ps1",
             ROOT / "scripts/evaluation/text-polish-two-stage/verify.py",
             ROOT / "scripts/evaluation/text-polish-two-stage/decide.py",
             ROOT / "services/api/src/main/java/com/promptoptimizer/textpolish/service/TextPolishInstructions.java"]
    protocol = {"version": "two-stage-pilot-1", "modelId": "deepseek:deepseek-flash", "routeKey": "deepseek",
                "upstreamModel": "deepseek-flash", "temperature": 0.2, "maxTokens": 8000,
                "jsonResponseFormat": True, "qualityRetries": 0, "protocolRetries": 0,
                "minimumCalls": {"pilot": 9, "expand": 18}, "requestedTotalNewCalls": 30,
                "reviewMethod": "开发者逐项人工审阅，无独立盲评。人工语义清单不发给模型。",
                "pilotGate": ["每篇逐项检查全部人工语义条目，无独立含义遗漏或新增事实。",
                              "数字化和服务说明均有具体句子或组织收益，对话文体兑现。",
                              "自然短文可原样或少改，计为持平。",
                              "两阶段端到端时间不超过同题v3的3倍，输入输出总Token不超过3倍，任一超限不扩大。",
                              "任一失败停止此方向，保留原件，保持当前默认。"],
                "jobs": jobs, "sha256": {str(file.relative_to(ROOT)): digest(file) for file in files}}
    # 冻结后禁止prepare覆盖，新增授权也不能改变输入和判据。
    with (EVIDENCE / "protocol.json").open("x", encoding="utf-8") as stream:
        json.dump(protocol, stream, ensure_ascii=False, indent=2)
    with (EVIDENCE / "authorization.json").open("x", encoding="utf-8") as stream:
        json.dump({"authorizedTaskLimit": 50, "priorActualCalls": 45,
                   "remainingPreviouslyAuthorized": 5, "requestedTaskLimit": 75,
                   "status": "AWAITING_ADDITIONAL_BUDGET"}, stream, ensure_ascii=False, indent=2)
    print(f"OFFLINE_CARDS_OK {len(cards)} articles {sum(len(card['units']) for card in cards)} units")
    print("PILOT_BUDGET_BLOCKED 9 required, 5 remaining")


if __name__ == "__main__":
    prepare()
