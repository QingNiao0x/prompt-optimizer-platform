package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.context.dto.PlanningContextRequest;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.enhancement.dto.PlanningContextReference;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用用户原题和独立措辞验收项目理解目标；构造的模型输出不冒充现场原响应。
 * 同时保留真正的开发目标、阅读授权和复合问题，防止靠清空问题实现零提问。
 */
class ProjectExplorationPlanningTest {
    static final String RAW = """
            你是一名高级全栈Java开发工程师, 请熟悉一下前后端项目; 这个是使用低代码平台JeecgBoot构建的布病系统, 前端使用vue3和typescript来进行编写的，后端使用的是spring boot3,数据库使用的是pgsq,redis,maven的技术.
             **关键模块识别**：
            - 后端：brucellosis目录为重构后的新模块，oldbrucellosis目录为遗留的旧模块
            - 前端：brucellosis目录为重构后的新模块，brucella目录为遗留的旧模块, 关于患者治疗的新旧模块, 每次修改的需求这两个两个模块都要进行同步
            """;
    private static final String GOAL_QUESTION = "本次需要实现或修改的具体功能是什么？";

    @ParameterizedTest
    @ValueSource(strings = {
            "请熟悉一下前后端项目。这个是使用JeecgBoot构建的系统。",
            "先了解订单工程，后端目录是重构后的模块。以后修改时同步旧模块。",
            "只阅读现有重构后的模块，不要修改代码。",
            "请梳理这个仓库的架构和依赖关系。",
            "请熟悉目前已实现的登录接口和新增的缓存模块。",
            "Read the existing repository and understand its architecture."
    })
    void recognizesExplorationAsAnExplicitCurrentGoal(String raw) {
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
        assertThat(intent.templateCode()).isEqualTo(TemplateCode.GENERAL);
        assertThat(intent.deliveryProfile().name()).isEqualTo("PROJECT_UNDERSTANDING");
        assertThat(intent.status()).isEqualTo(com.promptoptimizer.template.domain.TaskIntent.ResolutionStatus.RAW_GOAL);
        assertThat(intent.engineeringConstraints()).isFalse();
    }

    @Test
    void fullUserPromptKeepsTheExplorationGoalAndDoesNotInferCodeDelivery() {
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, RAW);
        assertThat(intent.deliveryProfile().name()).isEqualTo("PROJECT_UNDERSTANDING");
        assertThat(intent.engineeringConstraints()).isFalse();
        var template = new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, RAW);
        assertThat(template.outputGuidance()).contains("阅读", "核对").doesNotContain("关键代码", "实现方案");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "这个是使用低代码平台JeecgBoot构建的布病系统。",
            "后端：brucellosis目录为重构后的新模块。",
            "系统目前使用已经实现的认证接口。",
            "以后新增功能时同步旧模块。",
            "如果需要修改模块，再确认实施范围。"
    })
    void factsAndFutureConditionsDoNotBecomeCurrentDevelopment(String raw) {
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
        assertThat(intent.templateCode()).isEqualTo(TemplateCode.GENERAL);
        assertThat(intent.engineeringConstraints()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请先熟悉项目，再实现登录功能。",
            "先阅读模块，然后新增退款接口。",
            "请在已经构建的系统上添加缓存功能。",
            "请熟悉项目，另外实现患者随访功能。"
    })
    void mixedTasksRetainActualImplementation(String raw) {
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
        assertThat(intent.templateCode()).isEqualTo(TemplateCode.FEATURE_DEVELOPMENT);
        assertThat(intent.engineeringConstraints()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"请重构现有模块。", "重构现有React前端并迁移到Vue3。"})
    void actualRefactoringRemainsAnAction(String raw) {
        assertThat(TaskIntentResolver.resolve(TemplateCode.AUTO, raw).templateCode()).isEqualTo(TemplateCode.REFACTORING);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void planWithOrWithoutPreparedContextCanProceedWithoutRedundantQuestions(boolean withContext) {
        var sessions = sessions();
        PlanningContextReference reference = null;
        if (withContext) {
            var prepared = sessions.prepareContext(new PlanningContextRequest(RAW,
                    new ContextAnalysisRequest("", List.of(new ContextFileInput("README.md", "现有项目概况", "markdown"))),
                    PermissionPolicyInput.empty()));
            reference = new PlanningContextReference(prepared.contextId(), prepared.version());
        }
        var planning = new OptimizationPlanningServiceImpl(request -> new PlanningProviderResponse(
                "先熟悉项目，但尚未给出具体功能，无法形成开发目标。",
                List.of(question("feature", GOAL_QUESTION), question("framework", "项目使用什么后端框架？")),
                "offline-fixture", "offline-fixture", true), new PromptTemplateRegistryImpl(), sessions);
        var plan = planning.plan(new OptimizationPlanRequest(RAW, "", List.of(), reference));
        assertThat(plan.questions()).isEmpty();
        assertThat(plan.summary()).contains("无需额外确认").doesNotContain("无法", "具体功能");
        assertThat(plan.templateCode()).isEqualTo(TemplateCode.GENERAL);
        assertThat(plan.planId()).isNotBlank();
        assertThat(sessions.confirm(RAW, "", List.of(), new com.promptoptimizer.enhancement.dto.PlanConfirmation(
                plan.planId(), plan.planningContext(), List.of())).bound()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "是否允许读取生产患者资料？",
            "本次需要阅读哪一个工作区的项目？",
            "本次需要实现或修改什么功能，以及是否允许删除旧模块？",
            "两个目录的模块归属相互冲突，本次以哪份材料为准？"
    })
    void importantScopeAuthorizationAndCompoundQuestionsRemain(String text) {
        var plan = planningWithQuestions(RAW, List.of(question("feature", GOAL_QUESTION), question("important", text)));
        assertThat(plan.questions()).extracting(PlanQuestion::question).containsExactly(text);
        assertThat(plan.summary()).contains("熟悉项目").doesNotContain("无法形成开发目标");
    }

    @Test
    void aHintWithAnIndependentDecisionCannotBeDiscardedWithItsHeadline() {
        var compound = new PlanQuestion("compound", GOAL_QUESTION, "还需确认是否授权访问生产数据。",
                PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        assertThat(planningWithQuestions(RAW, List.of(compound)).questions()).hasSize(1);
    }

    @Test
    void unknownDevelopmentGoalStillNeedsClarification() {
        assertThat(planningWithQuestions("请帮我改一下项目。", List.of(question("feature", GOAL_QUESTION))).questions())
                .extracting(PlanQuestion::question).containsExactly(GOAL_QUESTION);
    }

    @Test
    void pureOutOfScopeFindingDoesNotCauseAssessmentConflictButAuthorizationIsKept() {
        var input = new PlanningProviderRequest(RAW, "", List.of(), null, null);
        var filter = new PlanQuestionFilter();
        assertThat(filter.filter(List.of(question("goal", GOAL_QUESTION)), input)).isEmpty();
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test-v1");
        assertThat(filter.unresolvedKnownFindings(List.of(GOAL_QUESTION), RAW, context, List.of(), null)).isEmpty();
        assertThat(filter.unresolvedKnownFindings(List.of("是否允许删除旧模块？"), RAW, context, List.of(), null))
                .containsExactly("是否允许删除旧模块？");
    }

    /** 正式规划服务入口，响应仅为能触发越阶段问题的受控离线夹具。 */
    private com.promptoptimizer.enhancement.domain.OptimizationPlan planningWithQuestions(String raw, List<PlanQuestion> questions) {
        return new OptimizationPlanningServiceImpl(request -> new PlanningProviderResponse(
                "尚未给出具体功能，无法形成开发目标。", questions, "offline-fixture", "offline-fixture", true),
                new PromptTemplateRegistryImpl(), sessions()).plan(new OptimizationPlanRequest(raw, "", List.of()));
    }

    private static PlanQuestion question(String id, String text) {
        return new PlanQuestion(id, text, "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }

    /** 使用已有受控身份和安全过滤器，不访问真实项目或业务数据。 */
    private static PlanningSessionServiceImpl sessions() {
        return new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(), request ->
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test-v1"),
                new ProtectedContextFilterImpl(), TestActors.currentActor(), Clock.systemUTC());
    }
}
