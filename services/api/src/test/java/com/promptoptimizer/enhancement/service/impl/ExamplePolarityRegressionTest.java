package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 保留真实452合法否定响应的完整组装回放，确认功能退役后原定译文要求仍可交付。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ExamplePolarityRegressionTest {
    @Test
    void actual452PassesTheCompleteAssemblerWithoutChangingTheModelResponse() throws Exception {
        var mapper = new ObjectMapper();
        try (var stream = getClass().getResourceAsStream("/fixtures/example-polarity-452.json")) {
            var fixture = mapper.readTree(stream); String original = fixture.path("modelResponseRaw").asText();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo(fixture.path("modelResponseSha256").asText());
            var body = mapper.readTree(original); String raw = fixture.path("rawPrompt").asText();
            var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of());
            var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
            var groups = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), true, task.intent());
            var response = new EnhancementProviderResponse(mapper.convertValue(body.path("sections"), new TypeReference<List<PromptSection>>() { }),
                    "fixture", "fixture", true, List.of(), List.of(), mapper.treeToValue(body.path("assessment"), TaskEnhancementAssessment.class));
            var result = new OptimizationResultAssembler().assemble(response, context,
                    new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), List.of(), false,
                    groups, 1, raw, List.of(), List.of(), task, PromptFormat.COMPACT);
            assertThat(result.optimizedPrompt()).contains("只输出英文译文，不附解释、说明或示例。", "周五上午九点");
            assertThat(result.sections()).noneMatch(section -> section.type() == PromptSectionType.EXAMPLES);
        }
    }

}
