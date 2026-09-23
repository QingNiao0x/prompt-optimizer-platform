package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.api.ConversationMessage;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PlanQuestionFilterTest {
    private final PlanQuestionFilter filter = new PlanQuestionFilter();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "科研死亡率分析；研究范围：广东省|研究地区是哪里？",
            "软件统计任务；编程语言：Python|分析使用哪种编程语言？",
            "教育教案；目标读者：初中学生|目标读者是谁？",
            "商业分析；输出格式：Excel报告|输出采用什么格式？",
            "法律文书；适用法域：中国大陆|适用哪个法域？",
            "运营活动；目标受众：老客户|目标受众是哪些人？"
    })
    void shouldRemoveOnlyQuestionsWhoseFactsAreExplicitAcrossDomains(String prompt, String text) {
        assertThat(filter.filter(List.of(question("one", text)), input(prompt))).isEmpty();
        assertThat(filter.filter(List.of(question("one", text)), input("请协助完成任务"))).hasSize(1);
    }

    @Test
    void shouldKeepConflictsUnknownFactsAndCompoundQuestions() {
        for (String facts : List.of("研究范围：待定", "研究范围：广东省\n研究范围：浙江省")) {
            assertThat(filter.filter(List.of(question("one", "研究地区是哪里？")), input(facts))).hasSize(1);
        }
        assertThat(filter.filter(List.of(question("one", "研究地区和人群分别是什么？")),
                input("研究范围：广东省"))).hasSize(1);
    }

    @Test
    void shouldDeduplicateTextWithoutDependingOnModelQuestionIds() {
        assertThat(filter.filter(List.of(question("one", "研究地区是哪里？"),
                question("two", "研究地区是哪里?")), input("死亡率分析"))).hasSize(1);
    }

    @Test
    void shouldTrustOnlyUserHistoryAndExplicitSafeDigestFacts() {
        var userHistory = new PlanningProviderRequest("分析死亡率", "",
                List.of(new ConversationMessage("user", "研究范围：广东省")));
        var assistantHistory = new PlanningProviderRequest("分析死亡率", "",
                List.of(new ConversationMessage("assistant", "研究范围：广东省")));
        assertThat(filter.filter(List.of(question("region", "研究地区是哪里？")), userHistory)).isEmpty();
        assertThat(filter.filter(List.of(question("region", "研究地区是哪里？")), assistantHistory)).hasSize(1);
    }

    private PlanningProviderRequest input(String text) { return new PlanningProviderRequest(text, "", List.of()); }
    private PlanQuestion question(String id, String text) {
        return new PlanQuestion(id, text, "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }
}
