"""冻结 Q05 第六候选的真实三臂证据和之后的本地修复，保持两种版本分开。"""
import hashlib
import json
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "tmp/me00a-20261012"
ARCHIVE = ROOT / "docs/testing/evidence/me00a-candidate6-2026-10-13"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as file:
        json.dump(value, file, ensure_ascii=False, indent=2)


def main():
    if ARCHIVE.exists():
        raise RuntimeError("只追加归档：目标已存在，禁止覆盖")
    phase = RUNTIME / "candidate6-plan-r1/Q05-flash"
    direct = phase / "direct-equal-information"
    frozen = read(RUNTIME / "candidate6/source.json")
    protocol = read(RUNTIME / "protocol.json")
    sample = next(value for value in read(RUNTIME / "cases.json") if value["id"] == "Q05")
    answers = read(phase / "actual-answers.json")["answers"]
    questions = read(phase / "plan.json")["body"]["data"]["questions"]
    assert [(q["id"], q["question"]) for q in questions] == [
        (a["questionId"], a["question"]) for a in answers]
    assert digest(RUNTIME / "cases.json") == frozen["baseCasesSha256"]
    assert digest(RUNTIME / "protocol.json") == frozen["protocolSha256"]
    assert digest(RUNTIME / "MeEvaluationServer.java") == frozen["traceServerSha256"]
    for file, sha in frozen["criticalClasses"].items():
        assert digest(RUNTIME / "candidate6/classes" / file) == sha
    assert sample["enhancement"]["includeExamples"] is False
    assert not sample["context"]["files"]

    # 原始执行和直接增强接收同一组实际题答；Plan 只执行平台正文，不由工具补答案。
    log = "\n".join(f"问题：{a['question']}\n回答：{a['answer']}" for a in answers)
    equal_input = sample["rawPrompt"] + "\n\n本次测试实际确认信息（逐字题答）：\n" + log
    assert (phase / "raw-input.md").read_text(encoding="utf-8") == equal_input
    assert read(direct / "input.json")["rawPrompt"] == equal_input
    plan_input = read(phase / "input.json")
    assert plan_input["rawPrompt"] == sample["rawPrompt"]
    assert plan_input["planConfirmation"]["answers"] == answers
    for folder in (phase, direct):
        copied = (folder / "copied.md").read_text(encoding="utf-8")
        assert copied == (folder / "enhanced-input.md").read_text(encoding="utf-8")
        assert copied == read(folder / "platform.json")["body"]["data"]["optimizedPrompt"]
        history = read(folder / "history.json")["body"]["data"]
        assert history["optimizedPrompt"] == copied
        assert history["rawPrompt"] == read(folder / "input.json")["rawPrompt"]
        assert read(folder / "history-check.json")["copiedBodyMatches"] is True

    phases = Counter()
    for number in range(1, 177):
        trace = read(RUNTIME / "traces" / f"{number:03}.json")
        phases[trace["phase"]] += 1
    assert phases == {"baseline": 70, "candidateValidation": 106}
    assert not (RUNTIME / "traces/177.json").exists()
    for number, folder, input_name, work_name in (
            (173, phase, "enhanced-input.md", "work.md"),
            (174, phase, "raw-input.md", "raw-work.md"),
            (176, direct, "enhanced-input.md", "work.md")):
        trace = read(RUNTIME / "traces" / f"{number:03}.json")
        assert trace["request"]["messages"] == [{
            "role": "user", "content": (folder / input_name).read_text(encoding="utf-8")}]
        assert trace["response"]["choices"][0]["message"]["content"] == (
            folder / work_name).read_text(encoding="utf-8")
        assert trace["request"]["model"] == "deepseek-flash"
        assert trace["request"]["temperature"] == 0.2
        assert trace["request"]["max_tokens"] == 8192
        assert trace["request"]["thinking"] == {"type": "disabled"}
    invalid = read(RUNTIME / "traces/171.json")
    invalid_body = json.loads(invalid["response"]["choices"][0]["message"]["content"])
    assert all(isinstance(item, dict) for item in invalid_body["ambiguities"])

    entries = []

    def copy(source, target):
        target_path = ARCHIVE / target
        target_path.parent.mkdir(parents=True, exist_ok=True)
        with target_path.open("xb") as file:
            file.write(source.read_bytes())
        entries.append({"source": source.relative_to(ROOT).as_posix(),
                        "target": target, "sha256": digest(target_path),
                        "transformation": "byte-for-byte copy"})

    for source in sorted(phase.rglob("*")):
        if source.is_file():
            copy(source, "q05-flash/" + source.relative_to(phase).as_posix())
    for number in range(170, 177):
        source = RUNTIME / "traces" / f"{number:03}.json"
        # 不归档认证头、Cookie、密钥配置或账号文件；只保留已允许的模型载荷。
        trace = read(source)
        assert set(trace["request"]).issubset({
            "model", "messages", "temperature", "thinking", "stream", "max_tokens", "response_format"})
        copy(source, f"traces/{number:03}.json")
    for name in ("protocol.json", "protocol-amendment-03.json", "candidate6-call-summary.json",
                 "candidate6-mechanical.json", "candidate6-review-mapping.private.json"):
        copy(RUNTIME / name, name)
    copy(RUNTIME / "candidate6/source.json", "candidate6-source.json")
    copy(RUNTIME / "candidate6/tracked-source.diff", "candidate6-tracked-source.diff")
    copy(RUNTIME / "MeEvaluationServer.java", "harness/MeEvaluationServer.java")
    for source in sorted((RUNTIME / "candidate6-review").iterdir()):
        if source.is_file():
            copy(source, "review/" + source.name)
    write(ARCHIVE / "q05-case.json", sample)
    write(ARCHIVE / "integrity-check.json", {
        "status": "PASS", "candidate": "candidate6", "baseHead": frozen["commit"],
        "frozenSourceFiles": len(frozen["files"]), "actualAnswers": len(answers),
        "copiedBodyMatchesExecution": True, "completeHistoryMatchesCopiedBody": True,
        "scriptAddedMissingAnswersToPlan": False,
        "exactExecutionTraceNumbers": {"plan": 173, "rawEqualInformation": 174, "directEqualInformation": 176},
        "failedAttemptsPreserved": [{"number": 171, "httpStatus": 200,
                                      "platformReason": "AMBIGUITY_VALUE_INVALID"}],
        "modelVersion": "Exact upstream version UNKNOWN; response alias deepseek-flash",
        "budget": {"used": 176, "phases": dict(phases), "limits": protocol["phaseLimits"],
                   "totalLimit": 192, "batchCalls": 7, "batchMaximumCalls": 12,
                   "batchRemaining": 5, "candidateRemaining": 6, "baselineRemaining": 10},
        "priceAndCurrencyCost": "UNKNOWN"})

    local_source = RUNTIME / "local-followup-source-before-tests.json"
    summary = read(RUNTIME / "local-followup-verified-test-summary.json")
    assert summary["exitCode"] == 0 and summary["totals"] == {
        "tests": 593, "failures": 0, "errors": 0, "skipped": 0}
    assert summary["sourceSha256"] == digest(local_source)
    for file, sha in read(local_source)["files"].items():
        assert digest(ROOT / file) == sha
    for name in ("local-followup-source-before-tests.json", "local-followup-verified-test-summary.json",
                 "local-followup-final-run.json", "local-followup-red-summary.json",
                 "local-followup-review-red-summary.json"):
        copy(RUNTIME / name, "local-followup/" + name)
    for source in sorted((RUNTIME / "local-replay").iterdir()):
        if source.is_file() and source.suffix in (".java", ".md", ".json"):
            copy(source, "local-followup/replay/" + source.name)
    for name in ("red-summary.json", "verified-test-summary.json", "node-state.md", "before-source.json"):
        copy(ROOT / "tmp/me03a-research-boundary-20261013" / name,
             "local-followup/research/" + name)
    for source in sorted((RUNTIME / "local-review-red-source").iterdir()):
        copy(source, "local-followup/review-red-source/" + source.name)
    changed = [file for file, sha in read(local_source)["files"].items()
               if frozen["files"].get(file) != sha]
    for file in changed:
        copy(ROOT / file, "local-followup/source/" + file)
    before = (phase / "copied.md").read_text(encoding="utf-8")
    after = (RUNTIME / "local-replay/local-replay-copied.md").read_text(encoding="utf-8")
    replay = read(RUNTIME / "local-replay/local-replay-projection.json")
    assert [d["answer"] for d in replay["confirmedDecisions"]] == [a["answer"] for a in answers]
    assert sum(s.startswith("该问题尚未确定：") for s in replay["resultAmbiguities"]) == len(answers)
    assert after.count("用户说明：不确认任何既有参数") == 0
    write(ARCHIVE / "local-followup/replay-check.json", {
        "status": "PASS", "providerTrace": 172, "realModelCalls": 0,
        "boundQuestionsBefore": 5, "boundQuestionsAfter": 5, "completeAnswersPreserved": True,
        "repeatedBoundExplanation": {"before": before.count("用户说明：不确认任何既有参数"), "after": 0},
        "allNonConfirmationPhraseOccurrences": {"before": before.count("不确认任何既有参数"),
                                                "after": after.count("不确认任何既有参数")},
        "remainingDuplicate": "Model background/constraint boundary still occurs twice; not claimed as once-only fusion",
        "newLocalCodeRealWorksAcceptance": "NOT_RUN; paid candidate6 precedes these source changes"})
    write(ARCHIVE / "manifest.json", {
        "schemaVersion": 1, "kind": "APPEND_ONLY_CANDIDATE6_AND_SEPARATE_LOCAL_FOLLOWUP",
        "newProviderRequests": 7, "preservedFailedAttempts": 1,
        "localModelCalls": 0, "archivedSourceEntries": len(entries), "entries": entries,
        "localFollowupDifferentFromPaidCandidate": True,
        "excluded": ["keys", "account credentials", "cookies", "authorization headers",
                     "environment configuration", "class binaries", "Surefire XML properties"]})
    print(f"Archived {len(entries)} unchanged artifacts; calls 176=70+106; local tests 593, exit 0")


if __name__ == "__main__":
    main()
