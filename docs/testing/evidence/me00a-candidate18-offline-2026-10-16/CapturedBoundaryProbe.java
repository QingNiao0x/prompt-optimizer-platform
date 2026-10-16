package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.RequirementBoundaryContract;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;

/**
 * 同一探针分别运行在冻结十七和当前十八class上，核对真实原件，不启动服务或调用模型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class CapturedBoundaryProbe {
    /** 只接受两种固定运行身份，将结果写入受控证据文件，不接收外部输出路径。 */
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var direct = json.readTree(Path.of("services/api/src/test/resources/enhancement/candidate18-trace267.json").toFile());
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (var node : direct.path("response").path("sections")) {
            var section = json.treeToValue(node, PromptSection.class);
            sections.put(section.type(), section);
        }
        var task = EffectiveTaskViewFactory.create(direct.path("rawPrompt").asText(), TemplateCode.AUTO, List.of(), List.of(), false);
        PlanDecisionIntegrator.completeDeclaredEvaluation(sections, task,
                json.treeToValue(direct.path("response").path("assessment"), TaskEnhancementAssessment.class));
        var plan = json.readTree(Path.of("services/api/src/test/resources/enhancement/candidate18-trace266.json").toFile());
        var body = new StringBuilder();
        plan.path("response").path("sections").forEach(s -> body.append(s.path("content").asText()).append('\n'));
        String missing = RequirementBoundaryContract.from(plan.path("rawPrompt").asText(), List.of(), List.of()).missingGuidance(body.toString());
        String name = switch (args[0]) {
            case "candidate17-r2" -> "captured-probe-old-v2.json";
            case "candidate18" -> "captured-probe-current-v2.json";
            default -> throw new IllegalArgumentException("Unknown frozen runtime");
        };
        java.nio.file.Files.writeString(Path.of("docs/testing/evidence/me00a-candidate18-offline-2026-10-16", name),
                json.writeValueAsString(java.util.Map.of("runtime", args[0],
                "declaredCurrentScoringStillMissing", !sections.get(PromptSectionType.TASK).content().contains("提出拟议评分尺度"),
                "completeWorkflowStillDuplicated", missing.contains("研究若要求同信息对照，保留各组指定的增强流程"),
                "modelCalls", 0)) + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
    }
}
