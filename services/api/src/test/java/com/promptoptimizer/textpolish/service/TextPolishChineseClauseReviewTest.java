package com.promptoptimizer.textpolish.service;

import com.promptoptimizer.textpolish.domain.TextPolishModels.Check;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 用已观察到的偏差、反向改写和安全改写验证局部提示的适用范围。 */
class TextPolishChineseClauseReviewTest {
    @ParameterizedTest
    @CsvSource({
            "ZH_LIGHT_MODALITY_CHANGED, 西门预计维修，期间行人可从南门进出。, 西门预计维修，这期间行人请从南门进出。",
            "ZH_LIGHT_MODALITY_CHANGED, 申请人可以从系统撤回申请。, 请从系统撤回申请。",
            "ZH_LIGHT_MODALITY_CHANGED, 档案员应在3个工作日内确认材料是否齐全。, 档案员会在3个工作日内确认材料是否齐全。",
            "ZH_LIGHT_TIMING_CHANGED, 复查前，报告不认定货物受损。, 复查完成前，报告不认定货物受损。",
            "ZH_LIGHT_TIMING_CHANGED, 审批前不得寄出原件。, 审批后不得寄出原件。",
            "ZH_LIGHT_RESPONSIBILITY_CHANGED, 前台会通知申请人。, 物业会通知申请人。"
    })
    void pointsToTheChangedClauses(String code, String source, String draft) {
        assertThat(TextPolishChineseClauseReview.find(source, draft)).singleElement().satisfies(check -> {
            assertThat(check.code()).isEqualTo(code);
            assertThat(source).contains(check.before());
            assertThat(draft).contains(check.after());
        });
    }

    @ParameterizedTest
    @CsvSource({
            "西门预计维修，期间行人可从南门进出。, 维修期间，行人可以从南门进出。",
            "换门禁期间行人可从南门进出。, 换门禁期间，行人可由南门通行。",
            "档案员应在3个工作日内确认材料是否齐全。, 档案员须在3个工作日内确认材料是否齐全。",
            "复查前，报告不认定货物受损。, 在复查前，报告不认定货物受损。",
            "前台会通知申请人，之后将补齐文档。, 补齐文档之前，前台会通知申请人。",
            "客服不可以直接退回材料。, 客服不会直接退回材料。",
            "系统关停期间，申请人可先登记信息。系统启动期间，申请人可先登记信息。, 申请人请先登记信息。"
    })
    void leavesEquivalentOrUnalignedClausesForOrdinaryReading(String source, String draft) {
        assertThat(TextPolishChineseClauseReview.find(source, draft)).extracting(Check::code).isEmpty();
    }
}
