package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.nio.file.*;
import java.util.*;

/** 本地冻结响应重放，不连接模型或数据库；仅输出合成题的来源投影和组装正文。 */
public class ReplayCandidateSix {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var fixture = json.readTree(Files.readString(Path.of(args[0])));
        var provider = fixture.path("providerResponse");
        var answers = List.of(json.treeToValue(fixture.path("answers"), PlanAnswer[].class));
        var raw = fixture.path("rawPrompt").asText();
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.RESEARCH_ANALYSIS, answers, List.of(), false);
        var response = new EnhancementProviderResponse(
                List.of(json.treeToValue(provider.path("sections"), PromptSection[].class)), "test", "test", false,
                List.of(json.treeToValue(provider.path("ambiguities"), String[].class)), List.of(),
                json.treeToValue(provider.path("assessment"), TaskEnhancementAssessment.class));
        var result = new OptimizationResultAssembler().assemble(response,
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "按原文交付。", "保留原意。", "遵守示例选择。"),
                List.of(), answers, true, List.of(), false, 1, raw);
        Files.writeString(Path.of(args[1], "local-replay-copied.md"), result.optimizedPrompt());
        Files.writeString(Path.of(args[1], "local-replay-projection.json"), json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("confirmedDecisions", task.confirmedDecisions(),
                        "executionRequirements", task.executionRequirements(), "pendingDecisions", task.pendingDecisions(),
                        "resultAmbiguities", result.ambiguities(), "source", "frozen trace 172; no new model call")));
    }
}
