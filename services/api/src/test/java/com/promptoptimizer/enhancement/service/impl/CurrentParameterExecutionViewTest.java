package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实际输出表已确认一院阈值时，任务中的公共旧未知须同步更新；条件、引用和其他范围不改。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CurrentParameterExecutionViewTest {
    private static final String RAW = "为甲院和乙院制定候诊时间分析方案。两院需要独立计算后再比较。"
            + "两院比较观察窗口尚未确定。异常等待阈值尚未确定。交付指标表。";
    private static final List<PlanAnswer> ANSWERS = List.of(new PlanAnswer("threshold", "甲院异常等待阈值是什么？",
            "本次仅确认甲院异常等待阈值为90分钟。乙院异常等待阈值尚未确定。"));

    @Test
    void updatesTheOldCollectiveUnknownInTaskWithoutChangingTheOtherHospital() {
        String prompt = assemble("异常等待的阈值也没有定，不能把资料中的示例阈值当作医院采用的规则。", ANSWERS);
        assertThat(prompt).doesNotContain("异常等待的阈值也没有定")
                .contains("甲院异常等待的阈值采用90分钟", "乙院异常等待的阈值尚未确定", "不能把资料中的示例阈值当作医院采用的规则")
                .contains("| 甲院异常等待的阈值 | 90分钟 |", "| 乙院异常等待的阈值 | 待确认 |");
    }

    @Test
    void updatesOnlyTheConfirmedPropertyInACompoundCurrentStatement() {
        String prompt = assemble("两院比较观察窗口尚未确定，异常等待阈值尚未确定。", ANSWERS);
        assertThat(prompt).doesNotContain("，异常等待阈值尚未确定")
                .contains("| 甲院的观察窗口 | 待确认 |", "| 乙院的观察窗口 | 待确认 |");
    }

    @Test
    void preservesQuotedFutureAndYearScopedUnknowns() {
        for (String unchanged : List.of("如果以后异常等待阈值尚未确定，则另行讨论。",
                "如果以后采用新的统计周期；异常等待阈值尚未确定。",
                "2025年原记录；异常等待阈值尚未确定。",
                "甲院2026年异常等待阈值尚未确定。", "> 异常等待阈值尚未确定。",
                "```text\n异常等待阈值尚未确定。\n```", "“异常等待阈值尚未确定”是原记录。")) {
            assertThat(assemble(unchanged, ANSWERS)).as(unchanged).contains(unchanged);
        }
    }

    @Test
    void aDirectRequestWithoutAnswersDoesNotInventASelection() {
        String prompt = assemble("异常等待阈值尚未确定。", List.of());
        assertThat(prompt).doesNotContain("采用90分钟").contains("| 甲院异常等待的阈值 | 待确认 |",
                "| 乙院异常等待的阈值 | 待确认 |");
    }

    private static String assemble(String task, List<PlanAnswer> answers) {
        return assemble(task, answers, new ContextSnapshot("", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "v1"), List.of()).optimizedPrompt();
    }

    @Test
    void updatedExecutionEvidenceIsLabelledWhileOriginalCardsStayUntouched() {
        String original = "两院比较观察窗口尚未确定，异常等待阈值尚未确定，不得自行补值";
        var fact = new PlanningFactCard("threshold", PlanningFactCategory.BUSINESS_RULE,
                "docs/两院数据说明.txt", original);
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(
                new FileSnippet(fact.sourcePath(), "text", original, "两院候诊分析", false)), List.of(), List.of(), "v1");
        for (List<PlanningFactCard> cards : List.of(List.of(fact), List.<PlanningFactCard>of())) {
            var result = assemble("提供两院独立分析方案。", ANSWERS, context, cards);
            assertThat(result.optimizedPrompt()).contains("按本次明确决定更新", "非原文摘录", "甲院异常等待的阈值采用90分钟");
            assertThat(result.evidenceCards()).anySatisfy(card -> assertThat(card.evidence()).contains(original));
            assertThat(result.contextReport().fileSnippets().getFirst().content()).isEqualTo(original);
        }
    }

    private static OptimizationResult assemble(String task, List<PlanAnswer> answers,
            ContextSnapshot context, List<PlanningFactCard> facts) {
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "甲院和乙院独立分析。"),
                new PromptSection(PromptSectionType.TASK, "任务", task),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "交付可讨论方案与指标表。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不编造患者数据。")), "test", "test", false, List.of());
        return new OptimizationResultAssembler().assemble(response,
                context,
                new PromptTemplate(TemplateCode.RESEARCH_ANALYSIS, "指标表", "核对范围", "示例"), List.of(),
                answers, !answers.isEmpty(), List.of(), false, 1, RAW, facts, List.of());
    }
}
