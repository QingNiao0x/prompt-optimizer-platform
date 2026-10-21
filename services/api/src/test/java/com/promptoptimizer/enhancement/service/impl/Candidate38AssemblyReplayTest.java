package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatiblePromptEnhancementProvider;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 404—406完整要求与414括注示例原件的两态回放；不冒充新剪贴板/数据库验证。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class Candidate38AssemblyReplayTest {
    @ParameterizedTest
    @ValueSource(ints = {404, 405, 406, 414})
    void savedProviderBodyKeepsSourcesWhileCompleteRequirementsAreNotProjectedAgain(int trace) throws Exception {
        var mapper = new ObjectMapper();
        try (var input = getClass().getResourceAsStream("/fixtures/candidate38-existing-model-replay.json")) {
            var sample = java.util.stream.StreamSupport.stream(mapper.readTree(input).path("samples").spliterator(), false)
                    .filter(row -> row.path("trace").asInt() == trace).findFirst().orElseThrow();
            String original = sample.path("modelResponseRaw").asText();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo(sample.path("modelResponseSha256").asText());
            String raw = sample.path("rawPrompt").asText();
            var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
            var groups = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false,
                    TaskIntentResolver.resolve(TemplateCode.AUTO, raw));
            var template = new PromptTemplate(TemplateCode.AUTO, "保留交付。", "遵守原要求。");
            // 使用真实适配器解析边界，含既有 presentation 缺省归一化；原件及正文不手工补字段。
            var builder = org.springframework.web.client.RestClient.builder();
            var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
            var properties = new OpenAiCompatibleProperties();
            properties.setEndpoint(java.net.URI.create("https://model.example.test/v1/chat/completions"));
            properties.setApiKey("test-api-key");
            properties.setModel("deepseek-flash");
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(properties.getEndpoint()))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                            mapper.writeValueAsString(java.util.Map.of("model", "deepseek-flash", "choices", List.of(
                                    java.util.Map.of("finish_reason", "stop", "message", java.util.Map.of("content", original))))),
                            org.springframework.http.MediaType.APPLICATION_JSON));
            var response = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), mapper, properties).enhance(
                    new EnhancementProviderRequest(raw, context, template, List.of(), List.of(), false,
                            groups.visibleConstraints(), List.of(), EnhancementOptions.defaults()));
            server.verify();
            for (var format : PromptFormat.values()) {
                var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of());
                var assembly = (java.util.function.Supplier<OptimizationResult>) () -> new OptimizationResultAssembler().assemble(response, context,
                        template,
                        List.of(), List.of(), false, groups, 1, raw, List.of(), List.of(), task, format);
                var result = assembly.get();
                var constraints = result.sections().stream().filter(section -> section.type() == PromptSectionType.CONSTRAINTS)
                        .findFirst().orElseThrow().content();
                assertThat(result.constraintGroups()).isEqualTo(groups);
                assertThat(result.appliedConstraints()).containsAll(groups.visibleConstraints());
                assertThat(result.promptFormat()).isEqualTo(format);
                for (String rule : groups.platformMandatory()) assertThat(constraints).contains(rule);
                if (trace == 404) assertThat(constraints).doesNotContain("- 返回新Map");
                if (trace == 406) assertThat(constraints).doesNotContain("- 为核心逻辑补充正常、异常和边界场景测试。");
                assertThat(mapper.readTree(mapper.writeValueAsString(result)).path("optimizedPrompt").asText()).isEqualTo(result.optimizedPrompt());
                assertThat(result.sections()).noneMatch(section -> section.type() == PromptSectionType.EXAMPLES);
            }
            assertThat(sample.path("modelResponseRaw").asText()).isEqualTo(original);
        }
    }
}
