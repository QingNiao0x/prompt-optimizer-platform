package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.*;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.provider.domain.*;
import java.nio.file.*;
import java.util.List;

/** 只读真实失败原件，用冻结策略核对候选规则；不调用模型、不注册Plan或修改响应。 */
public final class PlanFailureReplay {
    /** 记录空选项事实及当前规则的逐字段拒绝，不把局部检查当成完整Plan验收。 */
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        Path folder = Path.of(args[0]);
        String raw = json.readTree(folder.resolve("cases.json").toFile()).get(0).path("rawPrompt").asText();
        var request = PlanningDecisionPolicy.enrich(new PlanningProviderRequest(raw, "", List.of()));
        var policy = PlanningDecisionPolicy.from(request);
        var findings = json.createArrayNode();
        for (int trace : List.of(314, 315)) {
            var original = json.readTree(folder.resolve("real/traces/" + trace + ".json").toFile());
            var content = json.readTree(original.path("response").path("choices").get(0)
                    .path("message").path("content").asText());
            for (var node : content.path("questions")) {
                String id = node.path("id").asText();
                for (var option : node.path("options")) {
                    if (option.path("answer").asText().isBlank()) {
                        findings.addObject().put("trace", trace).put("questionId", id)
                                .put("optionId", option.path("id").asText())
                                .put("finding", "OBSERVED_EMPTY_SELECTABLE_ANSWER");
                    }
                }
                var question = json.treeToValue(node, PlanQuestion.class);
                check(policy, question.question(), question, "", "questions.question", trace, findings);
                check(policy, question.hint(), question, "", "questions.hint", trace, findings);
                for (var option : question.options()) {
                    check(policy, option.label(), question, option.id(), "questions.options.label", trace, findings);
                    check(policy, option.description(), question, option.id(), "questions.options.description", trace, findings);
                    check(policy, option.answer(), question, option.id(), "questions.options.answer", trace, findings);
                }
            }
        }
        var result = json.createObjectNode();
        result.put("kind", "OFFLINE_PARTIAL_RULE_REPLAY_NOT_NEW_PLAN");
        result.put("modelCalls", 0);
        result.put("originalsModified", false);
        result.set("findings", findings);
        result.put("localThirdAttempt", "Frozen planning-purpose ceiling rejected before upstream I/O; not a third paid call");
        Files.writeString(args.length > 1 ? Path.of(args[1]) : folder.resolve("plan-failure-replay.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n", StandardOpenOption.CREATE_NEW);
    }

    /** 对单个原字段复用当前规则，不过滤候选、不生成替代答案。 */
    private static void check(PlanningDecisionPolicy policy, String text, PlanQuestion question, String optionId,
                              String field, int trace, com.fasterxml.jackson.databind.node.ArrayNode findings) {
        try {
            policy.validateCandidate(text, question.question(), field);
        } catch (ProviderResponseValidationException failure) {
            findings.addObject().put("trace", trace).put("questionId", question.id())
                    .put("optionId", optionId).put("field", field).put("reason", failure.getReason().name());
        }
    }
}
