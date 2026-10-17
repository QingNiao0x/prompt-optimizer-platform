package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PlanOption;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.dto.PlanConfirmation;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 回放350原件并验证工程核查与用户选择的边界，不把未知接口值当作已确认答案。 */
class ExistingContractPlanningTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode FIXTURE = fixture();
    private static final String RAW = FIXTURE.path("rawPrompt").asText();

    @Test
    void original350QuestionsAreDelegatedWithoutInventingAnswers() throws Exception {
        var original = JSON.treeToValue(FIXTURE.path("modelResponse"), CapturedPlan.class);
        var captured = new AtomicReference<PlanningProviderRequest>();
        var sessions = sessions();
        var service = new OptimizationPlanningServiceImpl(input -> {
            captured.set(input);
            return new PlanningProviderResponse(original.summary(), original.questions(), "offline", "fixture350", true);
        }, new PromptTemplateRegistryImpl(), sessions);
        var plan = service.plan(new OptimizationPlanRequest(RAW, "", List.of()));
        assertThat(plan.questions()).isEmpty();
        assertThat(plan.summary()).doesNotContain("需要确认", "凭据的具体形态");
        assertThat(captured.get().knownDecisions()).anySatisfy(decision -> {
            assertThat(decision.kind().name()).isEqualTo("EXISTING_CONTRACT_LOOKUP");
            assertThat(decision.sources()).contains("rawPrompt");
            assertThat(decision.evidence()).contains("核查", "未核实");
        });
        var confirmation = sessions.confirm(RAW, "", List.of(), new PlanConfirmation(
                plan.planId(), plan.planningContext(), List.of()));
        assertThat(confirmation.bound()).isTrue();
        assertThat(captured.get().taskView().confirmedDecisions()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "登录接口接受哪种凭据组合？", "登录成功后接口返回什么？",
            "登录接口使用哪些请求参数？", "登录接口的响应格式是什么？"
    })
    void sameContractDetailsAreLookedUp(String text) {
        assertThat(new PlanQuestionFilter().filter(List.of(question(text)), input(RAW))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "是否允许读取生产用户数据？", "登录成功后接口返回什么，以及是否允许泄露手机号？",
            "支付接口的响应格式是什么？", "登录接口是否新增短信验证？",
            "现有登录契约与接口文档冲突，以哪个为准？", "登录接口超时阈值是多少？",
            "是否更改登录成功后的响应结构？", "本次是否允许数据库迁移？"
    })
    void newObjectsChoicesPermissionsAndConditionsRemain(String text) {
        assertThat(new PlanQuestionFilter().filter(List.of(question(text)), input(RAW)))
                .extracting(PlanQuestion::question).containsExactly(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "还需确认是否允许读取生产数据。", "另外需要选择支付接口返回格式。",
            "新增人脸验证。", "需要决定缓存有效期。", "管理员和普通用户是否采用不同返回内容？"
    })
    void independentDetailsInHintsOptionsOrExamplesCannotBeDiscarded(String detail) {
        var base = "登录成功后接口返回什么？";
        var hint = new PlanQuestion("hint", base, detail, PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        var option = new PlanQuestion("option", base, "", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("choice", "沿用现有规则", detail, detail, false)), List.of(), true);
        var example = new PlanQuestion("example", base, "", PlanQuestionType.FREE_TEXT, List.of(), List.of(detail), true);
        assertThat(new PlanQuestionFilter().filter(List.of(hint), input(RAW))).hasSize(1);
        assertThat(new PlanQuestionFilter().filter(List.of(option), input(RAW))).hasSize(1);
        assertThat(new PlanQuestionFilter().filter(List.of(example), input(RAW))).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请先熟悉项目，再实现登录接口。", "实现登录接口，使用JWT认证。",
            "实现登录接口。不要沿用现有认证契约，实际接口先查现有源码。",
            "实现登录接口。请不要沿用现有认证契约，实际接口先查现有源码。",
            "实现登录接口。以后再沿用现有认证契约，实际接口先查现有源码。",
            "实现登录接口。\n> 沿用现有认证契约，实际接口先查现有源码。",
            "实现登录接口。\n```text\n沿用现有认证契约，实际接口先查现有源码。\n```",
            "实现登录接口。\n参考资料：\n沿用现有认证契约，实际接口先查现有源码。",
            "实现登录接口。资料写着“沿用现有认证契约，实际接口先查现有源码”。",
            "实现登录接口。沿用现有认证契约，实际接口先查现有源码；登录凭据由我选择。",
            "实现登录接口。沿用现有认证契约，实际接口先查现有源码；登录响应结构尚未确定。",
            "实现登录接口。支付接口沿用现有接口契约，实际接口先查现有源码。",
            "实现登录接口。沿用现有认证契约，实际接口先查现有源码；登录响应以后再确认。",
            "实现登录接口和支付接口。沿用现有接口契约，实际接口先查现有源码。"
    })
    void noUnscopedLookupOrImplicitParameterConfirmation(String raw) {
        assertThat(new PlanQuestionFilter().filter(List.of(question("登录成功后接口返回什么？")), input(raw))).hasSize(1);
    }

    @Test
    void commonMechanismAlsoCoversAnotherNamedInterface() {
        var raw = "先熟悉项目，再实现查询接口。沿用现有请求与响应契约，实际接口先查现有源码。";
        assertThat(new PlanQuestionFilter().filter(List.of(question("查询接口使用哪些请求参数？"),
                question("查询接口的响应格式是什么？")), input(raw))).isEmpty();
    }

    @Test
    void requestLookupDoesNotAuthorizeResponseChoice() {
        var raw = "实现登录接口。沿用现有请求参数契约，参数映射先查现有源码。";
        assertThat(new PlanQuestionFilter().filter(List.of(question("登录接口使用哪些请求参数？"),
                question("登录成功后接口返回什么？")), input(raw)))
                .extracting(PlanQuestion::question).containsExactly("登录成功后接口返回什么？");
    }

    @Test
    void responseLookupDoesNotAuthorizeRequestChoice() {
        var raw = "实现登录接口。沿用现有响应结构契约，返回结构先查现有源码。";
        assertThat(new PlanQuestionFilter().filter(List.of(question("登录接口使用哪些请求参数？"),
                question("登录成功后接口返回什么？")), input(raw)))
                .extracting(PlanQuestion::question).containsExactly("登录接口使用哪些请求参数？");
    }

    @Test
    void directEnhancementUsesTheSameBoundaryBeforeAssessmentConsistency() {
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "fixture");
        assertThat(new PlanQuestionFilter().unresolvedKnownFindings(List.of("登录成功后接口返回什么？",
                "是否允许读取生产用户数据？"), RAW, context, List.of(), null))
                .containsExactly("是否允许读取生产用户数据？");
    }

    private static PlanningProviderRequest input(String raw) {
        return new PlanningProviderRequest(raw, "", List.of(), null, null, List.of(), "",
                EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), false));
    }

    private static PlanQuestion question(String text) {
        return new PlanQuestion("contract", text, "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
    }

    /** 保留原件摘要和题目全文；测试只裁决职责，不选任何候选答案。 */
    private record CapturedPlan(String summary, List<PlanQuestion> questions) { }

    private static JsonNode fixture() {
        try (var stream = ExistingContractPlanningTest.class.getResourceAsStream("/fixtures/plan-existing-contract-350.json")) {
            return JSON.readTree(stream);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read immutable trace350 fixture", exception);
        }
    }

    private static PlanningSessionServiceImpl sessions() {
        return new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(), request ->
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "fixture"),
                new ProtectedContextFilterImpl(), TestActors.currentActor(), Clock.systemUTC());
    }
}
