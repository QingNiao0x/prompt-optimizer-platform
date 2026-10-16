package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 同一离线探针运行于冻结十八及十九，不启动服务、不改原件或调用模型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class CandidateNineteenProbe {
    /** 固定输出身份与目录，拒绝任意输入路径；正文仅供离线检查，不是新的历史或剪贴板。 */
    public static void main(String[] args) throws Exception {
        String runtime = switch (args[0]) {
            case "parent18", "candidate19" -> args[0];
            default -> throw new IllegalArgumentException("Unknown frozen runtime");
        };
        var json = new ObjectMapper();
        var fixture = json.readTree(Path.of("services/api/src/test/resources/enhancement/candidate19-trace265.json").toFile());
        var questions = json.readerForListOf(PlanQuestion.class).<List<PlanQuestion>>readValue(fixture.path("questions"));
        String raw = fixture.path("rawPrompt").asText();
        var kept = new PlanQuestionFilter().filter(questions, new PlanningProviderRequest(raw, "", List.of()));
        var scoring = json.readTree(Path.of("services/api/src/test/resources/enhancement/candidate18-trace267.json").toFile());
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (var node : scoring.path("response").path("sections")) {
            var section = json.treeToValue(node, PromptSection.class);
            sections.put(section.type(), section);
        }
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections,
                EffectiveTaskViewFactory.create(scoring.path("rawPrompt").asText(), TemplateCode.AUTO, List.of(), List.of(), false),
                json.treeToValue(scoring.path("response").path("assessment"), TaskEnhancementAssessment.class));
        var code = new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, "使用requests库爬取网页标题。");
        var report = Map.of("runtime", runtime, "modelCalls", 0, "keptQuestionIds", kept.stream().map(PlanQuestion::id).toList(),
                "lengthGuidance", TaskBodyLengthContract.guidance(raw), "scoringTask", sections.get(PromptSectionType.TASK).content(),
                "codeTemplateOutput", code.outputGuidance(), "codeTemplateAcceptance", code.acceptanceGuidance());
        Files.writeString(Path.of("docs/testing/evidence/me00a-candidate19-offline-2026-10-16/probe-" + runtime + ".json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
