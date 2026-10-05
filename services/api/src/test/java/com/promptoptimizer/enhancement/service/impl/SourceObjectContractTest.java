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
    private static final String RAW = "整理草稿A和草稿B的差异。草稿A押金为两个月，草稿B押金为三个月。"
            + "维修条款一版写承租方承担日常维护，另一版新增全部设施故障费用。";

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
