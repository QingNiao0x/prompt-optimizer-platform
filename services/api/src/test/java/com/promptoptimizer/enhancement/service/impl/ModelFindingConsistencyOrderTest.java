package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用用户本次原始需求构造离线响应；不是6978请求的原始模型响应回放。 */
class ModelFindingConsistencyOrderTest {
    private static final String RAW = """
            你是一名高级全栈Java开发工程师, 请熟悉一下前后端项目; 这个是使用低代码平台JeecgBoot构建的布病系统, 前端使用vue3和typescript来进行编写的，后端使用的是spring boot3,数据库使用的是pgsq,redis,maven的技术.
             **关键模块识别**：
            - 后端：brucellosis目录为重构后的新模块，oldbrucellosis目录为遗留的旧模块
            - 前端：brucellosis目录为重构后的新模块，brucella目录为遗留的旧模块, 关于患者治疗的新旧模块, 每次修改的需求这两个两个模块都要进行同步
            """;
    private static final ContextSnapshot EMPTY = new ContextSnapshot("", List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), "EMPTY");

    @ParameterizedTest
    @ValueSource(strings = {"项目使用什么后端框架？", "项目采用哪种前端框架？"})
    void knownCurrentTechnologyIsFilteredBeforeAssessmentConsistency(String finding) {
        // 先证明现有规则能够独立解决该问题；本次修复不扩展技术词表或推定新选择。
        assertThat(new PlanQuestionFilter().unresolvedFindings(List.of(finding), RAW, EMPTY, List.of())).isEmpty();
        var result = assemble(RAW, List.of(finding), List.of(), false, false);
        assertThat(result.ambiguities()).isEmpty();
        assertThat(result.optimizedPrompt()).contains("brucellosis", "oldbrucellosis", "brucella", "同步")
                .doesNotContain(finding);
    }

    @Test
    void knownTechnologyInAnotherOrdinaryTaskUsesTheSameOrder() {
        var result = assemble("请熟悉订单项目，前端使用Vue3，后端使用Spring Boot 3。",
                List.of("项目使用什么后端框架？"), List.of(), false, false);
        assertThat(result.ambiguities()).isEmpty();
    }

    @Test
    void exampleToggleDoesNotChangeKnownFactConsistency() {
        assertThat(assemble(RAW, List.of("项目使用什么后端框架？"), List.of(), true, false).ambiguities()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"是否更换项目现有后端框架？", "是否允许删除旧模块？",
            "项目使用的Java版本是多少？", "项目使用什么后端框架？是否需要迁移为其他框架？"})
    void realUnknownsPermissionsAndCompoundChoicesCannotBeFilteredToAvoidConflict(String finding) {
        assertThatThrownBy(() -> assemble(RAW, List.of("项目使用什么后端框架？", finding), List.of(), false, false))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, error ->
                        assertThat(error.getReason()).isEqualTo(ProviderResponseValidationException.Reason.ASSESSMENT_DECISION_CONFLICT));
    }

    @Test
    void serverDiscoveredConflictIsNotTreatedAsAModelClassificationConflict() {
        String conflict = "资料对“患者治疗模块”的适用范围存在冲突，须确认采用哪份资料。";
        var result = assemble(RAW, List.of("项目使用什么后端框架？"), List.of(conflict), false, false);
        assertThat(result.ambiguities()).contains(conflict);
        assertThat(result.optimizedPrompt()).contains("患者治疗模块", "冲突");
    }

    @Test
    void explicitUserDecisionRemainsVisibleAndUnconfirmed() {
        String finding = "是否允许删除旧模块？";
        var result = assemble(RAW, List.of(finding), List.of(), false, true);
        assertThat(result.ambiguities()).contains(finding);
        assertThat(result.optimizedPrompt()).contains("是否允许删除旧模块");
    }

    @Test
    void credentialCheckStillRunsBeforeKnownFactFiltering() {
        assertThatThrownBy(() -> assemble(RAW, List.of("项目使用什么后端框架？password=synthetic-secret-123"),
                List.of(), false, false)).isInstanceOfSatisfying(ProviderResponseValidationException.class, error ->
                        assertThat(error.getReason()).isEqualTo(ProviderResponseValidationException.Reason.SENSITIVE_CONTENT));
    }

    /** 走正式组装入口；所有响应是测试夹具，不调用模型、不访问患者资料。 */
    private OptimizationResult assemble(String raw, List<String> findings, List<String> serverFindings,
                                        boolean examples, boolean userDecision) {
        var kind = userDecision ? TaskEnhancementAssessment.Kind.USER_DECISION : TaskEnhancementAssessment.Kind.EXECUTION_STEP;
        var assessment = new TaskEnhancementAssessment(TaskEnhancementAssessment.Sufficiency.NEEDS_COMPLETION,
                List.of(new TaskEnhancementAssessment.Gap(kind,
                        userDecision ? "删除旧模块的授权未确认" : "需组织项目阅读步骤",
                        userDecision ? "保留实际授权问题" : "按真实源码核对项目结构",
                        "依据本次原始需求，不推定未提供的事实")), TaskEnhancementAssessment.Presentation.STRUCTURED);
        var response = new EnhancementProviderResponse(List.of(
                new PromptSection(PromptSectionType.BACKGROUND, "背景", "用户提供前后端技术栈和新旧模块名称。"),
                new PromptSection(PromptSectionType.TASK, "任务", "熟悉当前授权工作区的前后端项目，按源码核对模块关系。"),
                new PromptSection(PromptSectionType.OUTPUT, "输出", "整理项目理解摘要、模块映射及实际需核实的事项。"),
                new PromptSection(PromptSectionType.CONSTRAINTS, "约束", "后端brucellosis与oldbrucellosis、前端brucellosis与brucella的患者治疗修改须同步；未读源码前不声称已经完成。")),
                "offline-fixture", "offline-fixture", false, findings, List.of(), assessment);
        return new OptimizationResultAssembler().assemble(response, EMPTY,
                new PromptTemplate(TemplateCode.AUTO, "核对当前任务的交付。", "保留原始要求。"),
                serverFindings, List.of(), false, List.of(), 1, raw);
    }
}
