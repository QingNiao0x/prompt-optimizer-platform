package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回放390—395合成原件；新保护可以拒绝已知额外示例，不能误拒禁例原件或丢失规则来源。
 * 离线序列化检查不是新的数据库或剪贴板验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class Candidate37AssemblyReplayTest {
    @ParameterizedTest
    @ValueSource(ints = {390, 391, 392, 393, 394, 395})
    void replaysBothFormatsWithoutRepairingTheSavedModelContent(int trace) throws Exception {
        var mapper = new ObjectMapper();
        JsonNode sample;
        try (var input = getClass().getResourceAsStream("/fixtures/candidate37-existing-model-replay.json")) {
            sample = java.util.stream.StreamSupport.stream(mapper.readTree(input).path("samples").spliterator(), false)
                    .filter(row -> row.path("trace").asInt() == trace).findFirst().orElseThrow();
        }
        String original = sample.path("modelResponseRaw").asText();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(original.getBytes(StandardCharsets.UTF_8)))).isEqualTo(sample.path("modelResponseSha256").asText());
        JsonNode model = mapper.readTree(original);
        var response = new EnhancementProviderResponse(
                mapper.convertValue(model.path("sections"), new TypeReference<List<PromptSection>>() { }),
                "deepseek", "deepseek-flash", false, List.of(), List.of(),
                mapper.treeToValue(model.path("assessment"), TaskEnhancementAssessment.class));
        String raw = sample.path("rawPrompt").asText();
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
        var groups = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false,
                TaskIntentResolver.resolve(TemplateCode.AUTO, raw));
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), false);
        for (var format : PromptFormat.values()) {
            var assemble = (java.util.function.Supplier<com.promptoptimizer.enhancement.domain.OptimizationResult>) () ->
                    new OptimizationResultAssembler().assemble(response, context,
                            new PromptTemplate(TemplateCode.AUTO, "保留交付。", "遵守原要求。", "不新增示例。"),
                            List.of(), List.of(), false, groups, false, 1, raw, List.of(), List.of(), task, format);
            if (trace == 393) {
                assertThatThrownBy(assemble::get).isInstanceOf(ProviderResponseValidationException.class);
            } else {
                var result = assemble.get();
                assertThat(result.constraintGroups()).isEqualTo(groups);
                assertThat(result.appliedConstraints()).containsAll(groups.visibleConstraints());
                for (String rule : groups.platformMandatory()) assertThat(result.optimizedPrompt()).contains(rule);
                assertThat(result.promptFormat()).isEqualTo(format);
                var serialized = mapper.readTree(mapper.writeValueAsString(result));
                assertThat(serialized.path("optimizedPrompt").asText()).isEqualTo(result.optimizedPrompt());
                assertThat(serialized.path("constraintGroups")).isEqualTo(mapper.valueToTree(groups));
            }
        }
        assertThat(sample.path("modelResponseRaw").asText()).isEqualTo(original);
    }
}
