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
    @ParameterizedTest
    @ValueSource(strings = {
            "交付可运行脚本，以及简短运行说明（依赖安装命令与调用示例）。",
            "提供一组输入与预期输出示例。", "Include an input and expected output example."
    })
    void disabledExamplesAlsoRejectNewDeliveryRequirementsInsideOtherSections(String content) {
        var values = sections();
        values.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", content));
        assertThatThrownBy(() -> ExampleResultIntegrator.integrate(values,
                ExampleContract.from("使用requests库爬取网页标题。", false)))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "交付完整源码与可运行测试，给出安装命令、输入格式和错误信息。",
            "提供可执行的测试示例，覆盖正常与异常。",
            "不提供调用示例；保留运行说明。", "只解释示例字段的定义，不新增样例。",
            "只输出邮件正文，不添加说明或示例。", "交付源码，不需要调用示例。",
            "> 提供调用示例。\n仅引用资料，不作为交付要求。",
            "```text\n提供调用示例。\n```\n交付代码。",
            "如果以后用户要求，才提供调用示例；本次只交付代码。"
            , "如果以后用户要求：\n- 提供调用示例。", "引用：\n提供调用示例。",
            "“下列文字来自旧材料：\n提供调用示例。\n”\n交付源码。"
    })
    void disabledModePreservesCodeTestsNegationsQuotedMaterialAndFutureRequirements(String content) {
        var values = sections();
        values.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", content));
        ExampleResultIntegrator.integrate(values, ExampleContract.from("实现标题抓取。", false));
        assertThat(values.get(PromptSectionType.OUTPUT).content()).isEqualTo(content);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void explicitRequestedInlineExamplesAreNotCancelledByTheSwitch(boolean enabled) {
        var values = sections();
        values.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", "提供输入与预期输出示例。"));
        ExampleResultIntegrator.integrate(values, ExampleContract.from("交付代码并提供调用示例。", enabled));
        assertThat(values.get(PromptSectionType.OUTPUT).content()).contains("输入与预期输出示例");
    }

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
