package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证充分需求按完整原文整理，未知、新选择及无法归类的章节不被简化规则吞掉。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class MinimalPromptOrganizerTest {
    private static final String RAW = "## 任务\n请实现Java 21的审批工具。\n## 背景\n当前为独立工具库。\n"
            + "## 输出\n只输出代码块。\n## 约束\n不得联网，不能写文件。退款amount<=1000；审批amount<1000。";

    @Test
    void preservesEveryBodyLineAndComparatorWhileOrganizingKnownSections() {
        var sections = sections();
        assertThat(MinimalPromptOrganizer.organize(sections, RAW, ConfirmedDecisionSet.from(List.of()))).isTrue();
        assertThat(sections.get(PromptSectionType.CONSTRAINTS).content()).contains("退款amount<=1000", "审批amount<1000", "不得联网");
        assertThat(sections.get(PromptSectionType.OUTPUT).content()).isEqualTo("只输出代码块。");
        assertThat(sections.get(PromptSectionType.BACKGROUND).content()).isEqualTo("当前为独立工具库。");
    }

    @Test
    void doesNotReplaceNewConfirmedDecisionsOrGuessMissingStructure() {
        assertThat(MinimalPromptOrganizer.organize(sections(), RAW, ConfirmedDecisionSet.from(List.of(new PlanAnswer(
                "choice", "采用哪个工具？", "本次采用PostgreSQL。"))))).isFalse();
        assertThat(MinimalPromptOrganizer.organize(sections(), RAW.replace("## 背景", "## 特殊资料权限"),
                ConfirmedDecisionSet.from(List.of()))).isFalse();
        assertThat(MinimalPromptOrganizer.organize(sections(), RAW.replace("当前为独立工具库。", ""),
                ConfirmedDecisionSet.from(List.of()))).isFalse();
    }

    @Test
    void preservesNamedUnknownConditionsInsteadOfDeclaringZeroAmbiguities() {
        var sections = sections();
        assertThat(MinimalPromptOrganizer.organize(sections, RAW + "\n## 尚待明确\n退款时效尚未确定，不得默认7天。",
                ConfirmedDecisionSet.from(List.of(new PlanAnswer("refund", "退款时效如何确定？", "暂不确定。"))))).isTrue();
        assertThat(sections.get(PromptSectionType.CONSTRAINTS).content()).contains("退款时效尚未确定", "不得默认7天");
        assertThat(NewsLengthContract.conciseGuidance("撰写新闻稿正文600–800字，必须3段，标题另计。"))
                .contains("600–800字", "标题/附件计数边界").doesNotContain("个短段");
    }

    private Map<PromptSectionType, PromptSection> sections() {
        var values = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        for (PromptSectionType type : List.of(PromptSectionType.BACKGROUND, PromptSectionType.TASK,
                PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS)) values.put(type, new PromptSection(type, type.name(), "模型已有的内容"));
        return values;
    }
}
