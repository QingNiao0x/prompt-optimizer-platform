package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.enhancement.domain.PlanningFactOrigin;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 保护不同属性和版本的证据边界，不允许用押金的版本归属猜测维修条款归属。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class SourceObjectContractTest {
    /** 提问视图保留已证实的部分，不按排除法补全其他版本；翻译任务不追加分析要求。 */
    @Test
    void shouldGivePlanningTheSamePartialBindingEvidenceWithinABoundedView() {
        var contract = SourceObjectContract.from(RAW + "草稿A维修条款写承租方承担日常维护。", null, List.of());
        String guide = contract.planningGuidance(com.promptoptimizer.template.domain.TaskDeliveryProfile.GENERAL);
        assertThat(guide).contains("| 维修条款 | 承租方承担日常维护 | 草稿A |", "版本对应待核实", "不重复询问")
                .hasSizeLessThanOrEqualTo(6000);
        assertThat(contract.planningGuidance(com.promptoptimizer.template.domain.TaskDeliveryProfile.TRANSLATION)).isEmpty();
        String many = "比较方案A和方案B。" + java.util.stream.IntStream.range(0, 90)
                .mapToObj(index -> "事项" + index + "规则一版写" + "甲".repeat(60) + "，另一版新增" + "乙".repeat(60) + "。")
                .collect(java.util.stream.Collectors.joining());
        assertThat(SourceObjectContract.from(many, null, List.of())
                .planningGuidance(com.promptoptimizer.template.domain.TaskDeliveryProfile.GENERAL))
                .hasSizeLessThanOrEqualTo(6000).contains("行超出提问摘要预算");
    }

    /** 选择主文本和指定对照版本不将已知条款归属扩展到对照版本。 */
    @Test
    void shouldNotTreatAStandaloneComparisonRoleAsAnotherMaintenanceOwner() {
        var contract = SourceObjectContract.from(RAW + "草稿A维修条款写承租方承担日常维护。", null, List.of());
        assertThatCode(() -> contract.validate(
                "草稿A维修条款写承租方承担日常维护，草稿B作为对照版本。", "questions.options.answer"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> contract.validate(
                "草稿B作为对照版本，草稿B维修条款写承租方承担日常维护。", "questions.options.answer"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    /** 角色词只在完整独立子句中使用，不能掩盖夹带的归属断言。 */
    @Test
    void shouldNotHideAnOwnershipAssertionInsideAComparisonRoleClause() {
        var contract = SourceObjectContract.from(RAW + "草稿A维修条款写承租方承担日常维护。", null, List.of());
        assertThatThrownBy(() -> contract.validate(
                "草稿B作为对照版本并写维修条款承租方承担日常维护。", "questions.options.answer"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    /** 押金的具名版本与匿名维修内容出现在同句时，不能借用押金标签推定维修归属。 */
    @Test
    void shouldNotBorrowVersionLabelsFromAnotherAttributeInAnAnonymousComparison() {
        assertThatCode(() -> SourceObjectContract.from(RAW, null, List.of()).validate(
                "草稿A押金为两个月租金，草稿B押金为三个月租金，维修条款一版写承租方承担日常维护，另一版新增全部设施故障费用。",
                "sections.BACKGROUND")).doesNotThrowAnyException();
    }

    /** 原文说明格不是版本标识格，即使引用中出现多个版本也不建立行归属。 */
    @Test
    void shouldNotInterpretQuotedMaterialAsAVerticalVersionLabel() {
        assertThatCode(() -> SourceObjectContract.from(RAW, null, List.of()).validate(
                "| 材料原文 | 备注 |\n| --- | --- |\n"
                        + "| 草稿A押金两个月，草稿B押金三个月；维修条款一版写承租方承担日常维护，另一版新增全部设施故障费用 | 并列资料 |",
                "sections.OUTPUT")).doesNotThrowAnyException();
    }

    /** 同句含匿名原文不允许掩盖此前独立断言的错误归属。 */
    @Test
    void shouldStillRejectAnExplicitAssertionBesideAnonymousMaterial() {
        assertThatThrownBy(() -> SourceObjectContract.from(RAW, null, List.of()).validate(
                "草稿A维修条款写承租方承担日常维护，维修条款一版写承租方承担日常维护，另一版新增全部设施故障费用。",
                "sections.TASK")).isInstanceOf(ProviderResponseValidationException.class);
    }

    /** 内容出处与版本对应的核实依据分别展示，后者不能错误引用最初的匿名材料。 */
    @Test
    void shouldKeepTheBindingProofSourceSeparateFromTheContentSource() {
        var proof = new PlanningFactCard("proof", PlanningFactCategory.BUSINESS_RULE,
                PlanningFactOrigin.USER_MATERIAL, "docs/归属核实.md", "草稿A维修条款写承租方承担日常维护。");
        String output = SourceObjectContract.from(RAW, null, List.of(proof)).deliveryGuidance();
        assertThat(output).contains("归属依据", "| 原始需求 | docs/归属核实.md |", "尚无对应证据");
    }

    /** 同一资料值的多个出处合并展示，事实本身和来源均不丢失。 */
    @Test
    void shouldDisplayAnIdenticalValueOnceAndKeepAllItsSources() {
        var material = new PlanningFactCard("material", PlanningFactCategory.BUSINESS_RULE,
                PlanningFactOrigin.USER_MATERIAL, "docs/维修说明.md",
                "维修条款一版写承租方承担日常维护，另一版新增全部设施故障费用。");
        String output = SourceObjectContract.from(RAW, null, List.of(material)).deliveryGuidance();
        assertThat(output.split("\\| 维修条款 \\| 承租方承担日常维护 \\|", -1)).hasSize(2);
        assertThat(output).contains("原始需求；docs/维修说明.md", "全部设施故障费用");
    }

    private static final String RAW = "整理草稿A和草稿B的差异。草稿A押金为两个月，草稿B押金为三个月。"
            + "维修条款一版写承租方承担日常维护，另一版新增全部设施故障费用。";

    /** 下游常见的短版本表头仍是在断言归属，表尾的待核说明不能撤销单元格含义。 */
    @Test
    void shouldRejectBareVersionColumnsAndMaintenanceRangeAliases() {
        var contract = SourceObjectContract.from(RAW, null, List.of());
        for (String header : List.of("| 条款 | A | B |", "| 条款 | A版 | B版 |", "| 条款 | 草稿 A | 草稿 B |")) {
            assertThatThrownBy(() -> contract.validate(header
                    + "\n| 维修范围 | 承租方承担日常维护 | 新增全部设施故障费用 |\n版本对应待核实。", "sections.OUTPUT"))
                    .as(header).isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    /** 纵向表格中，另一个状态列里的“未确认”不能使错误归属通过。 */
    @Test
    void shouldRejectNamedVersionRowsDespiteUnknownStatusElsewhere() {
        var contract = SourceObjectContract.from(RAW, null, List.of());
        assertThatThrownBy(() -> contract.validate("| 版本 | 维修范围 | 签署状态 |\n"
                + "| 草稿A | 承租方承担日常维护 | 未确定 |", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    /** 同版本格内只有付款状态未知，不是维修内容的版本关系未知。 */
    @Test
    void shouldNotUseAnUnrelatedUnknownToHideAVersionAssertion() {
        var contract = SourceObjectContract.from(RAW, null, List.of());
        assertThatThrownBy(() -> contract.validate("| 条款 | 草稿A | 草稿B |\n"
                + "| 维修范围 | 承租方承担日常维护，付款日期未确定 | 版本对应待核实 |", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    /** 已确认维修A版允许用短表头；押金和匿名材料仍保持各自的正确归属。 */
    @Test
    void shouldPreserveExplicitAndAnonymousRowsWithoutInventingTheOtherVersion() {
        var contract = SourceObjectContract.from(RAW + "草稿A维修条款写承租方承担日常维护。", null, List.of());
        assertThatCode(() -> contract.validate("| 条款 | A版 | B版 |\n"
                + "| 维修范围 | 承租方承担日常维护 | 版本对应待核实 |\n"
                + "| 押金 | 两个月 | 三个月 |", "sections.OUTPUT")).doesNotThrowAnyException();
        assertThatCode(() -> contract.validate("| 条款内容 | 版本对应关系 |\n"
                + "| 维修条款：全部设施故障费用 | 版本对应待核实 |", "sections.OUTPUT"))
                .doesNotThrowAnyException();
    }

    /** 全部版本都有本属性的证据后无需附加未知归属模板，常规任务也不增加交付负担。 */
    @Test
    void shouldNotAddUnknownDeliveryWhenBothMappingsAreExplicitOrNoAlternativeExists() {
        var complete = SourceObjectContract.from(RAW + "草稿A维修条款写承租方承担日常维护。"
                + "草稿B维修条款写全部设施故障费用。", null, List.of());
        assertThat(complete.deliveryGuidance()).isEmpty();
        assertThat(SourceObjectContract.from("用Java实现列表去重，保留元素出现顺序。", null, List.of()).deliveryGuidance()).isEmpty();
    }

    /** 视图保留条件与运算符，转义用户材料中的管道而不将它变成新增单元格。 */
    @Test
    void shouldKeepConditionsAndOperatorsInQuotedDeliveryValues() {
        String raw = "比较方案A和方案B。审批规则一版写在金额>=10时返回ALLOW|AUDIT，另一版写在金额<10时返回REJECT。";
        assertThat(SourceObjectContract.from(raw, null, List.of()).deliveryGuidance())
                .contains("金额>=10", "金额<10", "ALLOW\\|AUDIT", "版本对应待核实");
    }

    /** 未绑定内容不能放入A/B单元格后只在表尾声明未知；其他明确版本属性仍按证据保留。 */
    @Test
    void shouldMakeTheUnknownCorrespondenceExplicitInEachVersionCell() {
        assertThat(SourceObjectContract.from(RAW, null, List.of()).guidance())
                .contains("单元格只能填写“版本对应待核实”", "一版／另一版的已知内容另列", "不能靠表尾提醒", "其他已有明确版本依据");
    }

    /** 版本已经建立对应也不能把其适用条件删除，工作日规则不自动扩大为全天规则。 */
    @Test
    void shouldKeepTheConditionWhenAnExplicitVersionIsBound() {
        String raw = "比较方案A和方案B。通知规则一版写在工作日时通过邮件发送，另一版写在休息日时通过短信发送。"
                + "方案A通知规则写在工作日时通过邮件发送。";
        var contract = SourceObjectContract.from(raw, null, List.of());
        assertThatCode(() -> contract.validate("方案A通知规则写在工作日时通过邮件发送。", "sections.TASK"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> contract.validate("方案A通知规则写通过邮件发送。", "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatThrownBy(() -> contract.validate("方案B通知规则写在工作日时通过邮件发送。", "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void shouldKeepKnownValuesAndUnknownVersionCorrespondenceSeparate() {
        var contract = SourceObjectContract.from(RAW, null, List.of());
        assertThat(contract.guidance()).contains("维修条款", "对应关系未建立", "其他已有明确版本依据");
        assertThatThrownBy(() -> contract.validate("草稿A维修条款写承租方承担日常维护。", "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatCode(() -> contract.validate("维修条款一版写承租方承担日常维护，版本对应待核实。", "sections.TASK"))
                .doesNotThrowAnyException();
        assertThatCode(() -> contract.validate("草稿A押金写为两个月租金。", "sections.TASK")).doesNotThrowAnyException();
    }

    @Test
    void shouldValidateMarkdownColumnsAndNotLetUnknownOtherCellsHideAnAssertion() {
        var contract = SourceObjectContract.from(RAW, null, List.of());
        assertThatThrownBy(() -> contract.validate("| 条款 | 草稿A | 草稿B |\n| 维修条款 | 承租方承担日常维护 | 待核实 |", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatCode(() -> contract.validate("| 条款 | 草稿A | 草稿B |\n| 维修条款 | 版本对应待核实 | 版本对应待核实 |", "sections.OUTPUT"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldUseExplicitMappingOnlyForItsOwnVersionAndAttribute() {
        var contract = SourceObjectContract.from(RAW + "草稿A维修条款写承租方承担日常维护。", null, List.of());
        assertThatCode(() -> contract.validate("草稿A维修条款写承租方承担日常维护。", "sections.TASK")).doesNotThrowAnyException();
        assertThatThrownBy(() -> contract.validate("草稿B维修条款写承租方承担日常维护。", "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatCode(() -> contract.validate("另一房产维修条款要求年度检查。", "sections.TASK")).doesNotThrowAnyException();
    }

    @Test
    void shouldNotUseTestSourcesToEstablishBusinessMapping() {
        var fixture = new PlanningFactCard("T1", PlanningFactCategory.BUSINESS_RULE, PlanningFactOrigin.TEST_SOURCE,
                "src/test/Example.java", "草稿A维修条款写承租方承担日常维护。");
        assertThatThrownBy(() -> SourceObjectContract.from(RAW, null, List.of(fixture))
                .validate("草稿A维修条款写承租方承担日常维护。", "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void shouldNotTreatProhibitionsOrHypotheticalMappingsAsEvidence() {
        for (String text : List.of("不得将草稿A维修条款写为承租方承担日常维护。",
                "如果草稿A维修条款写承租方承担日常维护，需要核对。",
                "例如草稿A维修条款写承租方承担日常维护。")) {
            var contract = SourceObjectContract.from(RAW + text, null, List.of());
            assertThatThrownBy(() -> contract.validate("草稿A维修条款写承租方承担日常维护。", "questions.options.description"))
                    .as(text).isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    @Test
    void shouldAcceptOnlyTheBoundExplicitAnswerForItsOwnVersion() {
        var decisions = ConfirmedDecisionSet.from(List.of(new com.promptoptimizer.enhancement.dto.PlanAnswer(
                "maintenance", "草稿A维修条款的对应内容是什么？", "草稿A维修条款写承租方承担日常维护。")));
        var contract = SourceObjectContract.from(RAW, null, List.of(), decisions.knownDecisions());
        assertThatCode(() -> contract.validate("草稿A维修条款写承租方承担日常维护。", "sections.TASK"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> contract.validate("草稿B维修条款写承租方承担日常维护。", "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }
}
