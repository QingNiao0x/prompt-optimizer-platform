package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.PromptRewriteStrategy;
import com.promptoptimizer.provider.domain.TaskMeaningContract;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查研究评价指导的来源和请求范围，不把策略文本检查当作模型语义或作品验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ResearchEvaluationGuidanceTest {
    private static final String RESEARCH = "为研究生撰写中文研究设计的方法部分，研究Plan问答对提示词质量的影响。"
            + "当前只是拟定研究，尚未实施。两名独立评审盲评，评价准确性、完整性、清晰度与可执行性。";

    @Test void capturedResearchTopicReachesTheExistingEvaluationStepWithItsSource() {
        var task = EffectiveTaskViewFactory.create(RESEARCH, TemplateCode.AUTO, List.of(), List.of());
        var topic = task.meaning().anchors().stream()
                .filter(anchor -> anchor.role() == TaskMeaningContract.Role.RESEARCH_TOPIC).findFirst().orElseThrow();

        assertThat(RESEARCH.substring(topic.start(), topic.end())).isEqualTo("Plan问答对提示词质量的影响");
        assertThat(topic.origin()).isEqualTo(TaskMeaningContract.Origin.EFFECTIVE_USER_INPUT);
        assertThat(PromptRewriteStrategy.forTask(task).instruction())
                .contains("EFFECTIVE_USER_INPUT[20,35)", "TASK 原有评价步骤", "待评对象或产物", "评审所见材料", "各项指标作用的对象")
                .doesNotContain("EFFECTIVE_USER_INPUT[1,4)");
    }

    @Test void actualDownstreamWorkRemainsTheUserSourcedTarget() {
        String raw = "为研究生撰写研究方案，研究Plan问答对下游代码作品质量的影响。只评价最终代码及测试，不评价提示词。";
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of());
        var topic = task.meaning().anchors().stream()
                .filter(anchor -> anchor.role() == TaskMeaningContract.Role.RESEARCH_TOPIC).findFirst().orElseThrow();

        assertThat(topic.text()).isEqualTo("Plan问答对下游代码作品质量的影响");
        assertThat(PromptRewriteStrategy.forTask(task).instruction())
                .contains("EFFECTIVE_USER_INPUT[" + topic.start() + "," + topic.end() + ")", "有效确认", "下游作品")
                .doesNotContain("评价对象固定为提示词", "默认评价提示词质量");
        assertThat(task.effectivePrompt()).isEqualTo(raw);
    }

    @Test void independentTopicsKeepTheirOwnSourceRanges() {
        String raw = "研究提示词清晰度。研究下游作品准确性。两类评价的主次尚未确定。";
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of());
        var topics = task.meaning().anchors().stream()
                .filter(anchor -> anchor.role() == TaskMeaningContract.Role.RESEARCH_TOPIC).toList();
        assertThat(topics).hasSize(2);
        for (var topic : topics) {
            assertThat(raw.substring(topic.start(), topic.end())).isEqualTo(topic.text());
            assertThat(PromptRewriteStrategy.forTask(task).instruction())
                    .contains("EFFECTIVE_USER_INPUT[" + topic.start() + "," + topic.end() + ")");
        }
        assertThat(PromptRewriteStrategy.forTask(task).instruction()).contains("主次关系未定时保持未决");
    }

    @Test void aResearchTopicAloneDoesNotAuthorizeANewRatingWorkflow() {
        var task = EffectiveTaskViewFactory.create("研究数据收集的可行路径，只提出方法建议。", TemplateCode.AUTO,
                List.of(), List.of());
        assertThat(PromptRewriteStrategy.forTask(task).instruction())
                .contains("仅当原任务包含比较、评审或评分", "未要求评价时不增加评分流程");
    }

    @Test void ordinaryTasksAndAnAudienceAloneReceiveNoResearchGuidance() {
        for (String raw : List.of("给同事写一封催办邮件。", "只输出下面英文的中文译文。", "为研究生撰写一封欢迎邮件。")) {
            var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of());
            assertThat(task.meaning().anchors()).noneMatch(anchor -> anchor.role() == TaskMeaningContract.Role.RESEARCH_TOPIC);
            assertThat(PromptRewriteStrategy.forTask(task).instruction()).doesNotContain("本任务评价对象绑定", "EFFECTIVE_USER_INPUT[");
        }
    }

    @Test void legacyPromptEntryDoesNotInventAnchorSources() {
        assertThat(PromptRewriteStrategy.forPrompt(RESEARCH).instruction())
                .doesNotContain("本任务评价对象绑定", "EFFECTIVE_USER_INPUT[");
    }

    @Test void alreadyExplicitEvaluationKeepsItsScopeAndLeavesUnknownParametersOpen() {
        String raw = "研究Plan问答对提示词质量的影响。评审只接收匿名提示词并评价其准确性；下游作品只作辅助评价。"
                + "保留这两句，未知评分尺度待定。";
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of());
        assertThat(task.effectivePrompt()).isEqualTo(raw);
        assertThat(PromptRewriteStrategy.forTask(task).instruction())
                .contains("已明确的表述保留并使用一次", "未确认的实际选值仍保持未决", "原任务要求盲法时");
    }
}
