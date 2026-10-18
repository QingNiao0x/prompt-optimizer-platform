package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖只影响追加投影；条件、对象、否定和未决状态不能因出现相同词语而被认作完整落实。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class AdditionalRuleProjectionTest {
    private static final String RULE = "只输出邮件正文，不超过100个汉字，不添加示例。";
    private static final String MAIL_OUTPUT = "只输出邮件正文，不超过100个汉字。";

    @Test
    void recognizesCompleteRequirementsAcrossExecutionSectionsWithoutJoiningUnboundLengthValues() {
        assertThat(AdditionalRuleProjection.uncovered(sections(MAIL_OUTPUT, "不添加示例。"), RULE)).isEmpty();
        assertThat(AdditionalRuleProjection.uncovered(sections("只输出摘要，不超过100个汉字。", "不添加示例。"), RULE))
                .containsExactly("只输出邮件正文，不超过100个汉字");
    }

    @Test
    void projectsOnlyTheMissingCompleteActionAndKeepsAnUnresolvedLengthWithItsOutputObject() {
        assertThat(AdditionalRuleProjection.uncovered(sections(MAIL_OUTPUT, ""), RULE)).containsExactly("不添加示例。");
        assertThat(AdditionalRuleProjection.uncovered(sections("只输出邮件正文。", "不添加示例。"), RULE))
                .containsExactly("只输出邮件正文，不超过100个汉字");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "如果获批，只输出邮件正文，不超过100个汉字。",
            "以后再只输出邮件正文，不超过100个汉字。",
            "只输出邮件正文，不超过100个汉字，但已获批的附件除外。",
            "仅限已获批邮件；只输出邮件正文，不超过100个汉字。",
            "只输出邮件正文，不超过100个汉字；但仅限已获批邮件。",
            "无需遵守只输出邮件正文，不超过100个汉字。",
            "另一任务只输出邮件正文，不超过100个汉字。",
            "另一封邮件只输出邮件正文，不超过100个汉字。",
            "例如：\n只输出邮件正文，不超过100个汉字。",
            "待确认后：\n只输出邮件正文，不超过100个汉字。",
            "```text\n只输出邮件正文，不超过100个汉字。\n```",
            "> 只输出邮件正文，不超过100个汉字。",
            "\"只输出邮件正文，不超过100个汉字。\""
    })
    void doesNotPromoteConditionalFutureQuotedNegatedOrOtherObjectRequirements(String output) {
        assertThat(AdditionalRuleProjection.uncovered(sections(output, "不添加示例。"), RULE))
                .containsExactly("只输出邮件正文，不超过100个汉字");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "如果批准，只输出邮件正文，不超过100个汉字，不添加示例。",
            "只输出邮件正文，不超过100个汉字，但专家附注除外。",
            "只输出邮件正文，不超过100个汉字，其他附件以后确认。",
            "仅限甲机构，只输出邮件正文，不超过100个汉字，不添加示例。"
    })
    void retainsTheWholeSourceWhenConditionsExceptionsOrPendingStateBindItsParts(String rule) {
        assertThat(AdditionalRuleProjection.uncovered(sections(MAIL_OUTPUT, "不添加示例。"), rule)).containsExactly(rule);
    }

    @Test
    void acceptanceAndExamplesCannotReplaceExecutionRequirements() {
        var sections = sections("", "");
        sections.put(PromptSectionType.ACCEPTANCE, new PromptSection(PromptSectionType.ACCEPTANCE, "验收", RULE));
        sections.put(PromptSectionType.EXAMPLES, new PromptSection(PromptSectionType.EXAMPLES, "示例", RULE));
        assertThat(AdditionalRuleProjection.uncovered(sections, RULE))
                .containsExactly("只输出邮件正文，不超过100个汉字", "不添加示例。");
    }

    @Test
    void JavaDeliveryKeepsUntestedStatusAndOnlySkipsIndependentlyCompleteObligations() {
        var sections = sections("实际编译和运行后才能说明检查通过。", "使用Java21确实存在的标准库API。");
        String guidance = "Java21交付：使用Java21确实存在的标准库API；实际编译和运行后才能说明检查通过；未执行时如实标明未验证。";
        assertThat(AdditionalRuleProjection.uncoveredJavaDelivery(sections, guidance)).containsExactly("未执行时如实标明未验证。");
        assertThat(AdditionalRuleProjection.uncoveredJavaDelivery(sections("实际编译和运行后才能说明检查通过。", "如果获批，使用Java21确实存在的标准库API。"), guidance))
                .containsExactly("使用Java21确实存在的标准库API", "未执行时如实标明未验证。");
    }

    @Test
    void sourceAndBodyAreNeverMutatedWhileMissingRequirementsAreComputed() {
        var sections = sections(MAIL_OUTPUT, "不添加示例。");
        var before = Map.copyOf(sections);
        assertThat(AdditionalRuleProjection.uncovered(sections, RULE)).isEmpty();
        assertThat(sections).isEqualTo(before);
    }

    @Test
    void multipleIndependentActionsOnlyProjectTheMissingCompleteAction() {
        String rule = "不添加示例，不打印输入数据，不记录输入数据。";
        assertThat(AdditionalRuleProjection.uncovered(sections("不添加示例。", "不打印输入数据。"), rule))
                .containsExactly("不记录输入数据。");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "不得删除文件，修改配置。",
            "不修改数据库，删除文件。",
            "禁止删除日志，打印输入数据。",
            "不要输出凭据，记录输入数据。",
            "不得删除文件，不修改配置，打印输入数据。",
            "请不要删除文件,修改配置。",
            "不提供代码，执行迁移。",
            "禁止读取凭据；输出日志。"
    })
    void inheritedNegationNeverTurnsALaterActionIntoAnAffirmativeRequirement(String rule) {
        String first = rule.split("[，,；;]", 2)[0] + "。";
        assertThat(AdditionalRuleProjection.uncovered(sections(first, ""), rule)).containsExactly(rule);
        assertThat(AdditionalRuleProjection.uncovered(sections(rule, ""), rule)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "1. 如果获批，", "另一任务", "其他项目" })
    void ordinaryRuleProjectionCannotBorrowTheFollowingLineFromAnotherScope(String heading) {
        assertThat(AdditionalRuleProjection.uncovered(sections(heading + "\n" + MAIL_OUTPUT, "不添加示例。"), RULE))
                .containsExactly("只输出邮件正文，不超过100个汉字");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "禁止读取凭据，输出日志。", "不得删除文件，输出日志。", "- 请不要读取凭据,输出日志。",
            "禁止读取凭据；输出日志。"
    })
    void aBodyWithInheritedNegationCannotProveAnAffirmativeAction(String output) {
        assertThat(AdditionalRuleProjection.uncovered(sections(output, ""), "输出日志。"))
                .containsExactly("输出日志。");
    }

    @Test
    void anObjectOrModifierCannotDisappearDuringCoverageAndIndependentSentencesKeepTheirOwnDirection() {
        assertThat(AdditionalRuleProjection.uncovered(sections("调试阶段不打印输入数据。", ""), "不打印输入数据。"))
                .containsExactly("不打印输入数据。");
        assertThat(AdditionalRuleProjection.uncovered(sections("不打印摘要的输入数据。", ""), "不打印输入数据。"))
                .containsExactly("不打印输入数据。");
        assertThat(AdditionalRuleProjection.uncovered(sections("禁止读取凭据。输出日志。", ""), "输出日志。"))
                .isEmpty();
    }

    private Map<PromptSectionType, PromptSection> sections(String output, String constraints) {
        Map<PromptSectionType, PromptSection> sections = new EnumMap<>(PromptSectionType.class);
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", output));
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", constraints));
        return sections;
    }
}
