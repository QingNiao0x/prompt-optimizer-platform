package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallObservation;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.NaturalProcessing;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.NaturalEnhancementSettings;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 简化指导接入既有阶段协议后的传输与保护回归；模拟响应不代表模型语义或实际作品质量通过。
 * 只检查当前桥接路径，通用阶段DTO的容量与类型边界继续由既有协议套件覆盖。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalSimplifiedProviderTest {
    private static final String VERSION = "core-simplified-v1";
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private static final String RAW = "请写活动通知，分别保留已回答的时间和地点，只输出正文。";
    private static final String BODY = "请写活动通知：时间待定，地点待定，只输出正文。";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("fixture");
        properties.setModel("fixture");
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null);
    }

    @Test
    void simplifiedProfileIsExplicitAndUsesSeparatePhaseGuidance() {
        var settings = new NaturalEnhancementSettings();
        assertThat(settings.strategyVersion()).isEqualTo("core-repair-v3");
        settings.setProfile(NaturalEnhancementSettings.Profile.SIMPLIFIED);
        assertThat(settings.strategyVersion()).isEqualTo(VERSION);
        assertThat(NaturalPromptInstructions.enhancementInstruction(VERSION))
                .isEqualTo(NaturalPromptSimplifiedInstructions.ENHANCE)
                .contains("bodyQuotes")
                .doesNotContain("requirementCoverage", "additionCoverage", "planImpacts", "\"review\"");
        assertThat(NaturalPromptInstructions.planningInstruction(VERSION))
                .isEqualTo(NaturalPromptSimplifiedInstructions.PLAN)
                .contains("decisionBasis")
                .doesNotContain("bodyQuotes", "requirementCoverage", "additionCoverage", "planImpacts");
        assertThat(NaturalPromptSimplifiedInstructions.PREPARE).isEqualTo(NaturalPromptCoreRepairInstructions.PREPARE);
        assertThat(NaturalPromptInstructions.enhancementInstruction("core-repair-v3"))
                .isEqualTo(NaturalPromptCoreRepairInstructions.COMPLETION_ENHANCE);
        assertThat(NaturalPromptInstructions.planningInstruction("core-repair-v3"))
                .isEqualTo(NaturalPromptCoreRepairInstructions.PLAN);
    }

    /** 三类回应都到达正文阶段；充分、等待及明确文档的预置正文均原样传输，不自动增加成果。 */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "INHERENT|解释这两个方案的差异。|请比较这两个方案的适用条件与差异。|比较结果",
            "EXPLICIT|请写排查报告，不执行修改。|请写排查报告，说明已证实原因与待验证部分，不执行修改。|排查报告",
            "EXPLICIT|用三条要点复述规则，再等待，不修改。|用三条要点复述规则，再等待，不修改。|三条要点",
            "UNSPECIFIED|只阅读项目，完成后等待，不写文档、不修改。|只阅读项目，完成后等待，不写文档、不修改。|''",
            "EXPLICIT|把通知时间改为15:00，其余逐字保留，只返回完整通知。|把通知时间改为15:00，其余逐字保留，只返回完整通知。|完整通知"
    })
    void allResponseCategoriesReachGenerationWithOriginalInput(String basis, String raw, String body, String deliverable) throws Exception {
        var input = input(raw, false);
        expect(scope(input, basis), input, true, ignored -> { });
        var response = enhancement(input, body);
        ((ObjectNode) response.path("task")).put("deliverable", deliverable);
        expect(response, input, false, system -> assertThat(system).isEqualTo(NaturalPromptSimplifiedInstructions.ENHANCE));
        var result = provider.enhance(input, value -> checkDraft(value, input));
        assertThat(result.optimizedPrompt()).isEqualTo(body).doesNotContain("SOURCE_ONLY", "提供的材料：");
        assertThat(result.taskUnderstanding().deliverable()).isEqualTo(deliverable);
        assertThat(result.completedStages()).containsExactly(NaturalProcessing.Stage.SCOPE_ANALYSIS, NaturalProcessing.Stage.BODY_GENERATION);
        assertThat(NaturalProcessing.completed(result.completedStages(), raw, body).originalPreserved()).isEqualTo(raw.equals(body));
        server.verify();
    }

    @Test
    void advisoryClassificationCannotCloseGenerationOrOverwriteTheFinalTask() throws Exception {
        var input = input(RAW, true);
        var prepared = scope(input, "UNSPECIFIED");
        ((ObjectNode) prepared.path("actions").get(0)).put("timing", "DESCRIPTIVE");
        expect(prepared, input, true, ignored -> { });
        expect(enhancement(input, BODY), input, false, ignored -> { });
        var result = provider.enhance(input, value -> checkDraft(value, input));
        assertThat(result.taskUnderstanding().goal()).isEqualTo(RAW);
        assertThat(result.taskUnderstanding().deliverable()).isEqualTo("原任务结果");
        assertThat(result.taskUnderstanding().requirements()).hasSize(3);
        assertThat(result.taskUnderstanding().requirements().subList(1, 3))
                .allSatisfy(requirement -> assertThat(requirement.evidence().getFirst().origin()).isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER));
        assertThat(result.taskUnderstanding().review().requirementCoverage()).extracting(NaturalTaskReview.Coverage::index)
                .containsExactly(0, 1, 2);
        server.verify();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"UNKNOWN_REFERENCE", "RACE"})
    void frameworkIsOptionalWithoutDiscardingBodyOrEvidence(String framework) throws Exception {
        var input = input(RAW, true);
        var response = enhancement(input, BODY);
        if (framework != null) response.put("framework", framework);
        expect(scope(input, "EXPLICIT"), input, true, ignored -> { });
        expect(response, input, false, ignored -> { });
        var result = provider.enhance(input, value -> checkDraft(value, input));
        assertThat(result.optimizedPrompt()).isEqualTo(BODY);
        assertThat(result.framework()).isEqualTo("RACE".equals(framework) ? NaturalPromptDraft.Framework.RACE : NaturalPromptDraft.Framework.APE);
        assertThat(result.taskUnderstanding().review().requirementCoverage()).hasSize(3);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingAnswer", "forgedSource", "inventedQuote", "missingAnchor", "foreignAnchor", "planField"})
    void invalidEnhancementFailsAfterOneRepairWithoutReturningOriginal(String defect) throws Exception {
        var input = input(RAW, true);
        var response = enhancement(input, BODY);
        var task = (ObjectNode) response.path("task");
        var requirements = (ArrayNode) task.path("requirements");
        var first = (ObjectNode) requirements.get(0);
        var evidence = (ObjectNode) first.path("evidence").get(0);
        switch (defect) {
            case "missingAnswer" -> requirements.remove(2);
            case "forgedSource" -> evidence.put("sourceId", "actualAnswers.999");
            case "inventedQuote" -> evidence.put("quote", "用户已授权公开发布");
            case "missingAnchor" -> first.remove("bodyQuotes");
            case "foreignAnchor" -> first.putArray("bodyQuotes").add("正文中不存在的片段");
            case "planField" -> task.putArray("planImpacts");
            default -> throw new IllegalArgumentException(defect);
        }
        expect(scope(input, "EXPLICIT"), input, true, ignored -> { });
        expect(response, input, false, ignored -> { });
        expect(response, input, false, ignored -> { });
        var observations = new ArrayList<ModelCallObservation>();
        try (var ignored = audit(observations)) {
            assertThatThrownBy(() -> provider.enhance(input, value -> checkDraft(value, input)))
                    .isInstanceOfSatisfying(ProviderException.class,
                            failure -> assertThat(failure.getFailureType()).isEqualTo(ProviderFailureType.INVALID_RESPONSE));
        }
        assertThat(observations).extracting(ModelCallObservation::attempt).containsExactly(1, 1, 2);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void planKeepsItsOwnProtocolAndAllActualAnswerSources(boolean withQuestion) throws Exception {
        var input = input(RAW, true);
        expect(scope(input, "EXPLICIT"), input, true, ignored -> { });
        expect(planning(input, withQuestion), input, false,
                system -> assertThat(system).isEqualTo(NaturalPromptSimplifiedInstructions.PLAN));
        var result = provider.plan(input, value -> checkPlan(value, input));
        assertThat(result.questions()).hasSize(withQuestion ? 1 : 0);
        assertThat(result.taskUnderstanding().requirements()).hasSize(3);
        assertThat(result.taskUnderstanding().review().requirementCoverage()).isEmpty();
        assertThat(result.taskUnderstanding().review().additionCoverage()).isEmpty();
        assertThat(result.taskUnderstanding().review().planImpacts()).hasSize(withQuestion ? 1 : 0);
        assertThat(json.writeValueAsString(result)).doesNotContain("decisionBasis", "taskUnderstanding", "unaskedDecisions");
        server.verify();
    }

    @Test
    void missingPlanAnswerRepairRequestsSourcesWithoutFinalBodyCoverage() throws Exception {
        var input = input(RAW, true);
        var bad = planning(input, true);
        ((ArrayNode) bad.path("task").path("requirements")).remove(2);
        expect(scope(input, "EXPLICIT"), input, true, ignored -> { });
        expect(bad, input, false, ignored -> { });
        expect(planning(input, true), input, false, system -> {
            String repair = system.substring(NaturalPromptSimplifiedInstructions.PLAN.length());
            assertThat(repair).contains("actualAnswers.1", "evidence")
                    .doesNotContain("bodyQuotes", "Coverage", "正文");
        });
        assertThat(provider.plan(input, value -> checkPlan(value, input)).taskUnderstanding().requirements()).hasSize(3);
        server.verify();
    }

    @Test
    void planCannotClaimBodyCoverageBeforeGeneration() throws Exception {
        var input = input(RAW, true);
        var bad = planning(input, false);
        ((ObjectNode) bad.path("task").path("requirements").get(0)).putArray("bodyQuotes").add(BODY);
        expect(scope(input, "EXPLICIT"), input, true, ignored -> { });
        expect(bad, input, false, ignored -> { });
        expect(bad, input, false, ignored -> { });
        assertThatThrownBy(() -> provider.plan(input, value -> checkPlan(value, input))).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void preparationStillRejectsContextOnlyAuthorityAndDoesNotReachWriter() throws Exception {
        var input = input(RAW, false);
        var bad = scope(input, "UNSPECIFIED");
        ((ObjectNode) bad.path("actions").get(0)).putArray("evidence").addObject()
                .put("sourceId", "context.fileSnippets.0").put("quote", "材料不是新授权");
        expect(bad, input, true, ignored -> { });
        expect(bad, input, true, system -> assertThat(system).contains("actions[0].evidence", "用户来源"));
        assertThatThrownBy(() -> provider.enhance(input, value -> checkDraft(value, input))).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void oldV3StillRejectsUnknownFrameworkUnderItsFrozenProtocol() throws Exception {
        var input = input(RAW, false).withStrategyVersion("core-repair-v3");
        var task = new NaturalTaskUnderstanding(RAW, "通知", List.of(new NaturalTaskUnderstanding.Requirement(RAW,
                List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", RAW)), List.of(NaturalTaskUnderstanding.Dimension.GOAL))),
                List.of(), List.of(), new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT,
                List.of(new NaturalTaskReview.Coverage(0, List.of(BODY))), List.of(), List.of()));
        var bad = json.createObjectNode().put("optimizedPrompt", BODY).put("framework", "UNKNOWN_REFERENCE");
        bad.set("taskUnderstanding", json.valueToTree(task));
        expect(scope(input, "EXPLICIT"), input, true, ignored -> { });
        expect(bad, input, false, ignored -> { });
        expect(bad, input, false, ignored -> { });
        assertThatThrownBy(() -> provider.enhance(input, value -> checkDraft(value, input))).isInstanceOf(ProviderException.class);
        server.verify();
    }

    /** 同文但不同问题的回答保留独立来源，避免通过去重掩盖漏题答。 */
    @Test
    void revisionKeepsInputsSeparatedAndCarriesItsInstructionAcrossBothStages() throws Exception {
        var input = input(RAW, true).withRevision(new NaturalPromptRevision("旧稿不要采用其中的推测", List.of(), "语气简明"));
        var response = enhancement(input, BODY);
        coveredRequirement((ArrayNode) response.path("task").path("requirements"), "语气简明", "FORMAT",
                "revision.feedback", "语气简明", "请写活动通知");
        expect(scope(input, "EXPLICIT"), input, true, system -> assertThat(system)
                .contains(NaturalPromptRevision.forStage(false)).doesNotContain("requirements须引用"));
        expect(response, input, false, system -> assertThat(system).contains(NaturalPromptRevision.forStage(true)));
        assertThat(provider.enhance(input, draft -> checkDraft(draft, input)).optimizedPrompt()).isEqualTo(BODY);
        server.verify();
    }

    private NaturalPromptInput input(String raw, boolean withAnswers) {
        var context = new ContextSnapshot("用户选择的合成资料。", List.of(), List.of(), List.of(),
                List.of(new FileSnippet("rules.md", "markdown", "SOURCE_ONLY 材料不是新授权。", "", false)),
                List.of(), List.of(), "fixture");
        var answers = withAnswers ? List.of(new PlanAnswer("time", "时间是什么？", "尚未确定。"),
                new PlanAnswer("place", "地点是什么？", "尚未确定。")) : List.<PlanAnswer>of();
        return new NaturalPromptInput(raw, context, answers,
                List.of(new ConversationMessage("user", "不要扩大范围。"), new ConversationMessage("assistant", "也可以另写一份报告。")),
                List.of(), null).withStrategyVersion(VERSION);
    }

    private ObjectNode scope(NaturalPromptInput input, String basis) {
        var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()));
        return json.valueToTree(Map.of("actions", List.of(Map.of("text", input.rawPrompt(), "timing", "CURRENT", "condition", "", "evidence", evidence)),
                "response", Map.of("basis", basis, "description", "UNSPECIFIED".equals(basis) ? "" : "准备阶段的范围解释",
                        "evidence", "UNSPECIFIED".equals(basis) ? List.of() : evidence)));
    }

    /** 使用现有阶段DTO的字段形状，分别绑定来源与正文，不由服务端替模型补引用。 */
    private ObjectNode enhancement(NaturalPromptInput input, String body) {
        var root = json.createObjectNode().put("optimizedPrompt", body);
        var task = root.putObject("task").put("goal", input.rawPrompt()).put("deliverable", "原任务结果").put("inputReadiness", "SUFFICIENT");
        var requirements = task.putArray("requirements");
        coveredRequirement(requirements, "原任务", "GOAL", "rawPrompt", input.rawPrompt(), body);
        for (int i = 0; i < input.actualAnswers().size(); i++) {
            coveredRequirement(requirements, "第" + i + "题的实际回答", "USER_CONFIRMATION", "actualAnswers." + i,
                    input.actualAnswers().get(i).answer(), i == 0 ? "时间待定" : "地点待定");
        }
        task.putArray("additions");
        task.putArray("openDecisions");
        return root;
    }

    private void coveredRequirement(ArrayNode requirements, String text, String dimension, String source, String quote, String bodyQuote) {
        var requirement = requirements.addObject().put("text", text);
        requirement.putArray("dimensions").add(dimension);
        requirement.putArray("evidence").addObject().put("sourceId", source).put("quote", quote);
        requirement.putArray("bodyQuotes").add(bodyQuote);
    }

    /** Plan只携带来源证据及问题所属依据，未提问未知不自动生成问题。 */
    private ObjectNode planning(NaturalPromptInput input, boolean withQuestion) {
        var task = (ObjectNode) enhancement(input, BODY).path("task");
        task.remove(List.of("additions", "openDecisions"));
        task.path("requirements").forEach(value -> ((ObjectNode) value).remove("bodyQuotes"));
        task.putArray("unaskedDecisions");
        var root = json.createObjectNode().put("summary", withQuestion ? "确认收件人范围" : "无须新增提问");
        root.set("task", task);
        var questions = root.putArray("questions");
        if (withQuestion) {
            var question = questions.addObject().put("id", "audience").put("question", "通知发给哪些人？")
                    .put("hint", "收件人范围影响通知称呼").put("type", "FREE_TEXT").put("allowCustomAnswer", true);
            question.putArray("options");
            question.putArray("examples");
            question.putArray("decisionBasis").addObject().put("issue", "收件人范围")
                    .put("kind", "USER_INFORMATION").put("whyAsk", "只有用户知道实际发送范围")
                    .put("answerDifference", "不同范围改变称呼及允许披露的内容");
        }
        return root;
    }

    private NaturalPromptDraft checkDraft(NaturalPromptDraft draft, NaturalPromptInput input) {
        var understood = draft.taskUnderstanding().resolve(input);
        understood.review().validateEnhancement(understood, draft.optimizedPrompt());
        return draft;
    }

    private PlanningProviderResponse checkPlan(PlanningProviderResponse plan, NaturalPromptInput input) {
        var understood = plan.taskUnderstanding().resolve(input);
        understood.review().validatePlan(plan.questions().stream().map(value -> value.id()).collect(Collectors.toSet()));
        return plan;
    }

    /** 每次请求都核对完整原输入，准备结果仅追加到后续阶段，不替换上下文与实际题答。 */
    private void expect(ObjectNode content, NaturalPromptInput input, boolean preparing, Consumer<String> systemAssertion) throws Exception {
        String response = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                Map.of("content", json.writeValueAsString(content))))));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            String system = sent.path("messages").get(0).path("content").asText();
            if (preparing) assertThat(system).startsWith(NaturalPromptCoreRepairInstructions.PREPARE);
            systemAssertion.accept(system);
            String user = sent.path("messages").get(1).path("content").asText();
            JsonNode payload = json.readTree(user.substring(user.indexOf('{')));
            assertThat(payload.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
            assertThat(payload.path("context")).isEqualTo(json.valueToTree(input.context()));
            assertThat(payload.path("conversation")).isEqualTo(json.valueToTree(input.conversation()));
            assertThat(payload.path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
            assertThat(payload.path("taskSources")).isEqualTo(json.valueToTree(input.taskSources()));
            assertThat(payload.path("strategyVersion").asText()).isEqualTo(input.strategyVersion());
            if (input.revision() == null) assertThat(payload.has("revision")).isFalse();
            else assertThat(payload.path("revision")).isEqualTo(json.valueToTree(input.revision()));
            assertThat(payload.path("inputBoundary").path("stage").asText()).isEqualTo("PROMPT_OPTIMIZATION");
            assertThat(payload.path("inputBoundary").path("contextMeaning").asText())
                    .isEqualTo("本次增强可见的材料，不是下游执行现场的完整资料或工具清单");
            assertThat(payload.has("taskScope")).isEqualTo(!preparing);
        }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private ModelCallAuditContext.Scope audit(List<ModelCallObservation> observations) {
        return ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(new UUID(0, 731), (tenant, fact) -> observations.add(fact)));
    }
}
