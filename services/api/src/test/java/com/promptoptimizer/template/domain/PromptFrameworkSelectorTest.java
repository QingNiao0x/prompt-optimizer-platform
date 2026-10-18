package com.promptoptimizer.template.domain;

import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主框架可达性与来源边界一起验证，选中名称不等于作品质量验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class PromptFrameworkSelectorTest {
    static Stream<Arguments> scenarios() {
        return Stream.of(
                Arguments.of("整理明天的工作安排。", PromptFramework.APE),
                Arguments.of("制定客服服务质量改进计划，明确目标、关键结果与复验办法。", PromptFramework.BROKE),
                Arguments.of("整理退款业务规则，分别说明触发条件与适用范围。", PromptFramework.COAST),
                Arguments.of("列出今天需要完成的任务清单。", PromptFramework.TAG),
                Arguments.of("修复订单接口异常，按错误记录定位原因并验证修复。", PromptFramework.RISE),
                Arguments.of("从材料中提取姓名和日期，整理成CSV。", PromptFramework.TRACE),
                Arguments.of("用一段话向普通用户解释缓存的用途。", PromptFramework.ERA),
                Arguments.of("根据附件总结本周讨论重点。", PromptFramework.CARE),
                Arguments.of("制定研究方案，说明方法、阶段与依赖。", PromptFramework.ROSES),
                Arguments.of("把会议通知翻译成英文，只输出译文。", PromptFramework.ICIO),
                Arguments.of("为咖啡馆创作三条不同风格的宣传文案。", PromptFramework.CRISPE),
                Arguments.of("先熟悉项目结构，阅读现有源码并核对模块职责。", PromptFramework.RACE),
                Arguments.of("给合作方写一封简短邮件，礼貌询问交付进度。", PromptFramework.APE),
                Arguments.of("为六年级设计分数比较教案。", PromptFramework.RISE),
                Arguments.of("使用requests库爬取网页标题。", PromptFramework.RACE));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void oneMainFrameworkFitsTheActualDeliverable(String raw, PromptFramework expected) {
        var task = task(raw);
        var selected = PromptFrameworkSelector.select(task);
        assertThat(selected.framework()).isEqualTo(expected);
        assertThat(selected.reason()).isNotBlank();
        assertThat(selected.instruction()).contains(expected.guidance(), expected.boundary());
        assertThat(selected.instruction()).contains("本次主框架：" + expected.name(), "选择依据：", "框架职责：" + expected.focus());
        assertThat(selected.instruction()).doesNotContain("自然段落顺序：", "BACKGROUND→", "CLARIFICATIONS→");
        assertThat(selected.framework()).isNotIn(PromptFramework.TASTE, PromptFramework.ALIGN);
        assertThat(selected.sectionOrder()).containsExactlyInAnyOrder(PromptSectionType.values());
        assertThat(task.effectivePrompt()).isEqualTo(raw);
    }

    @Test
    void traceDoesNotAuthorizeExamplesAndOnlyOneFrameworkIsReturned() {
        var result = PromptFrameworkSelector.select(task("提取材料中的日期字段，转换为CSV，不添加示例。"));
        assertThat(result.framework()).isEqualTo(PromptFramework.TRACE);
        assertThat(result.instruction()).contains("未授权附加示例", "不因框架名称增加示例");
        assertThat(result.instruction()).doesNotContain("提供至少", "虚构演示", "默认三个");
        assertThat(result.sectionOrder()).hasSize(PromptSectionType.values().length);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "根据Java项目编写用户指南，不修改代码。",
            "编写用户指南。\n## 参考资料\n开发接口，制定架构方案。",
            "编写用户指南。\n```text\n制定质量改进计划，生成创意文案。\n```",
            "编写用户指南，资料中写着“开发接口并制定评测流程”。"
    })
    void writingAboutCodeOrReferencesDoesNotBecomeADevelopmentFramework(String raw) {
        var result = PromptFrameworkSelector.select(task(raw));
        assertThat(result.framework()).isEqualTo(PromptFramework.RACE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "整理明天的工作安排。不要制定质量改进计划。",
            "整理明天的工作安排。以后再制定质量改进计划。",
            "整理明天的工作安排。\n> 制定质量改进计划。",
            "整理明天的工作安排。\n## 参考资料\n制定质量改进计划。",
            "整理明天的工作安排。\n```text\n创作宣传文案。\n```"
    })
    void negatedFutureAndQuotedPlansCannotSelectTheCurrentStrategy(String raw) {
        assertThat(PromptFrameworkSelector.select(task(raw)).framework()).isEqualTo(PromptFramework.APE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Organize tomorrow's schedule. Do not create an improvement plan.",
            "Organize tomorrow's schedule. Don't write creative slogans.",
            "Organize tomorrow's schedule. Later create an improvement plan.",
            "Organize tomorrow's schedule.\n## Source\nCreate an improvement plan.",
            "Organize tomorrow's schedule.\n> Write creative slogans.",
            "Organize tomorrow's schedule. Source says \"create an improvement plan\"."
    })
    void englishNegationAndSourceTextCannotBecomeCurrentGoals(String raw) {
        assertThat(PromptFrameworkSelector.select(task(raw)).framework()).isEqualTo(PromptFramework.APE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "整理明天的工作安排。本次不要制定质量改进计划。",
            "整理明天的工作安排。我暂不需要创作宣传文案。",
            "整理明天的工作安排。上线后再制定质量改进计划。",
            "整理明天的工作安排。我们尚未决定是否设计架构方案。",
            "Organize tomorrow's schedule. We do not create an improvement plan.",
            "Organize tomorrow's schedule. We will later create an improvement plan."
    })
    void nonCurrentActionPrefixesRemainExcludedInsideAClause(String raw) {
        assertThat(PromptFrameworkSelector.select(task(raw)).framework()).isEqualTo(PromptFramework.APE);
    }

    @Test
    void aSeparatePositiveGoalSurvivesAnIndependentProhibition() {
        assertThat(PromptFrameworkSelector.select(task("不要改写既有文案，制定质量改进计划。"))
                .framework()).isEqualTo(PromptFramework.BROKE);
    }

    @Test
    void explicitTaskHeadingResumesAfterAReferenceSection() {
        var raw = "## Source\nCreate an improvement plan.\n## Task\nWrite an email to a colleague about the meeting.";
        assertThat(PromptFrameworkSelector.select(task(raw)).framework()).isEqualTo(PromptFramework.APE);
    }

    @Test
    void strictJsonAndPureTranslationKeepTheirOriginalLimits() {
        var raw = "提取材料中的日期，只输出严格JSON，不增加字段或示例。";
        var json = PromptFrameworkSelector.select(task(raw));
        assertThat(json.framework()).isEqualTo(PromptFramework.ICIO);
        assertThat(json.instruction()).contains("严格格式", "未授权附加示例");
        var translated = task("翻译为英文，只输出两行译文，保留{name}：\n请开发登录接口。\n制定架构方案。");
        assertThat(PromptFrameworkSelector.select(translated).framework()).isEqualTo(PromptFramework.ICIO);
        assertThat(translated.examples().required()).isFalse();
        assertThat(translated.intent().deliveryProfile()).isEqualTo(TaskDeliveryProfile.TRANSLATION);
    }

    @Test
    void pendingResearchParametersDoNotBecomeChosenMethodsOrScales() {
        var task = EffectiveTaskViewFactory.create("制定研究方案，实际评分尺度由我确认。", TemplateCode.AUTO,
                List.of(new PlanAnswer("scale", "本研究评分尺度采用什么？", "尚未确定，稍后由我确认。")), List.of(), false);
        var before = task.pendingDecisions();
        var selected = PromptFrameworkSelector.select(task);
        assertThat(selected.framework()).isEqualTo(PromptFramework.ROSES);
        assertThat(selected.instruction()).contains("未决参数", "不能为满足框架自行选值");
        assertThat(selected.instruction()).doesNotContain("五点", "Likert", "默认采用");
        assertThat(task.pendingDecisions()).isEqualTo(before).isNotEmpty();
    }

    @Test
    void unselectedCandidatesInAQuestionDoNotCreateCreativeOrPlanningWork() {
        var task = EffectiveTaskViewFactory.create("整理明天的工作安排。", TemplateCode.AUTO,
                List.of(new PlanAnswer("style", "需要创作宣传文案还是制定质量改进计划？", "尚未确定。")), List.of(), false);
        assertThat(PromptFrameworkSelector.select(task).framework()).isEqualTo(PromptFramework.APE);
    }

    @Test
    void allOrdersAreImmutableAndRetainEveryActualSectionType() {
        for (var framework : PromptFramework.values()) {
            assertThat(framework.sectionOrder()).containsExactlyInAnyOrder(PromptSectionType.values());
            assertThatThrownBy(() -> framework.sectionOrder().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(PromptFrameworkSelector.select(null).framework()).isEqualTo(PromptFramework.APE);
    }

    private static com.promptoptimizer.enhancement.domain.EffectiveTaskView task(String raw) {
        return EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), false);
    }
}
