package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanOption;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只读医院资料比较沿用明确交付，未知观察窗口、新取值和真正的采用授权保持独立。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ReadOnlyMaterialComparisonTest {
    private static final String STATEMENT = "旧流程说明提前24小时取消不计爽约；新会议草稿建议提前12小时，尚未批准。请指出差异，不擅自采用其中一项。";
    private static final String RAW = "只提供内部运营分析与写作方案。两份资料存在差异：" + STATEMENT;

    /** 字面跨词匹配不能把材料讨论误当时区需求，也不能因此忽略真正的新分组维度。 */
    @Test
    void shouldDistinguishDiscussingDifferencesFromTimeZoneGrouping() {
        var policy = ReadOnlyMaterialComparison.from(RAW);
        String text = "旧流程说明提前24小时取消与新会议草稿提前12小时，本次报告应如何处理差异？";
        assertThat(policy.directedQuestion(question(text, "以便讨论时区分已给定说明与未批准建议。"))).isTrue();
        assertThat(policy.directedQuestion(question(text, "本次还需按时区分组，采用哪个时区？"))).isFalse();
        assertThat(policy.directedQuestion(question(text, "讨论时区分两份材料，同时请确定时区分组维度。"))).isFalse();
    }

    /** 问题、提示和全部选项共同证明只是重问资料采用；真正未知的例外、观察窗与新值保留。 */
    @Test
    void shouldRecognizeTheSameClosedMaterialChoiceInHintAndOptions() {
        var choices = List.of(new PlanOption("old", "旧流程", "旧流程提前24小时", "采用旧流程提前24小时取消不计爽约。", false, ""),
                new PlanOption("new", "新草稿", "新草稿提前12小时", "采用新会议草稿提前12小时取消不计爽约。", false, ""));
        var policy = ReadOnlyMaterialComparison.from(RAW);
        var hint = "旧流程说明提前24小时取消不计爽约，新会议草稿建议提前12小时且尚未批准。";
        assertThat(policy.directedQuestion(new PlanQuestion("q", "取消免责边界应采用哪份资料的口径？", hint,
                PlanQuestionType.SINGLE_CHOICE, choices, List.of(), true))).isTrue();
        assertThat(policy.directedQuestion(new PlanQuestion("q", "取消免责边界应如何定义？", hint,
                PlanQuestionType.SINGLE_CHOICE, choices, List.of(), true))).isTrue();
        assertThat(policy.directedQuestion(new PlanQuestion("q", "新增例外情形的取消免责边界应如何定义？", hint,
                PlanQuestionType.SINGLE_CHOICE, choices, List.of(), true))).isFalse();
        assertThat(policy.directedQuestion(new PlanQuestion("q", "仅在工作日的取消免责边界应如何定义？", hint,
                PlanQuestionType.SINGLE_CHOICE, choices, List.of(), true))).isFalse();
        assertThat(policy.directedQuestion(new PlanQuestion("q", "医院B的取消免责边界应如何定义？", hint,
                PlanQuestionType.SINGLE_CHOICE, choices, List.of(), true))).isFalse();
        assertThat(policy.directedQuestion(question("取消免责边界应如何定义？", ""))).isFalse();
    }

    @Test
    void inheritsTheAlreadySpecifiedComparisonWithoutMakingTheOldNoteEffective() {
        var policy = ReadOnlyMaterialComparison.from(RAW);
        assertThat(policy.guidance()).as("原文显式只读对照应建立具名规则范围").isNotEmpty();
        assertThat(policy.directedQuestion(question("对于提前24小时取消不计爽约的旧规则和提前12小时的新草稿，分析方案中应如何处理？", "两份资料存在差异。"))).isTrue();
        assertThat(policy.directedQuestion(question("旧流程说明的提前24小时与新会议草稿的提前12小时存在差异，方案中应如何处理？", "需明确取消免责边界。"))).isTrue();
        assertThat(policy.guidance()).contains("24小时", "12小时", "尚未批准", "不擅自采用其中一项").doesNotContain("现行");
        assertThat(policy.directedReminder("两份资料存在差异：" + STATEMENT)).isTrue();
    }

    @Test
    void keepsMissingConditionsNewObjectsNewValuesAndEffectiveRuleSelection() {
        var policy = ReadOnlyMaterialComparison.from(RAW);
        for (String text : List.of("预约未到诊的观察窗口应如何定义？", "取消免责边界应如何定义？",
                "退款的旧规则24小时和新规则12小时应如何采用？", "旧流程24小时取消与新草稿12小时应如何处理，生效日期何时？",
                "旧流程24小时取消和新草稿18小时应如何处理？")) {
            assertThat(policy.directedQuestion(question(text, ""))).as(text).isFalse();
        }
        assertThat(ReadOnlyMaterialComparison.from(RAW + "本次必须选定生效口径。").directedQuestion(question(
                "旧流程24小时取消和新草稿12小时应如何处理？", ""))).isFalse();
        assertThat(policy.directedQuestion(question("旧流程24小时取消和新草稿12小时应如何处理？", "请确定跨医院授权。"))).isFalse();
        assertThat(policy.directedReminder("两份资料存在差异：" + STATEMENT + "审批时间尚未确定。")).isFalse();
    }

    private PlanQuestion question(String text, String hint) {
        return new PlanQuestion("q", text, hint, PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }
}
