package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 区分已委托的拟议研究实施与真正需要用户确认的事实、授权和新条件。 */
class DelegatedResearchQuestionRegressionTest {
    private static final String RAW = "为研究生撰写研究设计的方法部分。当前只是拟定研究，尚未实施。"
            + "说明随机化、准确性、完整性、清晰度与可执行性评价以及分歧处理。"
            + "另设两组获得相同确认信息的公平对照。";

    @Test void delegatesRequestedDesignWhileKeepingRegionAndPermissionQuestions() {
        var questions = List.of(question("scale","准确性等维度采用何种评分方式？"),
                question("dispute","两名评审评分出现分歧时如何处理？"),
                question("random","随机化在哪个层面实施？"),
                question("parity","公平对照中，两组获得相同确认信息的具体方式是什么？"),
                question("region","真实研究地区是哪里？"),
                question("permission","是否已获准使用真实个人资料？"));
        assertThat(new PlanQuestionFilter().filter(questions, new PlanningProviderRequest(RAW,"",List.of(),null)))
                .extracting(PlanQuestion::id).containsExactly("region","permission");
    }

    @Test void preservesUserReservedScaleAndCompoundAuthorization() {
        var input = new PlanningProviderRequest(RAW + "评分量表由我决定，请先让我确认。","",List.of(),null);
        assertThat(new PlanQuestionFilter().filter(List.of(question("scale","评分量表如何选择？")), input)).hasSize(1);
        assertThat(new PlanQuestionFilter().filter(List.of(question("both","评分方式以及真实个人资料使用授权如何确定？")),
                new PlanningProviderRequest(RAW,"",List.of(),null))).hasSize(1);
    }

    @Test void keepsAnIndependentPermissionDecisionInTheHint() {
        var question = new PlanQuestion("scale", "评分方式如何选择？", "另需确认是否已获准使用真实个人资料。",
                PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        assertThat(new PlanQuestionFilter().filter(List.of(question),
                new PlanningProviderRequest(RAW, "", List.of(), null))).containsExactly(question);
    }

    @Test void checksEveryOptionFieldBeforeDiscardingADelegatedQuestion() {
        var options = List.of(
                new PlanOption("a", "跨机构纳入", "", "", false),
                new PlanOption("a", "五级量表", "另需确认个人资料授权", "", false),
                new PlanOption("a", "五级量表", "", "将其他机构的样本纳入评价", false),
                new PlanOption("a", "五级量表", "", "使用五级量表", true, "仍需确认真实数据使用授权"));
        for (PlanOption option : options) {
            var question = new PlanQuestion("scale", "评分量表如何选择？", "",
                    PlanQuestionType.SINGLE_CHOICE, List.of(option), List.of(), true);
            assertThat(new PlanQuestionFilter().filter(List.of(question),
                    new PlanningProviderRequest(RAW, "", List.of(), null))).containsExactly(question);
        }
    }

    @Test void keepsIndependentDataScopeInExamplesButDelegatesOrdinaryScaleOptions() {
        var scopeQuestion = new PlanQuestion("scope", "评分方式如何选择？", "",
                PlanQuestionType.FREE_TEXT, List.of(), List.of("另需决定缺失记录是否纳入分母"), true);
        var delegatedQuestion = new PlanQuestion("scale", "评分量表如何选择？", "给出拟议方法与适用条件",
                PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("a", "五级量表", "逐维评分", "使用五级量表", true, "各维度便于比较")),
                List.of("准确性从低到高评分"), true);
        assertThat(new PlanQuestionFilter().filter(List.of(scopeQuestion, delegatedQuestion),
                new PlanningProviderRequest(RAW, "", List.of(), null))).containsExactly(scopeQuestion);
    }

    private PlanQuestion question(String id, String value) {
        return new PlanQuestion(id,value,"",PlanQuestionType.FREE_TEXT,List.of(),List.of(),true);
    }
}
