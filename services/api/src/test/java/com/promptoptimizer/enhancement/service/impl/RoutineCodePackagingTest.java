package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.domain.*;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放413完整提问：纯包装选择交给实现者，重要未知、候选中的新条件和用户选择继续保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class RoutineCodePackagingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PlanQuestionFilter filter = new PlanQuestionFilter();
    private static final String RAW = "使用requests库爬取网页标题。";

    @Test
    void actualPlanningReplayOnlyDropsTheRoutinePackagingQuestion() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/candidate39-planning-packaging-replay.json")) {
            var fixture = mapper.readTree(input);
            String rawResponse = fixture.path("modelResponseRaw").asText();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawResponse.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo(fixture.path("modelResponseSha256").asText());
            var questions = mapper.convertValue(mapper.readTree(rawResponse).path("questions"),
                    new TypeReference<List<PlanQuestion>>() { });
            assertThat(filter.filter(questions, input(fixture.path("rawPrompt").asText())))
                    .containsExactly(questions.get(0), questions.get(1));
            assertThat(questions).extracting(PlanQuestion::id)
                    .containsExactly("target_pages", "title_definition", "delivery_form");
            assertThat(fixture.path("modelResponseRaw").asText()).isEqualTo(rawResponse);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void theRealPlanningServiceRegistersTwoQuestionsAndUpdatesItsSummary(boolean prepareContext) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/candidate39-planning-packaging-replay.json")) {
            var fixture = mapper.readTree(input);
            String originalInput = fixture.path("providerInputRaw").asText();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(originalInput.getBytes(StandardCharsets.UTF_8))))
                    .isEqualTo(fixture.path("providerInputSha256").asText());
            var original = mapper.readTree(fixture.path("modelResponseRaw").asText());
            var questions = mapper.convertValue(original.path("questions"), new TypeReference<List<PlanQuestion>>() { });
            var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-10-18T00:00:00Z"), java.time.ZoneOffset.UTC);
            var sessions = new PlanningSessionServiceImpl(new InMemoryPlanningSessionStore(clock),
                    request -> new com.promptoptimizer.context.domain.ContextSnapshot("", List.of(), List.of(), List.of(),
                            List.of(), List.of(), List.of(), "EMPTY"),
                    new com.promptoptimizer.policy.service.impl.ProtectedContextFilterImpl(),
                    com.promptoptimizer.identity.support.TestActors.currentActor(), clock);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var service = new OptimizationPlanningServiceImpl(request -> {
                calls.incrementAndGet();
                assertThat(request.contextDescription()).isEmpty();
                assertThat(request.knownDecisions()).isEmpty();
                assertThat(request.sourceObjectGuidance()).isEmpty();
                return new com.promptoptimizer.provider.domain.PlanningProviderResponse(original.path("summary").asText(),
                        questions, "mock", "retained413", true);
            }, new com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl(), sessions, clock);
            com.promptoptimizer.enhancement.dto.PlanningContextReference reference = null;
            if (prepareContext) {
                var prepared = sessions.prepareContext(new com.promptoptimizer.context.dto.PlanningContextRequest(RAW,
                        new com.promptoptimizer.context.dto.ContextAnalysisRequest("", List.of()),
                        com.promptoptimizer.enhancement.dto.PermissionPolicyInput.empty()));
                reference = new com.promptoptimizer.enhancement.dto.PlanningContextReference(prepared.contextId(), prepared.version());
            }
            var plan = service.plan(new com.promptoptimizer.enhancement.dto.OptimizationPlanRequest(RAW, "", List.of(), reference));
            assertThat(calls).hasValue(1);
            assertThat(plan.questions()).extracting(PlanQuestion::id).containsExactly("target_pages", "title_definition");
            assertThat(plan.summary()).contains("2 项未决事项").doesNotContain("交付形态尚未明确");
            assertThat(plan.planId()).isNotNull();
            // 旧无准备请求允许空引用；工作台准备过空上下文时继续返回同一服务端绑定引用。
            assertThat(plan.planningContext()).isEqualTo(reference);
            // 剩余问题原有答案不被改写；推荐状态仍由既有事实对齐器处理，不当作用户已确认。
            assertThat(plan.questions().get(1).options()).extracting(PlanOption::answer)
                    .containsExactlyElementsOf(questions.get(1).options().stream().map(PlanOption::answer).toList());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "编写Python函数提取网页标题。", "实现Java列表去重功能。", "用TypeScript实现文本去重函数。"
    })
    void newImplementationTasksCanDelegatePurePackaging(String raw) {
        assertThat(filter.filter(List.of(packaging()), input(raw))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请先让我选择交付形态。", "交付方式由我决定。", "脚本还是函数需要先向我确认。",
            "交付形态尚未确定。", "对接现有接口，保持兼容。", "需要发布为公开SDK。",
            "本次只给设计方案，不交付代码。", "作为现有项目的插件交付。",
            "不得输出URL。", "只返回标题，不打印。", "以后再确定交付形态。", "除非用户同意，否则不运行。"
    })
    void userReservedChoicesAndExistingContractsRemainQuestions(String addition) {
        var question = packaging();
        assertThat(filter.filter(List.of(question), input(RAW + addition))).containsExactly(question);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "同时需要确认生产部署权限。", "新增保存期限30天。", "只允许内网运行。",
            "是否同时支持失败重试？", "患者数据需获得授权。", "不提供函数调用方式。"
    })
    void newDecisionInAnyQuestionFieldKeepsTheWholeQuestion(String condition) {
        var question = packaging();
        var first = question.options().getFirst();
        for (int field = 0; field < 6; field++) {
            var changedOption = new PlanOption(first.id(), first.label() + (field == 1 ? condition : ""),
                    first.description() + (field == 2 ? condition : ""), first.answer() + (field == 3 ? condition : ""),
                    field == 4, field == 4 ? condition : "");
            var changed = new PlanQuestion(question.id(), question.question(),
                    question.hint() + (field == 0 ? condition : ""), question.type(),
                    List.of(changedOption, question.options().getLast()), field == 5 ? List.of(condition) : List.of(), true);
            assertThat(filter.filter(List.of(changed), input(RAW))).as("field %s: %s", field, condition).containsExactly(changed);
        }
    }

    @Test
    void anIndependentBusinessQuestionOrUnknownPackagingOptionIsNeverRemoved() {
        var question = packaging();
        var business = new PlanQuestion(question.id(), "是否允许输出患者身份信息？", question.hint(), question.type(),
                question.options(), List.of(), true);
        var unknown = new PlanQuestion(question.id(), question.question(), question.hint(), question.type(),
                List.of(new PlanOption("service", "常驻服务", "自动处理外部请求", "交付对外服务", false, "")), List.of(), true);
        assertThat(filter.filter(List.of(business, unknown), input(RAW))).containsExactly(business, unknown);
    }

    @Test
    void missingOptionScopeOrAChoiceInMaterialsIsNotGuessedAway() {
        var question = packaging();
        var freeText = new PlanQuestion(question.id(), question.question(), "", PlanQuestionType.FREE_TEXT, List.of(), List.of(), true);
        assertThat(filter.filter(List.of(freeText), input(RAW))).containsExactly(freeText);
        assertThat(filter.filter(List.of(question), new PlanningProviderRequest(RAW,
                "交付方式存在冲突，必须由用户决定。", List.of()))).containsExactly(question);
        var digest = new PlanningContextDigest("", List.of(), List.of(), List.of(), List.of(), "COMPLETE", 1, List.of());
        assertThat(filter.filter(List.of(question), new PlanningProviderRequest(RAW, "", List.of(), digest))).containsExactly(question);
    }

    @Test
    void writingAndResearchDeliverablesKeepTheirFormatChoices() {
        var question = packaging();
        assertThat(filter.filter(List.of(question), input("比较脚本、模块和命令行工具三种交付方式，撰写报告。")))
                .containsExactly(question);
        assertThat(filter.filter(List.of(question), input("撰写心血管疾病研究方案。"))).containsExactly(question);
    }

    @Test
    void languageOrOutputSubjectChangesAreIndependentDecisions() {
        var question = packaging();
        for (String answer : List.of("交付Java脚本，并输出网址", "交付函数并返回参数", "交付Python脚本并输出标题")) {
            var changed = new PlanQuestion(question.id(), question.question(), "", question.type(),
                    List.of(new PlanOption("a", "脚本", "", answer, false, ""), question.options().getLast()), List.of(), true);
            assertThat(filter.filter(List.of(changed), input("编写Java函数读取网址并提取网页标题。")))
                    .as(answer).containsExactly(changed);
        }
        var twoLanguages = new PlanQuestion(question.id(), question.question(), "", question.type(),
                List.of(new PlanOption("a", "脚本", "", "交付Java脚本", false, ""),
                        new PlanOption("b", "模块", "", "交付Python模块", false, "")), List.of(), true);
        assertThat(filter.filter(List.of(twoLanguages), input(RAW))).containsExactly(twoLanguages);
    }

    @Test
    void aLanguageImplementationGoalCanUseTheExistingPackagingBoundaryAfterIntentRepair() {
        String raw = "用TypeScript实现文本去重。";
        // 候选四十已修正共同任务身份；包装规则本身不变，仍不能仅凭语言名删除问题。
        assertThat(com.promptoptimizer.template.domain.TaskDeliveryProfile.identify(raw))
                .isEqualTo(com.promptoptimizer.template.domain.TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        assertThat(filter.filter(List.of(packaging()), input(raw))).isEmpty();
        assertThat(filter.filter(List.of(packaging()), input("介绍TypeScript文本去重原理。"))).containsExactly(packaging());
    }

    private PlanningProviderRequest input(String raw) {
        return new PlanningProviderRequest(raw, "", List.of());
    }

    private PlanQuestion packaging() {
        return new PlanQuestion("delivery", "代码采用哪种交付形式？", "选择脚本、函数或模块。", PlanQuestionType.SINGLE_CHOICE,
                List.of(new PlanOption("script", "可直接运行的脚本", "单个脚本", "交付可直接运行的脚本", false, ""),
                        new PlanOption("module", "可复用函数或模块", "可被其他代码调用的函数", "交付可复用函数或模块", false, "")), List.of(), true);
    }
}
