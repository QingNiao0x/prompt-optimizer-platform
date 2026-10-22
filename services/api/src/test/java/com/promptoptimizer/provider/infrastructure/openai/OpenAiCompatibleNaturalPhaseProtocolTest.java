package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallObservation;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 新阶段协议减少模型重复字段后，仍须通过原来源、完整实际题答和正文覆盖保护。
 * HTTP夹具仅验证协议与计量边界，不以模拟正文宣称语义质量提升或服务端身份验收完成。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class OpenAiCompatibleNaturalPhaseProtocolTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private static final String RAW = "请写一条活动通知，50字以内，只输出正文；时间未定就写待定。";
    private static final String BODY = "请写一条50字以内的活动通知，只输出正文；时间待定，地点待定。";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("test-api-key");
        properties.setModel("fixture");
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"21", "22", "23"})
    void validEnhancementUsesOneCallAndNormalizesAllOriginalEvidence(String version) throws Exception {
        expect(1, enhancement(), input(version));
        var observations = new ArrayList<ModelCallObservation>();
        NaturalPromptDraft draft;
        try (var ignored = audit(observations)) {
            draft = provider.enhance(input(version), this::checkDraft);
        }
        assertThat(draft.optimizedPrompt()).isEqualTo(BODY);
        assertThat(draft.taskUnderstanding().requirements()).hasSize(4);
        assertThat(draft.taskUnderstanding().review().requirementCoverage()).hasSize(4);
        assertThat(draft.taskUnderstanding().review().planImpacts()).isEmpty();
        assertThat(draft.taskUnderstanding().openDecisions()).hasSize(1);
        assertThat(draft.taskUnderstanding().openDecisions().getFirst().questionId()).isEmpty();
        assertThat(draft.taskUnderstanding().requirements().getFirst().evidence().getFirst().origin())
                .isEqualTo(NaturalTaskSource.Kind.ORIGINAL_REQUEST);
        assertThat(draft.taskUnderstanding().requirements().subList(2, 4))
                .allSatisfy(requirement -> assertThat(requirement.evidence().getFirst().origin())
                        .isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER));
        assertThat(observations).hasSize(1);
        assertThat(observations.getFirst().usage().totalTokens()).isEqualTo(15L);
        server.verify();
    }

    @Test
    void missingOneOfTwoIdenticalAnswersIsRepairedWithoutMergingTheirSources() throws Exception {
        var bad = enhancement();
        ((ArrayNode) bad.path("task").path("requirements")).remove(3);
        expect(1, bad, input("21"));
        expect(1, enhancement(), input("21"), system -> {
            String instruction = NaturalPromptInstructions.enhancementInstruction("21");
            assertThat(system).startsWith(instruction);
            assertThat(system.substring(instruction.length())).contains("补齐出处及对应正文锚点");
        });
        var observations = new ArrayList<ModelCallObservation>();
        NaturalPromptDraft draft;
        try (var ignored = audit(observations)) {
            draft = provider.enhance(input("21"), this::checkDraft);
        }
        assertThat(draft.taskUnderstanding().requirements().get(2).evidence().getFirst().sourceId()).isEqualTo("actualAnswers.0");
        assertThat(draft.taskUnderstanding().requirements().get(3).evidence().getFirst().sourceId()).isEqualTo("actualAnswers.1");
        assertThat(observations).extracting(ModelCallObservation::attempt).containsExactly(1, 2);
        server.verify();
    }

    @Test
    void additionKeepsItsGapBenefitAndFinalBodyAnchorAfterNormalization() throws Exception {
        var content = enhancement();
        content.put("optimizedPrompt", BODY + "使用简明语言。");
        ((ArrayNode) content.path("task").path("additions")).add(json.readTree("""
                {"text":"使用简明语言","gap":"原文未说明表达方式","benefit":"便于收件人快速阅读","bodyQuotes":["使用简明语言"]}
                """));
        expect(1, content, input("21"));
        var draft = provider.enhance(input("21"), this::checkDraft);
        var addition = draft.taskUnderstanding().additions().getFirst();
        assertThat(addition.gap()).isEqualTo("原文未说明表达方式");
        assertThat(addition.benefit()).isEqualTo("便于收件人快速阅读");
        assertThat(draft.taskUnderstanding().review().additionCoverage()).hasSize(1);
        assertThat(draft.taskUnderstanding().review().additionCoverage().getFirst().index()).isZero();
        assertThatThrownBy(() -> draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), BODY))
                .isInstanceOf(ProviderException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"forgedSource", "inventedQuote", "forgedOrigin", "missingAnswer", "missingBodyQuote",
            "foreignBodyQuote", "planFieldAtRoot", "planFieldInTask", "questionIdInEnhancement", "nonTextQuote"})
    void invalidEnhancementStopsAfterTheExistingSingleProtocolRepair(String defect) throws Exception {
        var bad = enhancement();
        var task = (ObjectNode) bad.path("task");
        var requirements = (ArrayNode) task.path("requirements");
        var goal = (ObjectNode) requirements.get(0);
        var evidence = (ObjectNode) goal.path("evidence").get(0);
        switch (defect) {
            case "forgedSource" -> evidence.put("sourceId", "actualAnswers.999");
            case "inventedQuote" -> evidence.put("quote", "用户已授权公开发送");
            case "forgedOrigin" -> evidence.put("origin", "BOUND_PLAN_ANSWER");
            case "missingAnswer" -> requirements.remove(3);
            case "missingBodyQuote" -> goal.remove("bodyQuotes");
            case "foreignBodyQuote" -> goal.putArray("bodyQuotes").add("另一份正文");
            case "planFieldAtRoot" -> bad.putArray("questions");
            case "planFieldInTask" -> task.putArray("planImpacts");
            case "questionIdInEnhancement" -> ((ObjectNode) task.path("openDecisions").get(0)).put("questionId", "q_forged");
            case "nonTextQuote" -> goal.putArray("bodyQuotes").add(7);
            default -> throw new IllegalArgumentException(defect);
        }
        expect(2, bad, input("21"));
        var observations = new ArrayList<ModelCallObservation>();
        try (var ignored = audit(observations)) {
            assertThatThrownBy(() -> provider.enhance(input("21"), this::checkDraft))
                    .isInstanceOfSatisfying(ProviderException.class,
                            failure -> assertThat(failure.getFailureType()).isEqualTo(ProviderFailureType.INVALID_RESPONSE));
        }
        assertThat(observations).extracting(ModelCallObservation::attempt).containsExactly(1, 2);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"21", "22", "23"})
    void planGroupsMultipleReasonsUnderOneQuestionAndRetainsUnaskedUnknown(String version) throws Exception {
        expect(1, planning(true), input(version));
        var result = provider.plan(input(version), this::checkPlan);
        assertThat(result.questions()).hasSize(1);
        assertThat(result.taskUnderstanding().openDecisions()).hasSize(3);
        assertThat(result.taskUnderstanding().openDecisions().stream().filter(item -> "q_audience".equals(item.questionId())))
                .hasSize(2);
        assertThat(result.taskUnderstanding().openDecisions().stream().filter(item -> item.questionId().isBlank()))
                .hasSize(1);
        assertThat(result.taskUnderstanding().review().planImpacts()).hasSize(1);
        var impact = result.taskUnderstanding().review().planImpacts().getFirst();
        assertThat(impact.whyAsk()).contains("收件人", "对外公开");
        assertThat(impact.answerDifference()).contains("称呼", "披露范围");
        assertThat(result.taskUnderstanding().review().requirementCoverage()).isEmpty();
        assertThat(json.writeValueAsString(result)).doesNotContain("taskUnderstanding", "decisionBasis", "unaskedDecisions");
        server.verify();
    }

    @Test
    void zeroQuestionPlanKeepsAnUnaskedUnknownWithoutInventingBodyCoverage() throws Exception {
        expect(1, planning(false), input("21"));
        var result = provider.plan(input("21"), this::checkPlan);
        assertThat(result.questions()).isEmpty();
        assertThat(result.taskUnderstanding().openDecisions()).hasSize(1);
        assertThat(result.taskUnderstanding().review().planImpacts()).isEmpty();
        assertThat(result.taskUnderstanding().review().requirementCoverage()).isEmpty();
        server.verify();
    }

    @Test
    void missingPlanSourceIsRepairedWithoutRequestingFinalBodyCoverage() throws Exception {
        var bad = planning(true);
        ((ArrayNode) bad.path("task").path("requirements")).remove(3);
        expect(1, bad, input("21"));
        expect(1, planning(true), input("21"), system -> {
            String instruction = NaturalPromptInstructions.planningInstruction("21");
            assertThat(system).startsWith(instruction);
            // 只检查重试增量，原Plan指导本就说明禁止生成正文；不能把这句禁令误判为要求正文。
            assertThat(system.substring(instruction.length()))
                    .contains("task.requirements", "evidence", "actualAnswers.1")
                    .doesNotContain("正文", "bodyQuotes", "Coverage", "coverage", "尚未确定", "q_place", RAW);
        });
        var observations = new ArrayList<ModelCallObservation>();
        PlanningProviderResponse result;
        try (var ignored = audit(observations)) {
            result = provider.plan(input("21"), this::checkPlan);
        }
        assertThat(result.questions()).hasSize(1);
        assertThat(result.taskUnderstanding().requirements()).hasSize(4);
        assertThat(result.taskUnderstanding().requirements().get(3).evidence().getFirst().sourceId())
                .isEqualTo("actualAnswers.1");
        assertThat(result.taskUnderstanding().review().requirementCoverage()).isEmpty();
        assertThat(observations).extracting(ModelCallObservation::attempt).containsExactly(1, 2);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingBasis", "missingImpact", "bodyCoverage", "modelQuestionId", "missingAnswer", "duplicateQuestion"})
    void malformedPlanCannotBorrowEnhancementOrUnboundDecisionFields(String defect) throws Exception {
        var bad = planning(true);
        var question = (ObjectNode) bad.path("questions").get(0);
        var task = (ObjectNode) bad.path("task");
        switch (defect) {
            case "missingBasis" -> question.remove("decisionBasis");
            case "missingImpact" -> ((ObjectNode) question.path("decisionBasis").get(0)).put("answerDifference", "");
            case "bodyCoverage" -> ((ObjectNode) task.path("requirements").get(0)).putArray("bodyQuotes").add("未生成正文");
            case "modelQuestionId" -> ((ObjectNode) task.path("unaskedDecisions").get(0)).put("questionId", "q_audience");
            case "missingAnswer" -> ((ArrayNode) task.path("requirements")).remove(3);
            case "duplicateQuestion" -> ((ArrayNode) bad.path("questions")).add(question.deepCopy());
            default -> throw new IllegalArgumentException(defect);
        }
        expect(2, bad, input("21"));
        assertThatThrownBy(() -> provider.plan(input("21"), this::checkPlan)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"22", "23"})
    void optionalFrameworkCannotRejectAnOtherwiseValidCandidate(String version) throws Exception {
        var body = enhancement();
        body.remove("framework");
        expect(1, body, input(version));
        assertThat(provider.enhance(input(version), this::checkDraft).framework()).isEqualTo(NaturalPromptDraft.Framework.APE);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"22", "23"})
    void unknownTextFrameworkFallsBackWithoutChangingTheBody(String version) throws Exception {
        var body = enhancement();
        body.put("framework", "UNRECOGNIZED_REFERENCE");
        expect(1, body, input(version));
        var draft = provider.enhance(input(version), this::checkDraft);
        assertThat(draft.framework()).isEqualTo(NaturalPromptDraft.Framework.APE);
        assertThat(draft.optimizedPrompt()).isEqualTo(BODY);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"21:missing", "21:unknown", "22:number", "23:number"})
    void frameworkCompatibilityNeverCoercesNonTextMetadata(String example) throws Exception {
        String[] parts = example.split(":");
        var body = enhancement();
        switch (parts[1]) {
            case "missing" -> body.remove("framework");
            case "unknown" -> body.put("framework", "UNRECOGNIZED_REFERENCE");
            case "number" -> body.put("framework", 7);
            default -> throw new IllegalArgumentException(example);
        }
        expect(2, body, input(parts[0]));
        assertThatThrownBy(() -> provider.enhance(input(parts[0]), this::checkDraft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void fourthVersionStillRejectsFinalBodyWithNonemptyPlanImpacts() throws Exception {
        var source = enhancement();
        var requirements = new ArrayList<NaturalTaskUnderstanding.Requirement>();
        var coverage = new ArrayList<NaturalTaskReview.Coverage>();
        for (JsonNode item : source.path("task").path("requirements")) {
            requirements.add(new NaturalTaskUnderstanding.Requirement(item.path("text").asText(),
                    List.of(new NaturalTaskUnderstanding.Evidence(item.path("evidence").get(0).path("sourceId").asText(),
                            item.path("evidence").get(0).path("quote").asText())),
                    List.of(NaturalTaskUnderstanding.Dimension.valueOf(item.path("dimensions").get(0).asText()))));
            coverage.add(new NaturalTaskReview.Coverage(coverage.size(), List.of(item.path("bodyQuotes").get(0).asText())));
        }
        var old = new NaturalTaskUnderstanding("写活动通知", "通知正文", requirements, List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.NEEDS_COMPLETION, coverage, List.of(),
                        List.of(new NaturalTaskReview.PlanImpact("q_audience", "收件人未确定", "不同称呼"))));
        ObjectNode oldWire = json.valueToTree(Map.of("optimizedPrompt", BODY, "framework", "APE", "taskUnderstanding", old));
        expect(2, oldWire, input("4"));
        assertThatThrownBy(() -> provider.enhance(input("4"), this::checkDraft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    /** 同样的答案来自不同实际题干；不能去重为一个确认，助手建议也不能改成确认来源。 */
    private NaturalPromptInput input(String version) {
        return new NaturalPromptInput(RAW,
                new ContextSnapshot("本次资料未提供场地容量。", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                List.of(new PlanAnswer("q_time", "活动时间？", "尚未确定。"), new PlanAnswer("q_place", "活动地点？", "尚未确定。")),
                List.of(new ConversationMessage("assistant", "建议周五开始，用户尚未确认。")), List.of(), null)
                .withStrategyVersion(version);
    }

    /** 与业务回调相同，在规范化后再次验证最终正文；正文组装后的保护仍由既有服务测试覆盖。 */
    private NaturalPromptDraft checkDraft(NaturalPromptDraft draft) {
        draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
        return draft;
    }

    private PlanningProviderResponse checkPlan(PlanningProviderResponse plan) {
        plan.taskUnderstanding().review().validatePlan(plan.questions().stream().map(question -> question.id()).collect(java.util.stream.Collectors.toSet()));
        return plan;
    }

    /** 请求体核对复用原始输入，不让协议简化把实际题答或来源身份移交给模型。 */
    private void expect(int count, ObjectNode content, NaturalPromptInput input) throws Exception {
        expect(count, content, input, system -> { });
    }

    /** 除完整输入外可核对本次system增量，避免修复提示跨阶段或泄露实际题答。 */
    private void expect(int count, ObjectNode content, NaturalPromptInput input, Consumer<String> checkSystem) throws Exception {
        String response = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                Map.of("content", json.writeValueAsString(content)))), "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
        server.expect(ExpectedCount.times(count), requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            checkSystem.accept(sent.path("messages").get(0).path("content").asText());
            String user = sent.path("messages").get(1).path("content").asText();
            var payload = json.readTree(user.substring(user.indexOf('{')));
            assertThat(payload.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
            assertThat(payload.path("context")).isEqualTo(json.valueToTree(input.context()));
            assertThat(payload.path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
            assertThat(payload.path("conversation")).isEqualTo(json.valueToTree(input.conversation()));
            assertThat(payload.path("taskSources")).isEqualTo(json.valueToTree(input.taskSources()));
            assertThat(payload.path("strategyVersion").asText()).isEqualTo(input.strategyVersion());
            assertThat(payload.has("completionSuggestions")).isFalse();
            assertThat(payload.has("fidelityDraft")).isFalse();
        }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private ModelCallAuditContext.Scope audit(List<ModelCallObservation> observations) {
        return ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(new UUID(0, 2123), (tenant, fact) -> observations.add(fact)));
    }

    /** 固定任务原句与正文片段，分别表达真实确认与原定目标；测试不借同一片段伪装不同信息状态。 */
    private ObjectNode enhancement() throws Exception {
        return (ObjectNode) json.readTree("""
                {"optimizedPrompt":"请写一条50字以内的活动通知，只输出正文；时间待定，地点待定。","framework":"APE",
                 "task":{"goal":"写活动通知","deliverable":"通知正文","inputReadiness":"NEEDS_COMPLETION",
                  "requirements":[
                   {"text":"交付通知","dimensions":["GOAL"],"evidence":[{"sourceId":"rawPrompt","quote":"请写一条活动通知"}],"bodyQuotes":["活动通知"]},
                   {"text":"篇幅与格式","dimensions":["FORMAT","LENGTH"],"evidence":[{"sourceId":"rawPrompt","quote":"50字以内，只输出正文"}],"bodyQuotes":["50字以内的活动通知，只输出正文"]},
                   {"text":"时间未知","dimensions":["USER_CONFIRMATION"],"evidence":[{"sourceId":"actualAnswers.0","quote":"尚未确定。"}],"bodyQuotes":["时间待定"]},
                   {"text":"地点未知","dimensions":["USER_CONFIRMATION"],"evidence":[{"sourceId":"actualAnswers.1","quote":"尚未确定。"}],"bodyQuotes":["地点待定"]}],
                  "additions":[],"openDecisions":[{"issue":"时间未知","impact":"通知保留待定","kind":"USER_INFORMATION"}]}}
                """);
    }

    /** 一个问题可以有多个实际交付依据；未提问的未知不冒充实际问题或答案。 */
    private ObjectNode planning(boolean withQuestion) throws Exception {
        var original = enhancement();
        var task = (ObjectNode) original.path("task").deepCopy();
        task.remove(List.of("additions", "openDecisions"));
        task.path("requirements").forEach(item -> ((ObjectNode) item).remove("bodyQuotes"));
        task.set("unaskedDecisions", json.readTree("""
                [{"issue":"场地容量未提供","impact":"不声称已核对容量，其余内容继续起草","kind":"USER_INFORMATION"}]
                """));
        var plan = json.createObjectNode().put("summary", "先确认实际收件人，其余措辞由执行者设计");
        plan.set("task", task);
        plan.set("questions", json.readTree(withQuestion ? """
                [{"id":"q_audience","question":"面向哪些收件人，是否对外公开？","hint":"影响称呼及可披露范围",
                  "type":"FREE_TEXT","options":[],"examples":[],"allowCustomAnswer":true,
                  "decisionBasis":[
                   {"issue":"收件人未确定","kind":"USER_INFORMATION","whyAsk":"收件人属于用户实际情况","answerDifference":"改变称呼"},
                   {"issue":"对外公开未确定","kind":"USER_INFORMATION","whyAsk":"对外公开需要用户确认","answerDifference":"改变披露范围"}]}]
                """ : "[]"));
        return plan;
    }
}
