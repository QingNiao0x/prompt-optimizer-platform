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
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 真实452合法否定被误拒的回放；正向、并列、转折及双重否定分别保留原方向。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ExamplePolarityRegressionTest {
    @ParameterizedTest
    @ValueSource(strings = {"只输出英文译文，不附解释、说明或示例。", "交付报告，不附说明和示例。", "输出结果，不添加解释和示例。",
            "不提供代码（含调用示例）。", "不要提供虚构示例。"})
    void negativeExampleObjectsAreNotNewPositiveObligations(String content) {
        var sections = sections(content);
        assertThatCode(() -> ExampleResultIntegrator.integrate(sections, ExampleContract.from("解释内容。", false)))
                .doesNotThrowAnyException();
        assertThat(sections.get(PromptSectionType.OUTPUT).content()).isEqualTo(content);
    }

    @ParameterizedTest
    @ValueSource(strings = {"输出结果，提供示例。", "只输出译文，但附一个示例。", "不附说明，但提供示例。", "不得不提供示例。",
            "输出代码、说明及示例。", "代码（含调用示例）。", "输出结果，不附说明，并给出示例。"})
    void independentPositiveObligationsRemainRejectedWithTheSwitchOff(String content) {
        assertThatThrownBy(() -> ExampleResultIntegrator.integrate(sections(content), ExampleContract.from("解释内容。", false)))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void explicitShortVerbProhibitionWinsOverTheEnabledSwitch() {
        assertThat(ExampleContract.from("输出结果，不附解释、说明或示例。", true).mode()).isEqualTo(ExampleContract.Mode.PROHIBITED);
        assertThat(ExampleContract.from("解释内容，不得使用虚构示例。", true).required()).isTrue();
        assertThat(ExampleContract.from("不得不提供示例。", false).required()).isTrue();
    }

    @Test
    void actual452PassesTheCompleteAssemblerWithoutChangingTheModelResponse() throws Exception {
        var mapper = new ObjectMapper();
        try (var stream = getClass().getResourceAsStream("/fixtures/example-polarity-452.json")) {
            var fixture = mapper.readTree(stream); String original = fixture.path("modelResponseRaw").asText();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo(fixture.path("modelResponseSha256").asText());
            var body = mapper.readTree(original); String raw = fixture.path("rawPrompt").asText();
            var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), true);
            var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
            var groups = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), true, task.intent());
            var response = new EnhancementProviderResponse(mapper.convertValue(body.path("sections"), new TypeReference<List<PromptSection>>() { }),
                    "fixture", "fixture", true, List.of(), List.of(), mapper.treeToValue(body.path("assessment"), TaskEnhancementAssessment.class));
            var result = new OptimizationResultAssembler().assemble(response, context,
                    new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), List.of(), false,
                    groups, true, 1, raw, List.of(), List.of(), task, PromptFormat.COMPACT);
            assertThat(result.optimizedPrompt()).contains("只输出英文译文，不附解释、说明或示例。", "周五上午九点");
            assertThat(result.sections()).noneMatch(section -> section.type() == PromptSectionType.EXAMPLES);
        }
    }

    private EnumMap<PromptSectionType, PromptSection> sections(String content) {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", content));
        return sections;
    }
}
