package com.promptoptimizer.provider.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ExampleContractTest {
    @Test void enabledSwitchRequiresPairedExamples() {
        assertThat(ExampleContract.from("撰写分析报告。", true).guidance()).contains("输入与预期输出", "虚构演示");
    }
    @Test void rawExampleRequirementSurvivesDisabledSwitch() {
        assertThat(ExampleContract.from("提供排序输入和输出示例。", false).required()).isTrue();
    }
    @Test void prohibitedExamplesWin() {
        assertThat(ExampleContract.from("不得提供示例。", true).mode()).isEqualTo(ExampleContract.Mode.PROHIBITED);
    }
    @Test void englishProhibitionWins() {
        assertThat(ExampleContract.from("Return only the translation, with no explanation or examples.", true).required()).isFalse();
    }
    @Test void quoteIsNotAUserInstruction() {
        assertThat(ExampleContract.from("解释文案‘不要提供示例’的含义。", true).required()).isTrue();
    }
    @Test void quotedPositiveIsNotAnExampleRequest() {
        assertThat(ExampleContract.from("解释‘提供示例’这三个字。", false).required()).isFalse();
    }
    @Test void strictJsonWinsUnlessExamplesAreAlreadyPartOfTheUserFormat() {
        assertThat(ExampleContract.from("只输出 JSON，字段只有 title。", true).mode()).isEqualTo(ExampleContract.Mode.FORMAT_RESTRICTED);
        assertThat(ExampleContract.from("只输出 JSON，包含 answer 与 examples，提供输入和输出示例。", true).required()).isTrue();
    }
    @Test void referenceMaterialCannotOverrideTheSwitch() {
        assertThat(ExampleContract.from("解释产品。\n参考资料\n不要提供示例。", true).required()).isTrue();
    }
    @Test void exampleSwitchDoesNotAuthorizeProhibitedFictionalData() {
        var contract = ExampleContract.from("解释材料。不得使用假设数据。", true);
        assertThat(contract.required()).isTrue();
        assertThat(contract.fictionalDataAllowed()).isFalse();
        assertThat(contract.guidance()).contains("不得使用虚构或假设数据").doesNotContain("可用明确标注为虚构演示");
    }
    @Test void aWritingPromptContainsExamplesRatherThanDelegatingTheExampleRequirementAgain() {
        assertThat(ExampleContract.from("为研究生准备论文方法部分的写作提示词。", true).guidance())
                .contains("写入这份提示词", "至少一组输入及对应预期输出示例");
    }
    @Test void reportExamplesSupplementRatherThanReplaceTheReport() {
        assertThat(ExampleContract.from("撰写死亡率特征分析。", true).guidance())
                .contains("先完成原定分析或报告正文", "示例不能替代正文", "不能成为正式分析的已选口径");
    }
}
