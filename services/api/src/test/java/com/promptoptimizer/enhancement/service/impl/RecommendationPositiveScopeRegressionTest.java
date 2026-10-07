package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 正向推荐与同输入反例成对核对：用户偏好、既有依赖、机构年份和审批状态须分别有依据。
 * 推荐只是待用户选择的建议，不改变实际确认状态。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class RecommendationPositiveScopeRegressionTest {
    @Test
    void recommendsThePreferredChannelWithoutPretendingTheUserAlreadySelectedIt() {
        var q = question("本次审批结果通知渠道采用哪种？", "站内信", "邮件");
        var aligned = PlanRecommendationAligner.align(q, input("设计通知方案。我偏好站内信，请先让我确认渠道。", ""));
        assertThat(aligned.options()).filteredOn(PlanOption::recommended).singleElement().satisfies(value -> {
            assertThat(value.label()).isEqualTo("站内信");
            assertThat(value.recommendationReason()).contains("原始需求", "站内信").doesNotContain("用户已确认");
        });
    }

    @Test
    void preservesAPreferenceForANaturallyExclusiveChannelAndAnExistingDependency() {
        var channel = question("本次通知启用哪些渠道？", "仅站内信", "仅邮件");
        var aligned = PlanRecommendationAligner.align(channel,
                input("我偏好站内信，但渠道尚未最终选定，请先让我确认。", "两种渠道均已实现。"));
        assertThat(aligned.options()).filteredOn(PlanOption::recommended)
                .extracting(PlanOption::label).containsExactly("仅站内信");
        var storage = question("附件保存位置采用哪种方案？", "沿用 MinIO", "独立本地目录");
        assertThat(PlanRecommendationAligner.align(storage,
                input("我偏好 MinIO，以减少额外维护，但仍需要确认本次方案。", "现有凭证服务使用 MinIO。"))
                .options()).filteredOn(PlanOption::recommended).extracting(PlanOption::label).containsExactly("沿用 MinIO");
        assertThat(PlanRecommendationAligner.align(channel,
                input("如果以后确认偏好站内信，再讨论通知渠道。", "" )).options()).noneMatch(PlanOption::recommended);
        var denominator = question("分母采用哪种范围？", "仅有效记录", "全部记录");
        assertThat(PlanRecommendationAligner.align(denominator,
                input("我偏好有效记录相关的报告，但分母尚未决定。", "" )).options()).noneMatch(PlanOption::recommended);
    }

    @Test
    void filtersTheKnownCoverageStateButKeepsItsUnknownDefinitionAndAnotherYear() {
        String raw = "制定A医院数据质量方案。A医院2022年覆盖度未核实。";
        var known = new PlanQuestion("coverage", "A医院2022年的数据覆盖度是否已核实？", "覆盖度影响年度比较。",
                PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(known), input(raw, ""))).isEmpty();
        var definition = new PlanQuestion("definition", "A医院2022年覆盖度的具体定义是什么？", "", PlanQuestionType.FREE_TEXT,
                List.of(), List.of(), true);
        var year = new PlanQuestion("year", "A医院2023年的数据覆盖度是否已核实？", "", PlanQuestionType.FREE_TEXT,
                List.of(), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(definition, year), input(raw, ""))).hasSize(2);
    }

    @Test
    void recommendsExistingDependencyButDoesNotTreatATestSampleAsProductionEvidence() {
        var q = question("数据访问方案采用哪种？", "MyBatis-Plus", "JPA");
        var positive = input("设计订单查询，只交付方案。", "现有订单Mapper使用 MyBatis-Plus。");
        assertThat(PlanRecommendationAligner.align(q, positive).options()).filteredOn(PlanOption::recommended)
                .extracting(PlanOption::label).containsExactly("MyBatis-Plus");
        var negative = input("设计订单查询，只交付方案。", "[TEST_FIXTURE] Example.java：测试演示使用 MyBatis-Plus。");
        assertThat(PlanRecommendationAligner.align(q, negative).options()).noneMatch(PlanOption::recommended);
    }

    @Test
    void recommendsOnlyAnApprovedRuleForTheExactHospitalAndYear() {
        var q = new PlanQuestion("window", "甲院2025年的观察窗口采用哪种？", "请确认本次沿用。", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("24", "24小时", "甲院2025年的观察窗口采用24小时。", "甲院2025年的观察窗口采用24小时。", false),
                        new PlanOption("48", "48小时", "甲院2025年的观察窗口采用48小时。", "甲院2025年的观察窗口采用48小时。", true)), List.of(), true);
        assertThat(PlanRecommendationAligner.align(q, input("制定甲院2025年比较方案。", "甲院2025年的观察窗口采用24小时。该规则已批准并生效。"))
                .options()).filteredOn(PlanOption::recommended).extracting(PlanOption::id).containsExactly("24");
        for (String evidence : List.of("乙院2025年的观察窗口采用24小时。该规则已批准并生效。",
                "甲院2024年的观察窗口采用24小时。该规则已批准并生效。",
                "甲院2025年的观察窗口采用24小时。该规则尚未批准。")) {
            assertThat(PlanRecommendationAligner.align(q, input("制定甲院2025年比较方案。", evidence)).options())
                    .noneMatch(PlanOption::recommended);
        }
    }

    @Test
    void focusesTheQuestionOnTheNewDenominatorAndFiltersOnlyTheKnownPresentation() {
        String raw = "制定研究方案。负数先标记并回到来源核实，不擅自改成0。";
        var q = new PlanQuestion("cost", "费用为负数时，在方法方案中应如何标记和呈现？",
                "但需确认负数记录是否进入费用类指标分母。", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        var filtered = new PlanQuestionFilter().filter(List.of(q), input(raw, ""));
        assertThat(filtered).singleElement().satisfies(value -> {
            assertThat(value.question()).isEqualTo("费用为负数的记录是否纳入费用类指标的分母？");
            assertThat(value.id()).isEqualTo("cost");
        });
        var routine = new PlanQuestion("cost", q.question(), "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(routine), input(raw, ""))).isEmpty();
        var permission = new PlanQuestion("cost", q.question(), "另外需确认数据访问权限。", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(permission), input(raw, ""))).hasSize(1);
    }

    @Test
    void doesNotReaskAHypotheticalConflictHandlingRuleThatTheUserAlreadySpecified() {
        String raw = "若两份资料对同一指标给出不同阈值，应把指标、医院、适用时间与两个取值说明清楚。";
        var q = new PlanQuestion("hypothesis", "若两份资料对同一指标给出不同阈值，应如何处理？", "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("show", "并列呈现", "", "并列呈现两个取值", false),
                        new PlanOption("newest", "采用较新资料", "", "以发布时间较新的资料为准", false)), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(q), input(raw, ""))).isEmpty();
        var actual = new PlanQuestion("context-conflict-window", q.question(), q.hint(), q.type(), q.options(), q.examples(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(actual), input(raw, ""))).hasSize(1);
        var advice = new PlanQuestion("advice", "若资料中建议阈值与医院现行规则不一致，方法方案应如何处理？",
                "建议阈值未经医院批准，不能当作正式规则。", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(advice), input(raw + "建议阈值未经过医院批准。", ""))).isEmpty();
        var newValue = new PlanQuestion(advice.id(), advice.question(), "甲院2026年出现90分钟的新阈值，需要核对。",
                advice.type(), advice.options(), advice.examples(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(newValue), input(raw, ""))).containsExactly(newValue);
    }

    @Test
    void delegatesRoutineFailureCoverageButKeepsRetryPoliciesAndPermissionDecisions() {
        String raw = "为订单服务新增附件保存能力，交付实现方案、权限边界、失败与重试行为和验收清单。";
        var routine = new PlanQuestion("failure", "失败与重试行为需要覆盖哪些失败场景？", "确定覆盖边界才能写全方案。",
                PlanQuestionType.MULTIPLE_CHOICE, List.of(
                new PlanOption("write", "存储写入失败", "", "失败与重试行为需覆盖存储写入失败场景。", false),
                new PlanOption("access", "权限校验失败", "", "失败与重试行为需覆盖权限校验失败场景。", false),
                new PlanOption("order", "订单不存在或不可用", "", "失败与重试行为需覆盖订单不存在或不可用场景。", false)), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(routine), input(raw, ""))).isEmpty();
        var retry = new PlanQuestion("retry", "失败后最多重试多少次？", "", PlanQuestionType.FREE_TEXT,
                List.of(), List.of(), true);
        var permission = new PlanQuestion("permission", "对应订单的授权用户具体指哪些人？", "", PlanQuestionType.FREE_TEXT,
                List.of(), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(retry, permission), input(raw, "")))
                .containsExactly(retry, permission);
        assertThat(new PlanQuestionFilter().filter(List.of(routine), input(raw + "请让我选择失败场景的覆盖范围。", "")))
                .containsExactly(routine);
        var newPolicy = new PlanQuestion(routine.id(), routine.question(), "另外需确认失败后的补偿金额上限。",
                routine.type(), routine.options(), routine.examples(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(newPolicy), input(raw, ""))).containsExactly(newPolicy);
        var medical = input("拟定医学研究方案，需要说明失败与重试行为，研究阈值尚未确定。", "");
        assertThat(new PlanQuestionFilter().filter(List.of(routine), medical)).containsExactly(routine);
    }

    @Test
    void inheritsTheExplicitHospitalAggregationOrderWithoutHidingAnActualChange() {
        String raw = "制定两院年度比较方法。分医院先算再汇总，展示样本量、缺失数和有效分母。";
        var known = new PlanQuestion("aggregation", "年度与医院比较时，样本量、缺失数和有效分母的呈现方式是否按医院先算再汇总？",
                "资料已要求分医院先算再汇总，此处确认是否作为固定呈现规则。", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("by", "是，分医院先算再汇总", "", "分医院先算再汇总。", false),
                        new PlanOption("all", "先汇总再分医院", "", "先汇总再分医院。", false)), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(known), input(raw, ""))).isEmpty();
        assertThat(new PlanQuestionFilter().filter(List.of(known), input(raw + "本次需要调整汇总顺序，请让我选择。", "")))
                .containsExactly(known);
        var conflict = new PlanQuestion("context-conflict-aggregation", known.question(), known.hint(), known.type(),
                known.options(), known.examples(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(conflict), input(raw, ""))).containsExactly(conflict);
        var threshold = new PlanQuestion(known.id(), known.question(), "另外需确认小样本医院的剔除阈值。", known.type(),
                known.options(), known.examples(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(threshold), input(raw, ""))).containsExactly(threshold);
    }

    private static PlanQuestion question(String question, String first, String second) {
        return new PlanQuestion("choice", question, "请确认本次采用的方案。", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("first", first, "", first, false), new PlanOption("second", second, "", second, true)), List.of(), true);
    }

    private static PlanningProviderRequest input(String raw, String evidence) {
        return new PlanningProviderRequest(raw, "", List.of(), new PlanningContextDigest("", List.of(), List.of(),
                List.of(), evidence.isBlank() ? List.of() : List.of(evidence), "COMPLETE", 1, List.of()));
    }
}
