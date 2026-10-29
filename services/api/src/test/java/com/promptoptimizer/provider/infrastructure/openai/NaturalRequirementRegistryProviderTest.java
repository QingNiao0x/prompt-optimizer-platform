package com.promptoptimizer.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.NaturalProcessing;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 验证49将原要求出处移到原scope调用后，正文仍能补正漏项并通过原稿、组装与全部题答保护。
 * HTTP响应均为合成协议夹具，只证明职责与校验路径，不代表真实准备提取或作品收益已通过。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalRequirementRegistryProviderTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT)); properties.setApiKey("fixture"); properties.setModel("fixture");
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPLICIT", "INHERENT", "UNSPECIFIED"})
    void everyAdvisoryClassificationStillWritesAndReceivesServerIds(String basis) throws Exception {
        var input = input("只阅读资料后等待，不修改文件，也不写说明文档。", null, List.of(), List.of());
        var prepared = preparation(input, basis);
        ((ObjectNode) prepared.path("actions").get(0)).put("timing", "CONDITIONAL").put("condition", "用户提出后续任务时");
        var response = writing(input.rawPrompt(), 1);
        expect(prepared, request -> assertThat(payload(request).has("preparedRequirements")).isFalse());
        expect(response, request -> {
            var data = payload(request);
            assertThat(data.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
            assertThat(data.path("preparedRequirements").get(0).path("reqId").asText()).isEqualTo("r0");
            assertThat(data.path("taskSources").size()).isEqualTo(1);
            assertThat(data.has("protocolRepair")).isFalse();
        });
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).isEqualTo(input.rawPrompt());
        assertThat(draft.completedStages()).containsExactly(NaturalProcessing.Stage.SCOPE_ANALYSIS, NaturalProcessing.Stage.BODY_GENERATION);
        assertThat(requests).hasSize(2);
        server.verify();
    }

    @Test
    void sparseReplacementAndNewRequirementCorrectPreparationWithoutLosingEqualAnswerSources() throws Exception {
        var context = new ContextSnapshot("材料版本待确认。", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "fixture");
        var answers = List.of(new PlanAnswer("budget", "预算是多少？", "不知道"), new PlanAnswer("start", "开始时间？", "不知道"));
        var input = input("请先阅读资料后等待，不写说明文档。", context, answers, List.of());
        var prepared = preparation(input, "EXPLICIT");
        // 来源真实但解释错误，且准备漏了上下文条件；writer必须能修正，不能被scope或旧text强制绑定。
        ((ObjectNode) prepared.path("requirements").get(0)).put("text", "提交阅读说明文档");
        ((ObjectNode) prepared.path("response")).put("description", "阅读说明文档");
        var rows = prepared.withArray("requirements"); rows.remove(1);
        String body = "请先阅读资料后等待，不写说明文档。预算尚未确认，不猜测取值；开始时间尚未确认，不猜测取值；材料版本待确认。";
        var response = writing(body, 3);
        response.put("goal", input.rawPrompt()).put("deliverable", "").put("inputReadiness", "NEEDS_COMPLETION");
        response.withArray("coverage").remove(0);
        response.withArray("requirementChanges").addObject().put("replacesId", "r0")
                .set("requirement", requirement(input.taskSources().get(0)));
        ((ObjectNode) response.withArray("requirementChanges").get(0)).putArray("bodyQuotes").add("请先阅读资料后等待，不写说明文档。");
        var addition = response.withArray("requirementChanges").addObject().putNull("replacesId");
        addition.set("requirement", requirement(input.taskSources().get(1)));
        addition.putArray("bodyQuotes").add("材料版本待确认。");
        response.withArray("openDecisions").addObject().put("issue", "预算和开始时间尚未确认").put("impact", "相关安排仍待确认").put("kind", "USER_INFORMATION");
        expect(prepared, request -> {});
        expect(response, request -> assertThat(payload(request).path("actualAnswers").size()).isEqualTo(2));
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.taskUnderstanding().deliverable()).isEmpty();
        assertThat(draft.taskUnderstanding().goal()).isEqualTo(input.rawPrompt());
        assertThat(draft.taskUnderstanding().requirements()).hasSize(4);
        assertThat(draft.taskUnderstanding().review().inputReadiness()).isEqualTo(NaturalTaskReview.InputReadiness.NEEDS_COMPLETION);
        assertThat(draft.taskUnderstanding().requirements().stream().flatMap(item -> item.evidence().stream()).map(NaturalTaskUnderstanding.Evidence::sourceId))
                .contains("actualAnswers.0", "actualAnswers.1");
        assertThat(draft.optimizedPrompt()).doesNotContain("提交阅读说明文档");
        var originalRegistry = json.treeToValue(prepared, NaturalPromptRequirementRegistry.Preparation.class).resolve(input);
        var normalized = json.treeToValue(response, NaturalPromptReferencedEnhancement.class)
                .normalize(input, new com.promptoptimizer.enhancement.domain.ProviderMetadata("fixture", "fixture", false), originalRegistry);
        assertThat(originalRegistry.requirements().getFirst().text()).isEqualTo("提交阅读说明文档");
        assertThat(normalized.bindings().get(0).changeIndex()).isEqualTo(0);
        assertThat(normalized.bindings().get(3).reqId()).isEqualTo("r3");
        assertThat(normalized.bindings().get(3).changeIndex()).isEqualTo(1);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknownId", "duplicateId", "missingId", "duplicateReplacement", "forgedSource", "sourceAsBody", "oldFullRecord", "inventedId"})
    void invalidReferencesAndCorrectionProtocolsStillFailWithinExistingTwoAttempts(String defect) throws Exception {
        var input = input("请解释缓存。", null, List.of(), List.of());
        var response = writing(input.rawPrompt(), 1);
        switch (defect) {
            case "unknownId" -> ((ObjectNode) response.path("coverage").get(0)).put("reqId", "r999");
            case "duplicateId" -> response.withArray("coverage").add(response.path("coverage").get(0).deepCopy());
            case "missingId" -> response.withArray("coverage").removeAll();
            case "sourceAsBody" -> ((ObjectNode) response.path("coverage").get(0)).putArray("bodyQuotes").add("来自来源但不在正文的句子");
            case "oldFullRecord" -> response.set("taskUnderstanding", json.createObjectNode());
            default -> {
                response.withArray("coverage").removeAll();
                var change = response.withArray("requirementChanges").addObject().put("replacesId", "r0");
                change.set("requirement", requirement(input.taskSources().getFirst())); change.putArray("bodyQuotes").add(input.rawPrompt());
                if (defect.equals("duplicateReplacement")) response.withArray("requirementChanges").add(change.deepCopy());
                if (defect.equals("forgedSource")) ((ObjectNode) change.path("requirement").path("evidence").get(0)).put("quote", "用户没有提供的确认");
                if (defect.equals("inventedId")) change.put("reqId", "model-issued-id");
            }
        }
        expect(preparation(input, "EXPLICIT"), request -> {});
        expect(response, request -> {});
        expect(response, request -> assertThat(payload(request).has("protocolRepair")).isFalse());
        assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
        assertThat(requests).hasSize(3);
        server.verify();
    }

    @Test
    void changingAnAnswerRequirementCannotDropItsOriginalIndependentSource() throws Exception {
        var input = input("请整理后续安排。", null, List.of(new PlanAnswer("a", "预算？", "未知"), new PlanAnswer("b", "时间？", "未知")), List.of());
        var response = writing("请整理后续安排；预算和时间保持未知。", 3);
        response.withArray("coverage").remove(1);
        var change = response.withArray("requirementChanges").addObject().put("replacesId", "r1");
        change.set("requirement", requirement(input.taskSources().getFirst())); change.putArray("bodyQuotes").add("请整理后续安排");
        expect(preparation(input, "EXPLICIT"), request -> {}); expect(response, request -> {}); expect(response, request -> {
            String system = request.path("messages").get(0).path("content").asText();
            assertThat(system).contains("requirementChanges.requirement.evidence", "actualAnswers.0");
            assertThat(payload(request).has("protocolRepair")).isFalse();
        });
        assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).isInstanceOf(NaturalTaskReview.MissingSources.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"extraCoverage", "forgedQuote", "missingAnswer", "modelId"})
    void preparationCannotInventSourcesOrClaimFinalBodyCoverage(String defect) throws Exception {
        var input = input("请解释缓存。", null, List.of(new PlanAnswer("a", "预算？", "未知")), List.of());
        var prepared = preparation(input, "EXPLICIT");
        if (defect.equals("extraCoverage")) prepared.putArray("coverage");
        if (defect.equals("forgedQuote")) ((ObjectNode) prepared.path("requirements").get(0).path("evidence").get(0)).put("quote", "不存在的输入确认");
        if (defect.equals("missingAnswer")) prepared.withArray("requirements").remove(1);
        if (defect.equals("modelId")) ((ObjectNode) prepared.path("requirements").get(0)).put("reqId", "model-issued-id");
        expect(prepared, request -> {}); expect(prepared, request -> {
            if (defect.equals("missingAnswer")) assertThat(request.path("messages").get(0).path("content").asText())
                    .contains("准备requirements.evidence", "actualAnswers.0").doesNotContain("三个review数组");
        });
        assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
        assertThat(requests).hasSize(2);
        server.verify();
    }

    @Test
    void originalApplicationSafetyStillRejectsSensitiveBodyAfterSourceNormalization() throws Exception {
        var input = input("请解释缓存。", null, List.of(), List.of());
        var response = writing("请解释缓存。" + "sk-" + "A".repeat(40), 1);
        response.put("goal", input.rawPrompt());
        ((ObjectNode) response.path("coverage").get(0)).putArray("bodyQuotes").add("请解释缓存。");
        expect(preparation(input, "EXPLICIT"), request -> {}); expect(response, request -> {}); expect(response, request -> {});
        assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).hasMessage("自然正文包含疑似敏感凭据");
        server.verify();
    }

    @Test
    void assembledBodyGuardStillRunsAfterRawQuotesPass() throws Exception {
        var input = input("请阅读资料后等待，不得删除文件。", null, List.of(), List.of("不得删除文件。"));
        var response = writing("请阅读资料后等待。\n不得删除文件。\n  不得删除文件。", 1);
        ((ObjectNode) response.path("coverage").get(0)).putArray("bodyQuotes").add("  不得删除文件。");
        expect(preparation(input, "EXPLICIT"), request -> {}); expect(response, request -> {}); expect(response, request -> {});
        assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).hasMessage("正文覆盖记录越界、重复或缺少对应原句");
        server.verify();
    }

    @Test
    void planRetainsTheFrozen47ProtocolAndNoRegistryInput() throws Exception {
        var input = input("请解释缓存。", null, List.of(), List.of());
        var scope = preparation(input, "EXPLICIT"); scope.remove("requirements");
        var task = new NaturalTaskUnderstanding(input.rawPrompt(), "", List.of(new NaturalTaskUnderstanding.Requirement(input.rawPrompt(),
                List.of(new NaturalTaskUnderstanding.Evidence("rawPrompt", input.rawPrompt())), List.of(NaturalTaskUnderstanding.Dimension.GOAL))), List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, List.of(), List.of(), List.of()));
        var plan = json.createObjectNode().put("summary", "按原任务执行。"); plan.putArray("questions"); plan.set("taskUnderstanding", json.valueToTree(task));
        expect(scope, request -> assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo(NaturalPromptScopedInstructions.PREPARE));
        expect(plan, request -> {
            assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo(NaturalPromptInstructions.planningInstruction("47"));
            assertThat(payload(request).has("preparedRequirements")).isFalse();
        });
        assertThat(provider.plan(input, value -> { value.taskUnderstanding().resolve(input).review().validatePlan(Set.of()); return value; }).questions()).isEmpty();
        server.verify();
    }

    @Test
    void planMissingSourceRetryRetainsTheExactLegacyHint() throws Exception {
        var input = input("请整理后续安排。", null, List.of(new PlanAnswer("budget", "预算？", "未知")), List.of());
        var scope = preparation(input, "EXPLICIT"); scope.remove("requirements");
        var raw = input.taskSources().getFirst();
        var answer = input.taskSources().get(1);
        var firstRequirement = new NaturalTaskUnderstanding.Requirement(raw.text(), List.of(new NaturalTaskUnderstanding.Evidence(raw.id(), raw.text())), List.of(NaturalTaskUnderstanding.Dimension.GOAL));
        var review = new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, List.of(), List.of(), List.of());
        var incomplete = new NaturalTaskUnderstanding(input.rawPrompt(), "", List.of(firstRequirement), List.of(), List.of(), review);
        NaturalTaskReview.MissingSources missing;
        try { incomplete.resolve(input.withStrategyVersion("47")); throw new AssertionError("Expected missing answer source"); }
        catch (NaturalTaskReview.MissingSources failure) { missing = failure; }
        var full = new NaturalTaskUnderstanding(input.rawPrompt(), "", List.of(firstRequirement,
                new NaturalTaskUnderstanding.Requirement(answer.text(), List.of(new NaturalTaskUnderstanding.Evidence(answer.id(), answer.text())), List.of(NaturalTaskUnderstanding.Dimension.CONDITION))), List.of(), List.of(), review);
        var invalid = json.createObjectNode().put("summary", "保持预算未知。"); invalid.putArray("questions"); invalid.set("taskUnderstanding", json.valueToTree(incomplete));
        var valid = invalid.deepCopy(); valid.set("taskUnderstanding", json.valueToTree(full));
        expect(scope, request -> {}); expect(invalid, request -> {});
        expect(valid, request -> assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo(
                NaturalPromptInstructions.planningInstruction("47") + "\n上次响应未满足协议或必要保护。请重新按完整输入生成合法JSON并保留全部要求；每个字段只出现一次，不添加额外内容。" + missing.repairInstruction()));
        provider.plan(input, value -> { value.taskUnderstanding().resolve(input).review().validatePlan(Set.of()); return value; });
        server.verify();
    }

    private NaturalPromptInput input(String raw, ContextSnapshot context, List<PlanAnswer> answers, List<String> permissions) {
        return new NaturalPromptInput(raw, context, answers, List.of(), permissions, null).withStrategyVersion("49");
    }

    /** 所有准备要求来源于真实输入目录；测试可显式制造漏项或错解而不伪造出处。 */
    private ObjectNode preparation(NaturalPromptInput input, String basis) {
        var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()));
        var response = json.createObjectNode();
        response.set("actions", json.valueToTree(List.of(Map.of("text", input.rawPrompt(), "timing", "CURRENT", "condition", "", "evidence", evidence))));
        response.set("response", json.valueToTree(Map.of("basis", basis, "description", basis.equals("UNSPECIFIED") ? "" : "原定回应", "evidence", basis.equals("UNSPECIFIED") ? List.of() : evidence)));
        var requirements = response.putArray("requirements"); input.taskSources().forEach(source -> requirements.add(requirement(source)));
        return response;
    }

    private ObjectNode requirement(NaturalTaskSource source) {
        var value = json.createObjectNode().put("text", source.text()); value.putArray("dimensions").add("GOAL");
        value.putArray("evidence").addObject().put("sourceId", source.id()).put("quote", source.text()); return value;
    }

    /** 正文响应不重复源要求；只提供服务端ID对应的实际正文片段。 */
    private ObjectNode writing(String body, int preparedCount) {
        var value = json.createObjectNode().put("optimizedPrompt", body).put("framework", "APE").put("goal", body).put("deliverable", "").put("inputReadiness", "SUFFICIENT");
        var coverage = value.putArray("coverage");
        for (int index = 0; index < preparedCount; index++) coverage.addObject().put("reqId", "r" + index).putArray("bodyQuotes").add(body);
        value.putArray("requirementChanges"); value.putArray("additions"); value.putArray("openDecisions"); return value;
    }

    /** 直接调用原应用安全/权限组装并分别核对原稿与组装稿，避免只测新适配器。 */
    private NaturalPromptDraft checked(NaturalPromptDraft value, NaturalPromptInput input) {
        var task = value.taskUnderstanding().resolve(input);
        try {
            var method = NaturalEnhancementService.class.getDeclaredMethod("validateBody", NaturalPromptDraft.class, List.class, String.class); method.setAccessible(true);
            String assembled = (String) method.invoke(new NaturalEnhancementService(null, null, null, null, null, null), value, input.mandatoryRules(), input.strategyVersion());
            task.review().validateEnhancement(task, value.optimizedPrompt().strip()); task.review().validateEnhancement(task, assembled);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        return value;
    }

    private JsonNode payload(JsonNode request) throws java.io.IOException {
        String user = request.path("messages").get(1).path("content").asText(); return json.readTree(user.substring(user.indexOf('{')));
    }

    /** 所有响应由Mock消费，请求列表只保存无认证body供协议断言。 */
    private void expect(ObjectNode response, CheckedRequest check) throws Exception {
        String envelope = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", json.writeValueAsString(response))))));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> { var body = json.readTree(((MockClientHttpRequest) request).getBodyAsString()); requests.add(body); check.accept(body); })
                .andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }

    @FunctionalInterface private interface CheckedRequest { void accept(JsonNode value) throws java.io.IOException; }
}
