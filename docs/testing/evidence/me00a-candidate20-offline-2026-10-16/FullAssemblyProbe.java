package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 使用281／283完整原件回放最终组装，不生成新模型响应、历史或剪贴板记录。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class FullAssemblyProbe {
    /** 固定无附件输入与实际题答；权限规则由原Completer生成，禁止把离线输出冒充真实复验。 */
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
        for (int trace : List.of(281, 283)) {
            var fixture = json.readTree(Path.of("services/api/src/test/resources/enhancement/candidate20-trace" + trace + ".json").toFile());
            String raw = fixture.path("rawPrompt").asText();
            List<PlanAnswer> answers = fixture.has("actualAnswers")
                    ? json.readerForListOf(PlanAnswer.class).readValue(fixture.path("actualAnswers")) : List.of();
            var value = fixture.path("response");
            var assessment = value.path("assessment").deepCopy();
            if (!assessment.has("presentation")) ((ObjectNode) assessment).put("presentation", "STRUCTURED");
            var response = new EnhancementProviderResponse(json.readerForListOf(PromptSection.class).readValue(value.path("sections")),
                    "captured-offline-not-real", "deepseek-flash", false,
                    json.readerForListOf(String.class).readValue(value.path("ambiguities")), List.of(),
                    json.treeToValue(assessment, TaskEnhancementAssessment.class));
            var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
            var bundle = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), true, intent);
            var result = new OptimizationResultAssembler().assemble(response, context,
                    new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), answers, !answers.isEmpty(),
                    bundle, false, 0, raw, List.of(), List.of());
            if (!result.optimizedPrompt().contains("各等级评分锚点")) throw new IllegalStateException("Scoring obligation lost in final assembly");
            Files.writeString(Path.of("docs/testing/evidence/me00a-candidate20-offline-2026-10-16/offline-assembled-trace" + trace + ".json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n", StandardOpenOption.CREATE_NEW);
        }
    }
}
