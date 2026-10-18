package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复用389原始响应验证两态的同一组装路径；不把离线回放当成新模型结果或实际作品。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CompactGuidanceReplayTest {
    @ParameterizedTest
    @EnumSource(PromptFormat.class)
    void real389OnlySkipsTheCompleteTestRequirementWhileKeepingNarrowerRulesAndEverySource(PromptFormat format) throws Exception {
        var mapper = new ObjectMapper();
        JsonNode sample;
        // 选择固定原件并校验原始字节；不从平台复制正文反向补造模型内容。
        try (var stream = getClass().getResourceAsStream("/fixtures/candidate36-existing-model-replay.json")) {
            sample = java.util.stream.StreamSupport.stream(mapper.readTree(stream).path("samples").spliterator(), false)
                    .filter(row -> row.path("trace").asInt() == 389).findFirst().orElseThrow();
        }
        String original = sample.path("modelResponseRaw").asText();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(original.getBytes(StandardCharsets.UTF_8)))).isEqualTo(sample.path("modelResponseSha256").asText());
        var model = mapper.readTree(original);
        var assessment = (ObjectNode) model.path("assessment").deepCopy();
        // 与现有Provider相同的缺省presentation兼容；不修改非法值或补任何业务信息。
        if (!assessment.has("presentation")) assessment.put("presentation", "STRUCTURED");
        var response = new EnhancementProviderResponse(
                mapper.convertValue(model.path("sections"), new TypeReference<List<PromptSection>>() { }),
                "deepseek", "deepseek-flash", false, List.of(), List.of(),
                mapper.treeToValue(assessment, TaskEnhancementAssessment.class));
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
        String raw = sample.path("rawPrompt").asText();
        var constraints = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false,
                TaskIntentResolver.resolve(TemplateCode.AUTO, raw));
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), false);
        var result = new OptimizationResultAssembler().assemble(response, context,
                new PromptTemplate(TemplateCode.AUTO, "保留交付。", "遵守原要求。", "不添加未授权示例。"),
                List.of(), List.of(), false, constraints, false, 1, raw, List.of(), List.of(), task, format);

        String body = result.sections().stream().filter(section -> section.type() == PromptSectionType.CONSTRAINTS)
                .findFirst().orElseThrow().content();
        assertThat(body).doesNotContain("\n- 为核心逻辑补充正常、异常和边界场景测试。");
        assertThat(body).contains("校验所有外部输入，并明确处理空值、非法值和边界条件。",
                "处理可预期异常，返回清晰错误信息，不吞掉或伪造错误。");
        assertThat(result.optimizedPrompt()).contains("为核心逻辑补充正常、异常和边界场景的测试。");
        assertThat(result.appliedConstraints()).containsAll(constraints.visibleConstraints());
        assertThat(result.constraintGroups()).isEqualTo(constraints);
        assertThat(result.ambiguities()).isEmpty();
        assertThat(result.promptFormat()).isEqualTo(format);
        for (String rule : constraints.platformMandatory()) assertThat(result.optimizedPrompt()).contains(rule);
        assertThat(result.optimizedPrompt()).doesNotContain("平台强制约束（不得删除或弱化）");
    }
}
