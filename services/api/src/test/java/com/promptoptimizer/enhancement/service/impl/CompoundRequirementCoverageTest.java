package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放404/406的完整动作及保护反例；只验证追加覆盖，不允许删除原文或来源。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CompoundRequirementCoverageTest {
    private static final String TEST_RULE = "为核心逻辑补充正常、异常和边界场景测试。";

    @Test
    void compoundDeliveryCoversItsCompleteTestObligationWithoutDroppingRunInstructions() {
        var sections = sections("为核心逻辑补充正常、异常和边界场景测试，并给出安装与运行说明。");
        var original = sections.get(PromptSectionType.TASK);
        assertThat(TaskConstraintCoverage.uncovered(sections, List.of(TEST_RULE))).isEmpty();
        assertThat(AdditionalRuleProjection.uncovered(sections, TEST_RULE)).isEmpty();
        assertThat(sections.get(PromptSectionType.TASK)).isEqualTo(original);
    }

    @Test
    void spacesBetweenLatinIdentifiersCannotForceAnotherCopyOfTheSameReturnRequirement() {
        var sections = sections("2. 不可变性：返回新 Map，不修改 current 与 patch；不修改输入中的嵌套对象引用所指向的内容。");
        assertThat(AdditionalRuleProjection.uncovered(sections, "返回新Map")).isEmpty();
        assertThat(sections.get(PromptSectionType.TASK).content())
                .contains("不修改 current 与 patch", "不修改输入中的嵌套对象引用所指向的内容");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "如果获批，为核心逻辑补充正常、异常和边界场景测试，并给出安装与运行说明。",
            "为核心逻辑补充正常、异常和边界场景测试，并给出安装与运行说明，但遗留模块除外。",
            "为核心逻辑补充正常、异常和边界场景测试，并给出安装与运行说明；仅适用于支付接口。",
            "不要求为核心逻辑补充正常、异常和边界场景测试，并给出安装与运行说明。",
            "另一任务：\n为核心逻辑补充正常、异常和边界场景测试，并给出安装与运行说明。",
            "以后确认：\n为核心逻辑补充正常、异常和边界场景测试，并给出安装与运行说明。",
            "> 为核心逻辑补充正常、异常和边界场景测试，并给出安装与运行说明。",
            "```text\n为核心逻辑补充正常、异常和边界场景测试，并给出安装与运行说明。\n```",
            "为核心逻辑只补充正常场景测试，并给出安装与运行说明。",
            "为支付接口的核心逻辑补充正常、异常和边界场景测试，并给出安装与运行说明。"
    })
    void independentConditionsObjectsNegationAndPartialCoverageRemainUncovered(String body) {
        var sections = sections(body);
        assertThat(TaskConstraintCoverage.uncovered(sections, List.of(TEST_RULE))).containsExactly(TEST_RULE);
        assertThat(AdditionalRuleProjection.uncovered(sections, TEST_RULE)).isNotEmpty();
    }

    @Test
    void coordinatingVerbDoesNotReleaseAnInheritedProhibition() {
        var sections = sections("禁止读取凭据，并输出日志。");
        assertThat(AdditionalRuleProjection.uncovered(sections, "输出日志。")).containsExactly("输出日志。");
    }

    @ParameterizedTest
    @ValueSource(strings = { "退款模块：返回新Map，不修改输入。", "引用：返回新Map，不修改输入。",
            "如果获批，返回新Map，不修改对象引用。", "返回新Map，但对象引用允许修改。" })
    void anAspectLabelNeverMakesANewObjectOrExceptionGlobal(String text) {
        assertThat(AdditionalRuleProjection.uncovered(sections(text), "返回新Map")).isNotEmpty();
    }

    private EnumMap<PromptSectionType, PromptSection> sections(String task) {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", task));
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", "交付实现和测试。"));
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "保留原定边界。"));
        return sections;
    }
}
