package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放直接增强未绑定 Plan 时，模型独立复写权威未决清单的情况。
 * 只允许完整行的排版差异，保护新信息、业务作用域以及引用和代码材料。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class DirectPrerequisiteCompactionTest {
    private static final String REMINDER = "甲院2025年观察窗口尚未确定。";
    private static final String RETAINED = "继续交付已有资料支持的内容。";

    @Test
    void removesAnExactStandaloneReminderWithoutBoundPlanAnswers() {
        for (PromptSectionType type : List.of(PromptSectionType.BACKGROUND, PromptSectionType.TASK,
                PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS, PromptSectionType.ACCEPTANCE)) {
            var sections = sections(type, RETAINED + "\n" + REMINDER);

            compact(sections, List.of(REMINDER));

            assertThat(sections.get(type).content()).as(type.name()).isEqualTo(RETAINED);
        }
    }

    @Test
    void normalizesOnlyListLayoutWidthAndTerminalPunctuation() {
        for (String repeated : List.of("  - " + REMINDER, "2. " + REMINDER,
                "３、甲院２０２５年观察窗口尚未确定？", "• 甲院2025年观察窗口尚未确定")) {
            var sections = sections(PromptSectionType.BACKGROUND, RETAINED + "\n" + repeated);

            compact(sections, List.of(REMINDER));

            assertThat(sections.get(PromptSectionType.BACKGROUND).content()).as(repeated).isEqualTo(RETAINED);
        }
    }

    @Test
    void keepsDifferentObjectsYearsOperatorsCaseAndIdentifierSpacing() {
        List<String> authoritative = List.of(REMINDER, "A医院阈值>90分钟时的处理尚未确定。",
                "CodeA对应机构尚未确定。", "A B机构覆盖度尚未确定。", "医院①覆盖度尚未确定。",
                "阈值为10²时的处理尚未确定。");
        for (String distinct : List.of("乙院2025年观察窗口尚未确定。", "甲院2026年观察窗口尚未确定。",
                "A医院阈值>=90分钟时的处理尚未确定。", "codea对应机构尚未确定。", "AB机构覆盖度尚未确定。",
                "医院1覆盖度尚未确定。", "阈值为102时的处理尚未确定。")) {
            var sections = sections(PromptSectionType.OUTPUT, RETAINED + "\n" + distinct);

            compact(sections, authoritative);

            assertThat(sections.get(PromptSectionType.OUTPUT).content()).as(distinct).contains(distinct);
        }
    }

    @Test
    void keepsAdditionalInformationBeyondTheCompleteRegisteredReminder() {
        for (String distinct : List.of(REMINDER + "需核实节假日是否计入。",
                "甲院2025年观察窗口尚未确定；乙院采用24小时。")) {
            var sections = sections(PromptSectionType.BACKGROUND, RETAINED + "\n" + distinct);

            compact(sections, List.of(REMINDER));

            assertThat(sections.get(PromptSectionType.BACKGROUND).content()).as(distinct).contains(distinct);
        }
    }

    @Test
    void keepsConditionalAndQuotedStatementsEvenWhenTheListContainsThem() {
        for (String protectedContent : List.of("如果" + REMINDER + "请先核查院方文件。",
                "引用：“" + REMINDER + "”", "'" + REMINDER + "'", "`" + REMINDER + "`")) {
            var sections = sections(PromptSectionType.BACKGROUND, RETAINED + "\n" + protectedContent);

            compact(sections, List.of(protectedContent));

            assertThat(sections.get(PromptSectionType.BACKGROUND).content()).as(protectedContent).contains(protectedContent);
        }
    }

    @Test
    void keepsCodeTablesQuotesAndNamedBusinessSections() {
        for (String protectedContent : List.of("```text\n" + REMINDER + "\n```",
                "~~~text\n" + REMINDER + "\n~~~", "| 说明 |\n| --- |\n| " + REMINDER + " |",
                "> " + REMINDER, "### 甲院2026年业务规则\n" + REMINDER,
                "**甲院2026年业务规则**\n" + REMINDER, "__甲院2026年业务规则__\n" + REMINDER)) {
            var sections = sections(PromptSectionType.CONSTRAINTS, RETAINED + "\n" + protectedContent);

            compact(sections, List.of(REMINDER));

            assertThat(sections.get(PromptSectionType.CONSTRAINTS).content()).as(protectedContent).contains(protectedContent);
        }
    }

    @Test
    void preservesTheCompleteAuthoritativeListOnTheSecondCompactionPass() {
        var complete = IntStream.rangeClosed(1, 12)
                .mapToObj(index -> "医院" + index + "观察窗口尚未确定。").toList();
        String canonical = "执行前须确认（仅涉及下列未决条件的步骤需等待确认；不得自行假定答案）：\n- "
                + String.join("\n- ", complete);
        var sections = sections(PromptSectionType.CONSTRAINTS, RETAINED + "\n" + canonical);

        compact(sections, complete);
        compact(sections, complete);

        assertThat(sections.get(PromptSectionType.CONSTRAINTS).content()).contains(canonical);
        complete.forEach(value -> assertThat(sections.get(PromptSectionType.CONSTRAINTS).content()).contains(value));
    }

    @Test
    void doesNotLeaveARequiredNarrativeSectionEmpty() {
        var sections = sections(PromptSectionType.BACKGROUND, REMINDER);

        compact(sections, List.of(REMINDER));

        assertThat(sections.get(PromptSectionType.BACKGROUND).content()).isEqualTo(REMINDER);
    }

    private static void compact(Map<PromptSectionType, PromptSection> sections, List<String> prerequisites) {
        ProviderPrerequisiteCompactor.compact(sections, prerequisites, ConfirmedDecisionSet.from(List.of()),
                "依据已有资料撰写科研报告初稿。");
    }

    private static Map<PromptSectionType, PromptSection> sections(PromptSectionType type, String content) {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(type, new PromptSection(type, type.name(), content));
        return sections;
    }
}
