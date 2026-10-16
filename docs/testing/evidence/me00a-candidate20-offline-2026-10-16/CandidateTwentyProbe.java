package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 同一探针比较冻结十九与当前二十的实际281／283原件，仅生成离线投影。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class CandidateTwentyProbe {
    /** 固定身份、目录与夹具；不启动服务、不读凭据、不调用模型。 */
    public static void main(String[] args) throws Exception {
        String runtime = switch (args[0]) {
            case "parent19", "candidate20" -> args[0];
            default -> throw new IllegalArgumentException("Unknown frozen runtime");
        };
        var json = new ObjectMapper();
        var rows = new ArrayList<Map<String, Object>>();
        for (int trace : List.of(281, 283)) {
            var fixture = json.readTree(Path.of("services/api/src/test/resources/enhancement/candidate20-trace" + trace + ".json").toFile());
            List<PlanAnswer> answers = fixture.has("actualAnswers")
                    ? json.readerForListOf(PlanAnswer.class).readValue(fixture.path("actualAnswers")) : List.of();
            var task = EffectiveTaskViewFactory.create(fixture.path("rawPrompt").asText(), TemplateCode.AUTO, answers, List.of(), false);
            var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
            for (var node : fixture.path("response").path("sections")) {
                var section = json.treeToValue(node, PromptSection.class);
                sections.put(section.type(), section);
            }
            var assessment = fixture.path("response").path("assessment").deepCopy();
            // 沿用既有研究Provider对缺省内部呈现字段的归一化，原始响应及全部gap不变。
            if (!assessment.has("presentation")) ((ObjectNode) assessment).put("presentation", "STRUCTURED");
            PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task, json.treeToValue(assessment, TaskEnhancementAssessment.class));
            int pending = task.pendingDecisions().size();
            var reminders = task.pendingDecisions().stream().map(p -> "已委托提出拟议设计，实际采用参数仍未确认：" + p.question()).toList();
            PlanDecisionIntegrator.promoteDelegatedPrerequisites(sections, reminders, task);
            var remaining = PlanDecisionIntegrator.uncoveredDelegatedPrerequisites(sections, reminders, task);
            String text = sections.get(PromptSectionType.TASK).content();
            rows.add(Map.of("trace", trace, "hasScoringObligationAndAnchors", text.contains("提出拟议评分尺度") && text.contains("各等级评分锚点"),
                    "remainingDelegatedReminders", remaining.size(), "pendingBefore", pending, "pendingAfter", task.pendingDecisions().size(), "offlineTask", text));
        }
        var planning = json.readTree(Path.of("services/api/src/test/resources/enhancement/candidate20-plan280.json").toFile());
        var questions = json.readerForListOf(PlanQuestion.class).<List<PlanQuestion>>readValue(planning.path("questions"));
        var kept = new PlanQuestionFilter().filter(questions, new PlanningProviderRequest(planning.path("rawPrompt").asText(), "", List.of()));
        var value = Map.of("runtime", runtime, "modelCalls", 0, "rows", rows, "keptQuestionIds", kept.stream().map(PlanQuestion::id).toList());
        Files.writeString(Path.of("docs/testing/evidence/me00a-candidate20-offline-2026-10-16/probe-" + runtime + ".json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
