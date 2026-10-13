package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.provider.domain.ExampleContract;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.EnumMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 将开关与否定请求、纯译文、严格格式和禁虚构一起送入实际示例组装路径。
 * @author QingNiao
 * @since 0.1.0
 */
class ExampleBoundaryCombinationTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aNegatedRequestCannotAddExamplesOrFieldsToStrictJson(boolean enabled) {
        var contract = ExampleContract.from("只输出JSON，字段只有result。不要求提供示例。", enabled);
        var sections = sections();
        ExampleResultIntegrator.integrate(sections, contract);
        assertThat(contract.required()).isFalse();
        assertThat(sections).doesNotContainKey(PromptSectionType.EXAMPLES);
        assertThat(sections.get(PromptSectionType.OUTPUT).content()).isEqualTo("保持用户限定的输出。");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void explicitProhibitionAndTranslationRemainWithoutAddedExamples(boolean enabled) {
        for (String raw : new String[]{"解释材料，不得提供示例。", "只输出译文，不附解释。"}) {
            var contract = ExampleContract.from(raw, enabled);
            var sections = sections();
            ExampleResultIntegrator.integrate(sections, contract);
            assertThat(sections).doesNotContainKey(PromptSectionType.EXAMPLES);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void requiredExamplesCannotTurnADataBanIntoADemonstrationException(boolean enabled) {
        var contract = ExampleContract.from("解释数据格式并提供输入与预期输出示例。不得虚构任何数据。", enabled);
        assertThat(contract.required()).isTrue();
        assertThat(contract.fictionalDataAllowed()).isFalse();
        var sections = sections();
        sections.put(PromptSectionType.EXAMPLES, new PromptSection(PromptSectionType.EXAMPLES, "示例",
                "输入：虚构数据；预期输出：演示结果。"));
        assertThatThrownBy(() -> ExampleResultIntegrator.integrate(sections, contract))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void examplesExplicitlyInsideTheJsonFormatStillRemainRequired(boolean enabled) {
        var contract = ExampleContract.from("只输出JSON，字段为result和examples，examples提供输入与预期输出示例。", enabled);
        assertThat(contract.required()).isTrue();
        var sections = sections();
        sections.remove(PromptSectionType.EXAMPLES);
        ExampleResultIntegrator.integrate(sections, contract);
        assertThat(sections.get(PromptSectionType.EXAMPLES).content()).contains("输入与预期输出");
    }

    private EnumMap<PromptSectionType, PromptSection> sections() {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", "保持用户限定的输出。"));
        sections.put(PromptSectionType.EXAMPLES, new PromptSection(PromptSectionType.EXAMPLES, "示例", "输入：材料；预期输出：说明。"));
        return sections;
    }
}
