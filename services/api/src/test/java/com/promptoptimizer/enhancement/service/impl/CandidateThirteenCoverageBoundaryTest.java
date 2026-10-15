package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.EffectiveTaskView;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.util.EnumMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归220的复合资料状态，保护限定对象、条件、未来、否定和部分确认；不调用模型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateThirteenCoverageBoundaryTest {
    private static final String NO_DATA = "我没有额外真实数据";

    @ParameterizedTest
    @ValueSource(strings = {
            "当前仅为拟定研究，尚未实施，没有额外真实数据，不确认任何既有参数。",
            "目前只是拟定研究，尚未实施，没有额外真实数据，用户未确认任何既有参数。",
            "用户没有额外真实数据，不确认任何既有参数。",
            "我没有额外真实数据，用户未确认任何既有参数。",
            "本研究尚未实施，用户没有额外真实数据，不确认任何既有参数。"
    })
    void fullCurrentCompoundMaterialStateCoversBothAssemblyPaths(String background) {
        var sections = sections(background);
        assertThat(PlanDecisionIntegrator.coveredRule(sections, NO_DATA)).isTrue();
        assertThat(PlanDecisionIntegrator.covered(sections, requirement(NO_DATA))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "当前仅为拟定研究，尚未实施，没有额外真实数据，但甲院资料除外。",
            "当前仅为拟定研究，尚未实施，仅甲数据源没有额外真实数据，不确认任何既有参数。",
            "当前仅为拟定研究，尚未实施，没有额外真实数据，不确认任何既有参数，除非另行授权。",
            "如果以后确认，当前仅为拟定研究，尚未实施，没有额外真实数据，不确认任何既有参数。",
            "另一研究：\n当前仅为拟定研究，尚未实施，没有额外真实数据，不确认任何既有参数。",
            "> 当前仅为拟定研究，尚未实施，没有额外真实数据，不确认任何既有参数。",
            "示例：\n用户没有额外真实数据，不确认任何既有参数。",
            "用户未说明是否有额外真实数据，不确认任何既有参数。",
            "用户并非没有额外真实数据，不确认任何既有参数。",
            "本研究当获得授权时，用户没有额外真实数据。",
            "本研究仅限甲院，用户没有额外真实数据，不确认任何既有参数。"
    })
    void partialConditionalForeignOrContradictoryStatesDoNotCoverTheGlobalStatement(String background) {
        var sections = sections(background);
        assertThat(PlanDecisionIntegrator.coveredRule(sections, NO_DATA)).isFalse();
        assertThat(PlanDecisionIntegrator.covered(sections, requirement(NO_DATA))).isFalse();
    }

    @Test
    void materialStateDoesNotConfirmMethodParametersOrAnotherDataSource() {
        var sections = sections("当前仅为拟定研究，尚未实施，没有额外真实数据，不确认任何既有参数。");
        assertThat(PlanDecisionIntegrator.coveredRule(sections, "甲院2024年资料不足。")).isFalse();
        assertThat(PlanDecisionIntegrator.coveredRule(sections, "采用年龄标准化。")).isFalse();
        assertThat(PlanDecisionIntegrator.coveredRule(sections, "甲指标采用2020年标准人口。")).isFalse();
        assertThat(PlanDecisionIntegrator.coveredRule(sections, "我没有额外真实数据，但可以使用已授权的公开资料。")).isFalse();
    }

    @Test
    void independentCurrentStateInAnotherParagraphRemainsValid() {
        var sections = sections("没有甲院资料。\n用户没有额外真实数据，不确认任何既有参数。");
        assertThat(PlanDecisionIntegrator.coveredRule(sections, NO_DATA)).isTrue();
        assertThat(PlanDecisionIntegrator.coveredRule(sections, "乙院资料尚未确认。")).isFalse();
    }

    /** 构造当前任务中的资料状态要求；具名对象继续由其完整statement核对。 */
    private EffectiveTaskView.ExecutionRequirement requirement(String statement) {
        return new EffectiveTaskView.ExecutionRequirement("material-state", "资料状态？", "用户说明",
                statement, statement, PromptSectionType.BACKGROUND);
    }

    /** 只在责任段提供状态，示例和引用不能成为本任务的覆盖依据。 */
    private EnumMap<PromptSectionType, PromptSection> sections(String background) {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.BACKGROUND, new PromptSection(PromptSectionType.BACKGROUND, "背景", background));
        sections.put(PromptSectionType.TASK, new PromptSection(PromptSectionType.TASK, "任务", "保留原始研究目标。"));
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", "交付中文方法正文。"));
        sections.put(PromptSectionType.CONSTRAINTS, new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "不得编造事实。"));
        return sections;
    }
}
