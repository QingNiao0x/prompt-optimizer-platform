package com.promptoptimizer.enhancement.service;

import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
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

    @Test
    void shouldNotAskKnownFrameworkAndShouldKeepConflictOrUnknownFramework() {
        var digest = new PlanningContextDigest("", List.of("Spring Boot 3"), List.of(),
                List.of(), List.of("pom.xml：订单服务"), "COMPLETE", 1, List.of());
        var known = new PlanningProviderRequest("开发订单接口", "", List.of(), digest);
        var unknown = input("开发订单接口");
        assertThat(filter.filter(List.of(question("framework", "项目使用什么后端框架？")), known)).isEmpty();
        assertThat(filter.filter(List.of(question("framework", "项目使用什么后端框架？")), unknown)).hasSize(1);
        assertThat(filter.filter(List.of(question("migration", "是否更换项目现有后端框架？")), known)).hasSize(1);
    }

    @Test
    void shouldMergeParaphrasesOfSingleKnownDimensionWithoutDroppingCompoundQuestions() {
        assertThat(filter.filter(List.of(
                question("region-a", "研究地区是哪里？"),
                question("region-b", "研究覆盖的地区范围是哪里？"),
                question("region-and-group", "研究地区和人群分别是什么？")
        ), input("分析死亡率"))).extracting("id").containsExactly("region-a", "region-and-group");
    }

    @Test
    void shouldDiscardResearchQuestionIntroducedOnlyByAttachment() {
        var digest = new PlanningContextDigest("", List.of("Spring Boot 3"), List.of(),
                List.of(), List.of("研究方案.txt：2015 至 2025 年死亡率研究"), "COMPLETE", 1, List.of());
        var input = new PlanningProviderRequest("开发订单接口", "", List.of(), digest);
        assertThat(filter.filter(List.of(
                question("research-region", "这项研究具体覆盖哪个地区？"),
                question("order-rule", "订单取消后已支付款项如何处理？")
        ), input)).extracting("id").containsExactly("order-rule");
    }

    @Test
    void shouldUseSourcedFactCardsToRemoveParaphrasedKnownQuestionsButKeepDifferentBusinessDecisions() {
        var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(),
                "COMPLETE", 2, List.of(), List.of(
                new PlanningFactCard("F01", PlanningFactCategory.REGION,
                        "docs/研究方案.txt", "研究范围：广东省"),
                new PlanningFactCard("F02", PlanningFactCategory.DATA_FORMAT,
                        "docs/数据字典.txt", "数据格式：CSV"),
                new PlanningFactCard("F03", PlanningFactCategory.BUSINESS_RULE,
                        "docs/订单审批方案.txt", "订单金额超过五万元时必须由财务复核。")
        ));
        var input = new PlanningProviderRequest("分析广东省数据并实现订单审批", "", List.of(), digest);

        assertThat(filter.filter(List.of(
                question("region-a", "研究地区是哪里？"),
                question("region-b", "本次研究覆盖哪个区域？"),
                question("format", "用户提供的数据是什么格式？"),
                question("approval", "订单金额的审批规则是什么？"),
                question("refund", "订单取消后已支付款项如何退款？")
        ), input)).extracting("id").containsExactly("refund");
    }

    @Test
    void shouldKeepQuestionsWhenFactCardsConflictOrOnlyPartiallyAddressACompoundQuestion() {
        var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(),
                "COMPLETE", 2, List.of(), List.of(
                new PlanningFactCard("F01", PlanningFactCategory.REGION,
                        "docs/a.txt", "研究范围：广东省"),
                new PlanningFactCard("F02", PlanningFactCategory.REGION,
                        "docs/b.txt", "研究范围：浙江省"),
                new PlanningFactCard("F03", PlanningFactCategory.DATA_FORMAT,
                        "docs/data.txt", "数据格式：CSV")
        ));
        var input = new PlanningProviderRequest("分析心脑血管疾病", "", List.of(), digest);

        assertThat(filter.filter(List.of(
                question("region", "研究区域具体是哪一省？"),
                question("compound", "研究数据的来源和格式分别是什么？")
        ), input)).extracting("id").containsExactly("region", "compound");
    }

    private PlanningProviderRequest input(String text) { return new PlanningProviderRequest(text, "", List.of()); }
    private PlanQuestion question(String id, String text) {
        return new PlanQuestion(id, text, "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }
}
