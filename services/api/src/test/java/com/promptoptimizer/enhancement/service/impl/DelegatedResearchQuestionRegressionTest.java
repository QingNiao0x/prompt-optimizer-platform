package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
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

    private PlanQuestion question(String id, String value) {
        return new PlanQuestion(id,value,"",PlanQuestionType.FREE_TEXT,List.of(),List.of(),true);
    }
}
