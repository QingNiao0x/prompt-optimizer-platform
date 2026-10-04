package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 从实际过滤与提醒归并入口保护运算条件、对象归属和同类别的独立属性。
 * 反例采用合成业务资料，不读取用户文件，也不要求模型逐字复现某份提示词。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PlanScopeSafetyRegressionTest {
    private final PlanQuestionFilter filter = new PlanQuestionFilter();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"金额>50000|金额<50000", "金额>50000|金额>=50000",
            "金额<50000|金额<=50000", "金额=50000|金额!=50000", "金额=1.5|金额=15", "金额=1.1|金额=11"})
    void shouldKeepDifferentOperatorsAndDecimalValuesRegardlessOfOrder(String left, String right) {
        var first = question("first", left + "元时采用什么审批规则？");
        var second = question("second", right + "元时采用什么审批规则？");
        assertThat(filter.filter(List.of(first, second), input("设计审批流程"))).containsExactly(first, second);
        assertThat(filter.filter(List.of(second, first), input("设计审批流程"))).containsExactly(second, first);
    }

    @Test
    void shouldStillMergeWhitespaceAndQuestionMarkVariantsOfTheSameRule() {
        var first = question("first", "金额 > 50000 元时采用什么审批规则？");
        var second = question("second", "金额>50000元时采用什么审批规则?");
        assertThat(filter.filter(List.of(first, second), input("设计审批流程"))).containsExactly(first);
    }

    @Test
    void shouldNotUseOneHospitalsFileFormatToAnswerAnotherHospital() {
        var first = question("first", "甲医院数据采用什么文件格式？");
        var second = question("second", "乙医院数据采用什么文件格式？");
        assertThat(filter.filter(List.of(first, second), input("比较甲医院和乙医院的数据。甲医院数据格式：CSV")))
                .containsExactly(second);
        assertThat(filter.filter(List.of(second, first), input("比较甲医院和乙医院的数据。甲医院数据格式：CSV")))
                .containsExactly(second);
    }

    @Test
    void shouldNotPromoteAnUnscopedFormatIntoANamedObjectsAnswer() {
        var second = question("second", "乙医院数据采用什么文件格式？");
        assertThat(filter.filter(List.of(second), input("比较多院数据。数据格式：CSV"))).containsExactly(second);
        assertThat(filter.filter(List.of(question("generic", "数据采用什么文件格式？")),
                input("分析数据。数据格式：CSV"))).isEmpty();
    }

    @Test
    void shouldUseScopedFactCardsWithoutTransferringTheirAnswers() {
        var card = new PlanningFactCard("F01", PlanningFactCategory.DATA_FORMAT,
                "docs/甲医院字典.md", "甲医院数据格式：CSV");
        var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(),
                "COMPLETE", 1, List.of(), List.of(card));
        var first = question("first", "甲医院数据采用什么文件格式？");
        var second = question("second", "乙医院数据采用什么文件格式？");
        assertThat(filter.filter(List.of(first, second),
                new PlanningProviderRequest("比较甲医院和乙医院的数据", "", List.of(), digest)))
                .containsExactly(second);
    }

    @Test
    void shouldResolveEachKnownObjectEvenWhenTheirFormatsDiffer() {
        String raw = "比较甲医院和乙医院的数据。甲医院数据格式：CSV。乙医院数据格式：Excel";
        assertThat(filter.filter(List.of(question("first", "甲医院数据采用什么文件格式？"),
                question("second", "乙医院数据采用什么文件格式？")), input(raw))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "甲课题研究地区：广东省|甲课题研究地区是哪里？|乙课题研究地区是哪里？",
            "甲部门目标受众：大学生|甲部门目标受众是谁？|乙部门目标受众是谁？",
            "甲合同适用法域：中国大陆|甲合同适用法域是哪个？|乙合同适用法域是哪个？",
            "甲队列数据来源：医院记录|甲队列数据来源是什么？|乙队列数据来源是什么？",
            "甲队列疾病亚类：缺血性心脏病|甲队列疾病亚类采用什么分类？|乙队列疾病亚类采用什么分类？",
            "甲报告分析工具：Python|甲报告分析工具使用哪种软件？|乙报告分析工具使用哪种软件？",
            "甲队列分析方法：时间序列分解|甲队列分析方法采用什么模型？|乙队列分析方法采用什么模型？",
            "甲报告验收标准：核对来源且不编造数据|甲报告验收标准是什么？|乙报告验收标准是什么？"
    })
    void shouldNotTransferOtherLabeledFactCategoriesBetweenNamedObjects(String raw, String knownText, String otherText) {
        var known = question("known", knownText);
        var other = question("other", otherText);
        assertThat(filter.filter(List.of(known, other), input(raw))).containsExactly(other);
        assertThat(filter.filter(List.of(other, known), input(raw))).containsExactly(other);
    }

    @Test
    void shouldKeepConflictsAndNewConditionsWithinTheSameObject() {
        var known = question("known", "甲医院数据采用什么文件格式？");
        assertThat(filter.filter(List.of(known), input("甲医院数据格式：CSV。甲医院数据格式：Excel")))
                .containsExactly(known);
        var next = question("next", "甲医院2026年度数据采用什么文件格式？");
        assertThat(filter.filter(List.of(next), input("甲医院数据格式：CSV"))).containsExactly(next);
    }

    @Test
    void shouldKeepDomainWordsInsideConcreteOrganizationNames() {
        var known = question("known", "标准化数据中心数据采用什么文件格式？");
        var other = question("other", "化数据中心数据采用什么文件格式？");
        assertThat(filter.filter(List.of(known, other), input("标准化数据中心数据格式：CSV")))
                .containsExactly(other);
    }

    @Test
    void shouldReuseLabeledApprovalRulesWithoutResolvingRefundOrEmergencyRules() {
        var approval = question("approval", "审批规则采用什么标准？");
        var refund = question("refund", "退款规则采用什么标准？");
        var emergency = question("emergency", "紧急订单审批规则采用什么标准？");
        assertThat(filter.filter(List.of(approval, refund, emergency), input("审批规则：金额超过五万元由财务复核")))
                .containsExactly(refund, emergency);
    }

    @Test
    void shouldNotUseAgeGroupsToResolveUrbanRuralGrouping() {
        var age = question("age", "年龄组按什么标准划分？");
        var urban = question("urban", "城乡人群按什么标准划分？");
        assertThat(filter.filter(List.of(age, urban), input("分析人群差异。年龄组：每5岁一组")))
                .containsExactly(urban);
    }

    @Test
    void shouldNotUseTheBodyFormatToResolveAnAppendixFormat() {
        var body = question("body", "正文输出采用什么格式？");
        var appendix = question("appendix", "附表输出采用什么格式？");
        assertThat(filter.filter(List.of(body, appendix), input("撰写报告。正文输出格式：Markdown")))
                .containsExactly(appendix);
    }

    @Test
    void shouldNotUseTheBaselinePeriodToResolveFollowUpPeriod() {
        var baseline = question("baseline", "基线分析期间是哪段时间？");
        var followUp = question("follow-up", "随访分析期间是哪段时间？");
        assertThat(filter.filter(List.of(baseline, followUp), input("制定研究方案。基线分析期间：2015-2025")))
                .containsExactly(followUp);
    }

    @Test
    void shouldNotMergeAnOppositeOperatorReminderIntoAnUnresolvedDecision() {
        var decisions = ConfirmedDecisionSet.from(List.of(
                new PlanAnswer("rule", "金额!=50000元时的审批规则是什么？", "暂不确定")));
        String independent = "金额=50000元时的审批规则尚未确定。";
        var merged = new PlanAmbiguityMerger(decisions).merge(List.of(independent), List.of(), List.of());
        assertThat(merged.messages()).hasSize(2).contains(independent);
    }

    @Test
    void shouldKeepAnIndependentConditionInsideTheSameQuestionEnvelope() {
        var first = new PlanQuestion("first", "甲医院数据采用什么文件格式？", "确认输入资料。",
                PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("csv", "CSV", "", "采用 CSV。", false)), List.of(), true);
        var second = new PlanQuestion("second", first.question(), "另需决定是否允许跨院共享。", first.type(),
                first.options(), List.of(), true);
        assertThat(filter.filter(List.of(first, second), input("分析甲医院资料"))).containsExactly(first, second);
        assertThat(filter.filter(List.of(first, second), input("甲医院数据格式：CSV"))).containsExactly(second);
    }

    @Test
    void shouldNotDelegateAResearchDecisionHiddenInsideAnOutlineQuestion() {
        var layout = new PlanQuestion("layout", "方法提纲的章节应如何组织？", "还需决定是否纳入未成年人样本。",
                PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("adult", "成人样本", "", "仅纳入成年人。", false)), List.of(), true);
        assertThat(filter.filter(List.of(layout), input("撰写研究方法提纲"))).containsExactly(layout);
        var routine = new PlanQuestion("routine", layout.question(), "常规章节排版。", layout.type(),
                List.of(new PlanOption("standard", "沿用常规结构", "", "按研究文体组织章节。", false)), List.of(), true);
        assertThat(filter.filter(List.of(routine), input("撰写研究方法提纲"))).isEmpty();
        assertThat(filter.filter(List.of(routine), input("撰写研究方法提纲，由我选择章节结构"))).containsExactly(routine);
    }

    @Test
    void shouldNotDelegateANewTimeoutHiddenInPseudocodeMetadata() {
        var grain = new PlanQuestion("grain", "伪代码需要详细到什么程度？", "还需确定超时阈值是否为50毫秒。",
                PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("flow", "流程级伪代码", "描述步骤", "采用流程级描述。", false)), List.of(), true);
        assertThat(filter.filter(List.of(grain), input("制定实现方案，交付关键伪代码"))).containsExactly(grain);
        var routine = new PlanQuestion("routine", grain.question(), "常规伪代码组织。", grain.type(),
                grain.options(), List.of(), true);
        assertThat(filter.filter(List.of(routine), input("制定实现方案，交付关键伪代码"))).isEmpty();
    }

    @Test
    void shouldMergeOnlyFormattingVariantsOfDirectEnhancementReminders() {
        var merger = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(List.of()));
        var merged = merger.merge(List.of("审批金额 > 50000 元的规则尚未确定。", "审批金额>50000元的规则尚未确定？",
                "审批金额>=50000元的规则尚未确定。"), List.of(), List.of());
        assertThat(merged.messages()).containsExactly("审批金额 > 50000 元的规则尚未确定。", "审批金额>=50000元的规则尚未确定。");
    }

    @Test
    void shouldMergeASelectionParaphraseButRetainTheNextYearsDecision() {
        var merger = new PlanAmbiguityMerger(ConfirmedDecisionSet.from(List.of(
                new PlanAnswer("format", "甲医院数据格式是什么？", "暂不确定"))));
        String next = "甲医院2026年度数据格式尚未选定。";
        var merged = merger.merge(List.of("甲医院数据格式尚未选定。", next), List.of(), List.of());
        assertThat(merged.messages()).hasSize(2).contains(next);
    }

    private PlanningProviderRequest input(String raw) {
        return new PlanningProviderRequest(raw, "", List.of());
    }

    private PlanQuestion question(String id, String text) {
        return new PlanQuestion(id, text, "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }
}
