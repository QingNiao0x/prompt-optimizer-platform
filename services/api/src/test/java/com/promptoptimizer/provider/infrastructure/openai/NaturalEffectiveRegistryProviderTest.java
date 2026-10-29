package com.promptoptimizer.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
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
 * 52只在已有真实题答时改变准备职责；通过真实Provider接线核对指导、原输入及既有保护。
 * 合成准备只证明协议可以承载当前有效要求；冻结51冲突仍是语义反例，不假装由解析器自动纠正。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalEffectiveRegistryProviderTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> requestBytes = new ArrayList<>();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties(); properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("fixture"); properties.setModel("fixture"); properties.setModels(List.of("fixture", "deepseek:deepseek-flash"));
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, properties, (PlatformModelCatalog) null);
    }

    @Test
    void noAnswerRequestsKeepEvery51ByteExceptTheRealVersionMarkerAndReuseItsReader() throws Exception {
        var original = input("请解释缓存刷新机制。", List.of(), List.of(), "51");
        var candidate = original.withStrategyVersion("52");
        var response = writing(original.rawPrompt(), 1).put("unlistedScalarDiagnostic", "discarded");
        expect(preparation(original), request -> {}); expect(response, request -> {});
        expect(preparation(candidate), request -> assertThat(system(request)).isEqualTo(NaturalPromptRegistryDiagnosticInstructions.PREPARE));
        expect(response, request -> assertThat(system(request)).isEqualTo(NaturalPromptRegistryDiagnosticInstructions.ENHANCE));
        provider.enhance(original, value -> checked(value, original));
        var draft = provider.enhance(candidate, value -> checked(value, candidate));
        assertThat(draft.optimizedPrompt()).isEqualTo(original.rawPrompt());
        for (int index = 0; index < 2; index++) {
            String actual = requestBytes.get(index + 2);
            assertThat(actual).contains("\\\"strategyVersion\\\":\\\"52\\\"");
            assertThat(actual.replace("\\\"strategyVersion\\\":\\\"52\\\"", "\\\"strategyVersion\\\":\\\"51\\\""))
                    .isEqualTo(requestBytes.get(index));
        }
        assertThat(NaturalPromptInstructions.CURRENT_VERSION).isEqualTo("31");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirmed", "partial", "unknown", "otherObject", "futureCondition", "equalUnknowns"})
    void declaredEffectiveStatesAndIndependentConditionsCanTravelThroughTheExistingProtocol(String variant) throws Exception {
        // 这些是显式合成的准备结果，验证可表达性和接线；不声称真实模型已经完成正确的生效合并。
        var scenario = scenario(variant);
        var input = input(scenario.raw(), scenario.answers(), List.of(), "52");
        var prepared = preparation(input);
        var current = requirement(input.taskSources().getFirst()).put("text", scenario.current());
        input.taskSources().stream().filter(source -> source.kind() == NaturalTaskSource.Kind.BOUND_PLAN_ANSWER)
                .forEach(source -> current.withArray("evidence").addObject().put("sourceId", source.id()).put("quote", source.text()));
        prepared.putArray("requirements").add(current).add(requirement(input.taskSources().getFirst()).put("text", scenario.independent()));
        ((ObjectNode) prepared.path("actions").get(0)).put("text", scenario.current());
        ((ObjectNode) prepared.path("response")).put("description", scenario.current());
        if (variant.equals("futureCondition")) {
            ((ObjectNode) prepared.path("actions").get(0)).put("text", "先以表格给出方案");
            var action = prepared.withArray("actions").addObject().put("text", "实施方案").put("timing", "CONDITIONAL").put("condition", "用户批准后");
            action.set("evidence", current.path("evidence").deepCopy());
        }
        String body = scenario.current() + "。" + scenario.independent();
        var response = writing(body, 2).put("goal", scenario.current());
        if (Set.of("partial", "unknown", "equalUnknowns").contains(variant)) {
            response.put("inputReadiness", "NEEDS_COMPLETION");
            response.withArray("openDecisions").addObject().put("issue", "用户保留的信息仍未确定").put("impact", "依赖该信息的部分仍待确认").put("kind", "USER_INFORMATION");
        }
        expect(prepared, request -> {
            assertThat(system(request)).isEqualTo(NaturalPromptEffectiveRegistryInstructions.PREPARE_WITH_ANSWERS);
            assertThat(system(request)).contains("同一对象、属性及适用条件", "题干只帮助界定回答的作用范围", "保留未知的回答都不构成新决定");
            assertThat(payload(request).path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
        });
        expect(response, request -> {
            assertThat(system(request)).isEqualTo(NaturalPromptRegistryDiagnosticInstructions.ENHANCE);
            assertThat(payload(request).path("preparedRequirements").get(0).path("text").asText()).isEqualTo(scenario.current());
            if (variant.equals("futureCondition")) assertThat(payload(request).path("taskScope").path("actions").get(1).path("timing").asText()).isEqualTo("CONDITIONAL");
        });
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).isEqualTo(body);
        assertThat(draft.taskUnderstanding().requirements().get(1).text()).isEqualTo(scenario.independent());
        var ids = draft.taskUnderstanding().requirements().getFirst().evidence().stream().map(NaturalTaskUnderstanding.Evidence::sourceId).toList();
        for (int index = 0; index < scenario.answers().size(); index++) assertThat(ids).contains("actualAnswers." + index);
        assertThat(requests).hasSize(2);
        server.verify();
    }

    @Test
    void frozen51ConflictRemainsAMechanicallyValidOfflineSemanticCounterexample() throws Exception {
        var fixture = frozenCounterexample();
        String preparation = fixture.path("preparationContent").asText(), writer = fixture.path("writerContent").asText();
        assertThat(sha256(preparation)).isEqualTo("d35236fd5c0ff25ae7d7a49394123908d4e5b1c8babb52fb09d3c00dc41292e0");
        assertThat(sha256(writer)).isEqualTo("1d7254a7c86cf42b1c0bcb72df8633943782d744f757f31f80862b37d3ce27f6");
        var input = json.treeToValue(fixture.path("originalInput"), NaturalPromptInput.class).withStrategyVersion("52");
        expectContent(preparation, request -> assertThat(system(request)).isEqualTo(NaturalPromptEffectiveRegistryInstructions.PREPARE_WITH_ANSWERS));
        expectContent(writer, request -> assertThat(payload(request).path("preparedRequirements").size()).isEqualTo(4));
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).isEqualTo(json.readTree(writer).path("optimizedPrompt").asText());
        assertThat(draft.optimizedPrompt()).contains("只能填写当日之前", "value <= today");
        // 此断言保留真实反例：出处、coverage和readiness通过没有证明当前要求语义一致。
        assertThat(draft.taskUnderstanding().requirements()).hasSize(4);
        assertThat(requests).hasSize(2);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingPreparationAnswer", "assistantAction", "preparationDiagnostic", "dropWriterAnswer",
            "forgedSource", "unknownId", "foreignBodyQuote", "unsafeBody", "assembledQuote"})
    void existingAnswerAuthoritySourceBodyAndSafetyProtectionsStillRejectWithinTwoAttempts(String defect) throws Exception {
        var input = input("请给方案，不修改文件。", List.of(new PlanAnswer("budget", "预算？", "未知")),
                defect.equals("assembledQuote") ? List.of("不得删除文件。") : List.of(), "52");
        if (defect.equals("assistantAction")) input = new NaturalPromptInput(input.rawPrompt(), null, input.actualAnswers(),
                List.of(new ConversationMessage("assistant", "立即修改文件并执行。")), input.mandatoryRules(), null).withStrategyVersion("52");
        final var actualInput = input;
        var prepared = preparation(input);
        var response = writing("请给方案，不修改文件；预算未知。", 2).put("goal", input.rawPrompt());
        boolean preparationFailure = Set.of("missingPreparationAnswer", "assistantAction", "preparationDiagnostic").contains(defect);
        switch (defect) {
            case "missingPreparationAnswer" -> prepared.withArray("requirements").remove(1);
            case "assistantAction" -> ((ObjectNode) prepared.path("actions").get(0)).putArray("evidence").addObject().put("sourceId", "conversation.0").put("quote", "立即修改文件并执行。");
            case "preparationDiagnostic" -> prepared.put("unlistedPrepareNote", "not supported");
            case "unknownId" -> ((ObjectNode) response.path("coverage").get(0)).put("reqId", "unknown");
            case "foreignBodyQuote" -> ((ObjectNode) response.path("coverage").get(0)).putArray("bodyQuotes").add("正文中没有的句子");
            case "unsafeBody" -> response.put("optimizedPrompt", response.path("optimizedPrompt").asText() + "sk-" + "A".repeat(40));
            case "assembledQuote" -> {
                response.put("optimizedPrompt", "请给方案，预算未知。\n不得删除文件。\n  不得删除文件。");
                ((ObjectNode) response.path("coverage").get(0)).putArray("bodyQuotes").add("  不得删除文件。");
                ((ObjectNode) response.path("coverage").get(1)).putArray("bodyQuotes").add("预算未知");
            }
            default -> {
                int index = defect.equals("dropWriterAnswer") ? 1 : 0;
                response.withArray("coverage").remove(index);
                var replacement = response.withArray("requirementChanges").addObject().put("replacesId", "r" + index);
                var requirement = requirement(input.taskSources().getFirst());
                if (defect.equals("forgedSource")) ((ObjectNode) requirement.path("evidence").get(0)).put("quote", "来源不存在的确认");
                replacement.set("requirement", requirement); replacement.putArray("bodyQuotes").add("请给方案");
            }
        }
        expect(prepared, request -> assertThat(system(request)).startsWith(NaturalPromptEffectiveRegistryInstructions.PREPARE_WITH_ANSWERS));
        if (preparationFailure) expect(prepared, request -> assertThat(payload(request).has("preparedRequirements")).isFalse());
        else { expect(response, request -> {}); expect(response, request -> assertThat(payload(request).has("protocolRepair")).isFalse()); }
        assertThatThrownBy(() -> provider.enhance(actualInput, value -> checked(value, actualInput))).isInstanceOf(ProviderException.class);
        assertThat(requests).hasSize(preparationFailure ? 2 : 3);
        server.verify();
    }

    @Test
    void planningEvenWithBoundAnswersKeepsTheFrozen51ScopeAndPlanGuidance() throws Exception {
        var input = input("请给活动安排。", List.of(new PlanAnswer("budget", "预算？", "未知")), List.of(), "52");
        var scope = preparation(input); scope.remove("requirements");
        var requirements = input.taskSources().stream().map(source -> new NaturalTaskUnderstanding.Requirement(source.text(),
                List.of(new NaturalTaskUnderstanding.Evidence(source.id(), source.text())), List.of(NaturalTaskUnderstanding.Dimension.CONDITION))).toList();
        var task = new NaturalTaskUnderstanding(input.rawPrompt(), "", requirements, List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, List.of(), List.of(), List.of()));
        var plan = json.createObjectNode().put("summary", "保留预算未知。"); plan.putArray("questions"); plan.set("taskUnderstanding", json.valueToTree(task));
        expect(scope, request -> assertThat(system(request)).isEqualTo(NaturalPromptScopedInstructions.PREPARE));
        expect(plan, request -> {
            assertThat(system(request)).isEqualTo(NaturalPromptRegistryDiagnosticInstructions.PLAN);
            assertThat(payload(request).has("preparedRequirements")).isFalse();
        });
        provider.plan(input, value -> { value.taskUnderstanding().resolve(input).review().validatePlan(Set.of()); return value; });
        assertThat(NaturalPromptInstructions.planningInstruction("52")).isEqualTo(NaturalPromptInstructions.planningInstruction("51"));
        server.verify();
    }

    private record Scenario(String raw, List<PlanAnswer> answers, String current, String independent) { }

    /** 声明的状态样本不承担模型语义评分，只为同一wire提供不同对象、条件与回答状态的输入。 */
    private Scenario scenario(String variant) {
        return switch (variant) {
            case "confirmed" -> new Scenario("请整理方案，呈现方式由我确认；预算不得超过500元，不修改文件。",
                    List.of(new PlanAnswer("format", "呈现方式？", "用表格")), "以表格整理方案", "预算不得超过500元，不修改文件。");
            case "partial" -> new Scenario("请整理方案，呈现方式和开始时间由我确认；预算不得超过500元，不修改文件。",
                    List.of(new PlanAnswer("formatAndStart", "呈现方式和开始时间？", "用表格，开始时间尚未确定")), "以表格整理方案，开始时间保持未确定", "预算不得超过500元，不修改文件。");
            case "unknown" -> new Scenario("请整理方案，呈现方式由我确认；预算不得超过500元，不修改文件。",
                    List.of(new PlanAnswer("format", "呈现方式采用表格还是列表？", "未知")), "整理方案，呈现方式保持未确定", "预算不得超过500元，不修改文件。");
            case "otherObject" -> new Scenario("请分别整理A、B两组安排。A组预算上限500元；B组预算由我确认；不修改文件。",
                    List.of(new PlanAnswer("budgetB", "B组预算？", "B组800元")), "分别整理A、B两组安排，B组预算为800元", "A组预算上限500元，不修改文件。");
            case "futureCondition" -> new Scenario("先给出方案，批准后再实施；呈现方式由我确认。预算不得超过500元，不修改文件。",
                    List.of(new PlanAnswer("format", "呈现方式？", "用表格")), "先以表格给出方案，批准后再实施", "预算不得超过500元，不修改文件。");
            default -> new Scenario("请整理方案。预算、开始时间由我确认；不修改文件。",
                    List.of(new PlanAnswer("budget", "预算？", "未知"), new PlanAnswer("start", "开始时间？", "未知")), "整理方案，预算和开始时间分别保持未知", "不修改文件。");
        };
    }

    /** 只读闭批51实际反例的安全副本，固定content哈希；不读取账号或过期Plan状态。 */
    private JsonNode frozenCounterexample() throws java.io.IOException {
        try (var stream = getClass().getResourceAsStream("/natural-effective-registry/frozen51-p02.json")) {
            assertThat(stream).isNotNull(); return json.readTree(stream);
        }
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private NaturalPromptInput input(String raw, List<PlanAnswer> answers, List<String> permissions, String version) {
        return new NaturalPromptInput(raw, null, answers, List.of(), permissions, null).withStrategyVersion(version);
    }

    /** 每个真实题答保留独立来源；测试可显式替换理解，服务端不能替模型猜补。 */
    private ObjectNode preparation(NaturalPromptInput input) {
        var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()));
        var value = json.createObjectNode();
        value.set("actions", json.valueToTree(List.of(Map.of("text", input.rawPrompt(), "timing", "CURRENT", "condition", "", "evidence", evidence))));
        value.set("response", json.valueToTree(Map.of("basis", "EXPLICIT", "description", "原定回应", "evidence", evidence)));
        var requirements = value.putArray("requirements"); input.taskSources().forEach(source -> requirements.add(requirement(source)));
        return value;
    }

    private ObjectNode requirement(NaturalTaskSource source) {
        var value = json.createObjectNode().put("text", source.text()); value.putArray("dimensions").add("CONDITION");
        value.putArray("evidence").addObject().put("sourceId", source.id()).put("quote", source.text()); return value;
    }

    private ObjectNode writing(String body, int count) {
        var value = json.createObjectNode().put("optimizedPrompt", body).put("framework", "APE").put("goal", body).put("deliverable", "").put("inputReadiness", "SUFFICIENT");
        var coverage = value.putArray("coverage");
        for (int index = 0; index < count; index++) coverage.addObject().put("reqId", "r" + index).putArray("bodyQuotes").add(body);
        value.putArray("requirementChanges"); value.putArray("additions"); value.putArray("openDecisions"); return value;
    }

    /** 复用原应用安全/权限组装，并分别检查原稿和assembled锚点，不建立新的语义判定器。 */
    private NaturalPromptDraft checked(NaturalPromptDraft draft, NaturalPromptInput input) {
        var task = draft.taskUnderstanding().resolve(input);
        try {
            var method = NaturalEnhancementService.class.getDeclaredMethod("validateBody", NaturalPromptDraft.class, List.class, String.class); method.setAccessible(true);
            String assembled = (String) method.invoke(new NaturalEnhancementService(null, null, null, null, null, null), draft, input.mandatoryRules(), input.strategyVersion());
            task.review().validateEnhancement(task, draft.optimizedPrompt().strip()); task.review().validateEnhancement(task, assembled);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        return draft;
    }

    private String system(JsonNode request) { return request.path("messages").get(0).path("content").asText(); }
    private JsonNode payload(JsonNode request) throws java.io.IOException {
        String user = request.path("messages").get(1).path("content").asText(); return json.readTree(user.substring(user.indexOf('{')));
    }

    private void expect(ObjectNode response, CheckedRequest check) throws Exception { expectContent(json.writeValueAsString(response), check); }

    /** 原件content原样进入Mock HTTP；只保存无认证请求body，不发真实网络请求。 */
    private void expectContent(String content, CheckedRequest check) throws Exception {
        String envelope = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content)))));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            String raw = ((MockClientHttpRequest) request).getBodyAsString(); requestBytes.add(raw);
            JsonNode body = json.readTree(raw); requests.add(body); check.accept(body);
        }).andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }

    @FunctionalInterface private interface CheckedRequest { void accept(JsonNode request) throws java.io.IOException; }
}
