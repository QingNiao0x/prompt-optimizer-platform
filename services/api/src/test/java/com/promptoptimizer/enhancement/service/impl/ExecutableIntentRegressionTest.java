package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.infrastructure.MockPromptEnhancementProvider;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 从 Mock 和正式组装入口固定角色变元提示词、已知信息重问的用户反例。
 * 不通过臆造分析对象或删除独立未决条件来制造可执行结果。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ExecutableIntentRegressionTest {
    static final String ROLE = """
            你是一名股票投资专家，一个用数据说话的投资分析搭档。覆盖 A 股、港股、美股三大市场，从实时行情到财报解读、从行业研究到持仓跟踪，帮你把模糊的投资疑问拆解成可判断的数据和逻辑。我不喊单、不预测点位、不替你做决策——我给你的永远是看多和看空各自的关键变量，决策权在你手里。
            工作流程：
            问题分类：判断是行情查询、深度分析、决策辅助还是事件追踪
            数据取数：拉取最新行情、财报、资金流向、研报评级等硬数据
            逻辑拆解：把投资疑问拆成看多变量与看空变量，不模糊不骑墙
            风险标注：每个亮点背后找对应风险点，每个数字标注来源和报告期次
            结论输出：结论先行+数据支撑+风险提示，跨市场统一财年口径和币种
            """;
    private final PromptTemplate template = new PromptTemplate(TemplateCode.GENERAL,
            "按原定任务交付可使用的结果。", "内容完整、规则保持。", "提供相关示例。");

    @ParameterizedTest
    @ValueSource(strings = {
            "将上述角色设定与工作流程整理为一份可直接交付给下游执行者的提示词，用于驱动股票投资分析助手的行为。",
            "编写股票投资分析助手的系统提示词。",
            "Create a reusable prompt for a stock analysis assistant."
    })
    void rejectsTurningRoleExecutionIntoWritingAnotherPrompt(String task) {
        assertThatThrownBy(() -> assemble(ROLE, task, "交付分析结论及数据依据。", List.of(), ""))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void rejectsTheScreenshotOutputEvenWhenTheTaskIsCorrect() {
        assertThatThrownBy(() -> assemble(ROLE, "按照用户给定角色和流程回答投资分析问题。",
                "交付一份结构化的提示词文本，包含角色定位、工作流程和覆盖市场。", List.of(), ""))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"用 Python 编写整数排序函数。", "翻译为英文：你好。", "撰写分析报告。"})
    void protectsTheSameExecutionLevelForOtherDeliverables(String raw) {
        assertThatThrownBy(() -> assemble(raw, "生成一份可直接使用的提示词。", "交付提示词文本。", List.of(), ""))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void explicitPromptAuthoringRemainsAValidUserTask() {
        assertThatCode(() -> assemble("请编写一份股票分析助手的提示词。", "编写股票分析助手的提示词。",
                "交付可直接使用的提示词。", List.of(), "")).doesNotThrowAnyException();
    }

    @Test
    void aMentionNegationOrQuotedExampleDoesNotChangeTheTaskLevel() {
        assertThatCode(() -> assemble(ROLE, "执行分析，不要改成编写提示词。\n> 示例：生成一份提示词。",
                "交付分析结论，不输出提示词。", List.of(), "")).doesNotThrowAnyException();
    }

    @Test
    void mockPreservesExecutableInstructionsAndUsesTheAvailableBackground() {
        var response = mock(ROLE, List.of(), "");
        assertThat(content(response, PromptSectionType.TASK)).isEqualTo(ROLE.trim());
        assertThat(content(response, PromptSectionType.BACKGROUND)).contains("A 股", "港股", "美股")
                .doesNotContain("未提供额外上下文");
        assertThat(content(response, PromptSectionType.OUTPUT)).doesNotContain("交付提示词");
    }

    @Test
    void mockDoesNotReaskARegionExplicitlyProvidedByTheContext() {
        var response = mock("撰写死亡率分析报告。", List.of("研究地区具体是哪里？"), "研究地区：广东省");
        assertThat(response.ambiguities()).isEmpty();
        assertThat(content(response, PromptSectionType.BACKGROUND)).contains("广东省");
    }

    @Test
    void assemblyReusesKnownContextFactsButKeepsAnIndependentMethodChoice() {
        var result = assemble("撰写死亡率及 YLL 分析报告。", "撰写死亡率及 YLL 分析报告。", "交付分析报告正文。",
                List.of("研究地区具体是哪里？", "YLL 采用哪份参考寿命表？"), "研究地区：广东省");
        assertThat(result.ambiguities()).containsExactly("YLL 采用哪份参考寿命表？");
        assertThat(result.optimizedPrompt()).doesNotContain("研究地区具体是哪里");
    }

    @Test
    void assemblyDoesNotAskForTheAlreadySpecifiedLanguage() {
        var result = assemble("使用 Python 编写整数排序函数。", "实现整数排序函数。", "交付 Python 函数及必要说明。",
                List.of("应使用哪种编程语言？"), "");
        assertThat(result.ambiguities()).isEmpty();
    }

    @Test
    void routineReportOrganizationDoesNotBecomeARequiredUserDecision() {
        var result = assemble("请撰写季度工作报告。", "撰写季度工作报告。", "交付工作报告正文。",
                List.of("报告的章节如何组织？"), "");
        assertThat(result.ambiguities()).isEmpty();
    }

    @Test
    void explicitUserControlOfTheOutlineIsStillRespected() {
        var result = assemble("请撰写季度工作报告，先让我确认章节结构。", "撰写季度工作报告。", "交付报告正文。",
                List.of("报告的章节如何组织？"), "");
        assertThat(result.ambiguities()).containsExactly("报告的章节如何组织？");
    }

    @ParameterizedTest
    @ValueSource(strings = {"不使用 Python，编程语言待定。", "如果以后采用 Python，再编写函数。", "参考示例使用 Python，本次编程语言待定。"})
    void negationFutureAndExamplesAreNotLanguageChoices(String raw) {
        var result = assemble(raw, "按当前目标实现排序功能。", "交付排序实现。", List.of("应使用哪种编程语言？"), "");
        assertThat(result.ambiguities()).containsExactly("应使用哪种编程语言？");
    }

    @Test
    void conflictingOrNewScopeInformationCannotBeRemovedAsKnown() {
        var result = assemble("撰写科研报告。", "撰写科研报告。", "交付报告正文。",
                List.of("研究地区具体是哪里？", "是否将研究范围扩大到广西？"), "研究地区：广东省\n研究地区：广西壮族自治区");
        assertThat(result.ambiguities()).hasSize(2);
    }

    @Test
    void roleOutputMakesActivationDifferentFromAnsweringAnActualQuestion() {
        var result = assemble(ROLE, "按用户的投资问题进行分析。", "交付结论、依据、看多变量和看空变量。", List.of(), "");
        assertThat(result.optimizedPrompt()).contains("已有具体问题或待处理材料时直接完成任务", "仅收到角色设定时", "不列启动问卷");
    }

    @ParameterizedTest
    @ValueSource(strings = {"请编写一份股票分析助手的提示词。", "你是一名助手，先向我提问再开始。", "你是一名助手，只输出 JSON。", "翻译为英文：你是一名助手。"})
    void roleActivationDoesNotOverrideExplicitAuthoringQuestionsOrStrictOutput(String raw) {
        var result = raw.startsWith("请编写")
                ? assemble(raw, "编写股票分析助手的提示词。", "交付角色提示词。", List.of(), "")
                : assemble(raw, "按用户的明确要求完成任务。", "遵守用户指定的输出要求。", List.of(), "");
        assertThat(result.optimizedPrompt()).doesNotContain("不列启动问卷");
    }

    private EnhancementProviderResponse mock(String raw, List<String> findings, String description) {
        return new MockPromptEnhancementProvider().enhance(new EnhancementProviderRequest(raw, context(description), template,
                findings, List.of("不得编造事实。"), List.of(), EnhancementOptions.defaults(), null));
    }

    private OptimizationResult assemble(String raw, String task, String output, List<String> findings, String description) {
        var sections = List.of(new PromptSection(PromptSectionType.BACKGROUND, "背景", description.isBlank() ? "依据用户目标和已有条件。" : description),
                new PromptSection(PromptSectionType.TASK, "任务", task), new PromptSection(PromptSectionType.OUTPUT, "输出", output),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "保留明确限制，不编造事实。"));
        return new OptimizationResultAssembler().assemble(new EnhancementProviderResponse(sections, "test", "test", false, findings),
                context(description), template, List.of(), List.of(), false, List.of(), false, 1, raw);
    }

    private ContextSnapshot context(String description) {
        return new ContextSnapshot(description, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test");
    }

    private String content(EnhancementProviderResponse response, PromptSectionType type) {
        return response.sections().stream().filter(section -> section.type() == type).findFirst().orElseThrow().content();
    }
}
