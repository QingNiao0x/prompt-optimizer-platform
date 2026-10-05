package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事实内容与实验边界已明确时只委派格式选择，专业参数、用户主动确认及缺少依据的情况仍保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class RoutineResearchPresentationTest {
    private static final String RAW = "请为研究生课程作业写一份尚未执行的提示词整理工具对照研究方案。"
            + "信息量匹配对照获得相同已确认事实，但不经历本平台问答过程。";
    private static final String QUESTION = "信息量匹配对照组的已确认事实应如何呈现给下游模型？";
    private static final List<PlanOption> OPTIONS = List.of(
            new PlanOption("inline", "直接并入提示词", "事实作为附加段落写入初始提示词。", "将已确认事实作为附加段落写入初始提示词。", false, ""),
            new PlanOption("list", "单独事实清单", "与原始需求分开呈现事实。", "以单独事实清单提供已确认事实。", false, ""),
            new PlanOption("text", "自然语言叙述", "作为背景信息提供事实。", "将已确认事实整合为自然语言叙述。", false, ""));

    @Test
    void delegatesOnlyTheAlreadyDefinedFactsPresentation() {
        assertThat(RoutineWritingPresentation.delegated(question(QUESTION, "呈现方式可能影响输出。", OPTIONS), RAW)).isTrue();
        assertThat(RoutineWritingPresentation.delegated(question(QUESTION, "呈现方式可能影响输出。", OPTIONS),
                "请写研究方案，信息量匹配的数据内容未确定。" )).isFalse();
    }

    @Test
    void retainsNewBusinessParametersAndAnExplicitUserChoice() {
        assertThat(RoutineWritingPresentation.delegated(question(QUESTION, "还需确定数据授权范围。", OPTIONS), RAW)).isFalse();
        assertThat(RoutineWritingPresentation.delegated(question(QUESTION, "呈现方式可能影响输出。", OPTIONS),
                RAW + "请先让我选择信息量匹配对照组的呈现格式。" )).isFalse();
        var changed = List.of(new PlanOption("list", "单独事实清单", "包含新增字段映射。",
                "以单独事实清单提供已确认事实及新增字段映射。", false, ""));
        assertThat(RoutineWritingPresentation.delegated(question(QUESTION, "", changed), RAW)).isFalse();
        assertThat(RoutineWritingPresentation.delegated(question("信息量匹配对照组应获得哪些已确认事实？", "", OPTIONS), RAW)).isFalse();
    }

    private PlanQuestion question(String text, String hint, List<PlanOption> options) {
        return new PlanQuestion("presentation", text, hint, PlanQuestionType.SINGLE_CHOICE, options, List.of(), true);
    }
}
