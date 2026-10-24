package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.NaturalPromptDraft;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.domain.NaturalPromptInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptIntentCompletionInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptContentScopeInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptPreparationRoutingInstructions;
import com.promptoptimizer.provider.domain.NaturalPromptStageInstructions;
import com.promptoptimizer.provider.domain.NaturalTaskSource;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 检查37/38实际发送的指导与两阶段保护；指导组装与模拟正确正文不证明自由文本语义已正确。
 * 旧31/36/37字节哈希来自各轮冻结指导，不改变响应字段或实际题答来源契约。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalIntentCompletionProviderTest {
    private static final String ENDPOINT = "http://localhost:19000/v1/chat/completions";
    private static final String RAW = "请阅读现有项目，完成后等待我安排下一步，不修改文件。";
    private static final String BODY = RAW + "沿模块入口梳理主要职责；不访问生产系统，不对外披露客户名称。";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleNaturalPromptProvider provider;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var settings = new OpenAiCompatibleProperties();
        settings.setEndpoint(URI.create(ENDPOINT));
        settings.setApiKey("fixture");
        settings.setModel("fixture");
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, settings,
                (PlatformModelCatalog) null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"37", "38"})
    void unspecifiedPreparationStillGeneratesWithUnifiedGuidanceAndNoIndependentDeliverable(String version) throws Exception {
        var input = input(false, version);
        expect(scope(input), input, false, system -> assertThat(system)
                .isEqualTo(NaturalPromptIntentCompletionInstructions.PREPARE)
                .isEqualTo(NaturalPromptPreparationRoutingInstructions.PREPARE));
        var response = enhancement(input);
        // 无权威标量诊断不作为语义结论，也不应使保留来源和覆盖的合法结果被误拒。
        response.put("diagnosticNote", "fixture-only");
        expect(response, input, true, system -> {
            assertThat(system).isEqualTo("38".equals(version) ? NaturalPromptContentScopeInstructions.ENHANCE
                    : NaturalPromptIntentCompletionInstructions.ENHANCE);
            assertUnifiedGuidance(system);
            assertThat(system).contains("从完整原始提示词", "从正文每项新增要求", "不只遍历自己已提取的requirements")
                    .doesNotContain("首句说明要完成什么和交付什么", "无法可靠匹配用APE", "未指定时用合适的一般表达");
        });

        var draft = provider.enhance(input, this::checkDraft);

        assertThat(draft.optimizedPrompt()).isEqualTo(BODY).isNotEqualTo(RAW);
        assertThat(draft.taskUnderstanding().goal()).isEqualTo("理解项目并等待下一步");
        assertThat(draft.taskUnderstanding().deliverable()).isEmpty();
        assertThat(draft.taskUnderstanding().additions()).hasSize(1);
        assertThat(draft.framework()).isEqualTo(NaturalPromptDraft.Framework.APE);
        assertBoundAnswers(draft.taskUnderstanding().requirements());
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"37,false", "37,true", "38,false", "38,true"})
    void planKeepsBothZeroQuestionsAndARealUserPermissionWithoutReintroducingMaterialDebt(String version, boolean withQuestion) throws Exception {
        var input = input(withQuestion, version);
        expect(scope(input), input, false, system -> assertThat(system).isEqualTo(NaturalPromptIntentCompletionInstructions.PREPARE));
        expect(planning(input, withQuestion), input, true, system -> {
            assertThat(system).isEqualTo(NaturalPromptInstructions.planningInstruction(version))
                    .isEqualTo(NaturalPromptIntentCompletionInstructions.PLAN);
            assertUnifiedGuidance(system);
            assertThat(system).doesNotContain("用户独有的实际信息、明确保留的选择、授权和无法消解的来源冲突，若不同答案会实质改变结果，必须提问。");
        });

        var result = provider.plan(input, this::checkPlan);

        assertThat(result.questions()).hasSize(withQuestion ? 1 : 0);
        assertThat(result.taskUnderstanding().deliverable()).isEmpty();
        assertBoundAnswers(result.taskUnderstanding().requirements());
        if (withQuestion) assertThat(result.questions().getFirst().question()).isEqualTo("是否允许读取客户附件？");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"37", "38"})
    void enhancementAndPlanDescribeSharedFieldsConsistentlyWithoutChangingEnumCodes(String version) {
        String enhance = NaturalPromptInstructions.enhancementInstruction(version);
        String plan = NaturalPromptInstructions.planningInstruction(version);
        assertThat(enhance).isEqualTo("38".equals(version) ? NaturalPromptContentScopeInstructions.ENHANCE
                : NaturalPromptIntentCompletionInstructions.ENHANCE);
        assertThat(plan).isEqualTo(NaturalPromptIntentCompletionInstructions.PLAN);
        assertThat(NaturalPromptStageInstructions.enhancementInstruction(version)).isEqualTo(enhance);
        assertThat(NaturalPromptStageInstructions.planningInstruction(version)).isEqualTo(plan);
        assertThat(NaturalPromptInstructions.supports(version)).isTrue();
        assertThat(NaturalPromptInstructions.requiresTaskUnderstanding(version)).isTrue();
        assertThat(NaturalPromptInstructions.requiresTaskReview(version)).isTrue();
        assertThat(NaturalPromptStageInstructions.supports(version)).isTrue();
        assertThat(NaturalPromptStageInstructions.requiresFramework(version)).isFalse();
        assertThat(NaturalPromptStageInstructions.acceptsDiagnosticFields(version)).isTrue();
        for (String field : List.of("goal", "deliverable", "inputReadiness")) {
            assertThat(fieldDescription(enhance, field)).as(field)
                    .isEqualTo(fieldDescription(plan, field));
        }
        assertThat(fieldDescription(enhance, "deliverable")).contains("未要求独立成果可为空");
        assertThat(fieldDescription(enhance, "inputReadiness")).isEqualTo("SUFFICIENT或NEEDS_COMPLETION");
        assertThat(enhance).doesNotContain("\"passed\"", "\"semanticCheckPassed\"", "executionResponsibilities", "dependencyId");
    }

    @ParameterizedTest
    @CsvSource({"37,sourceId", "37,quote", "37,bodyQuote", "38,sourceId", "38,quote", "38,bodyQuote"})
    void selfReportedSuccessCannotOverrideInvalidOriginalEvidenceOrFinalCoverage(String version, String defect) throws Exception {
        var input = input(false, version);
        expect(scope(input), input, false, system -> { });
        var bad = enhancement(input);
        bad.put("semanticCheckPassed", true);
        var requirement = (ObjectNode) bad.path("task").path("requirements").get(0);
        switch (defect) {
            case "sourceId" -> ((ObjectNode) requirement.path("evidence").get(0)).put("sourceId", "taskScope.generatedRequirement");
            case "quote" -> ((ObjectNode) requirement.path("evidence").get(0)).put("quote", "用户已确认必须提交项目说明文档");
            case "bodyQuote" -> requirement.putArray("bodyQuotes").add("正文没有这个片段");
            default -> throw new IllegalArgumentException(defect);
        }
        expect(bad, input, true, system -> { });
        expect(bad, input, true, system -> { });

        assertThatThrownBy(() -> provider.enhance(input, this::checkDraft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void contentBoundaryCandidateStillRejectsCrossStageProtocolFields() throws Exception {
        var input = input(false, "38");
        expect(scope(input), input, false, system -> { });
        var bad = enhancement(input);
        bad.putArray("questions");
        expect(bad, input, true, system -> { });
        expect(bad, input, true, system -> { });

        assertThatThrownBy(() -> provider.enhance(input, this::checkDraft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void contentBoundaryIsTheOnlyGuidanceChangeAndDoesNotSwitchTheDefault() {
        String original = "且不增加成果、前置资料义务或授权。";
        String replacement = "保持原定成果类型、范围和明确格式，不擅自增加独立交付义务、用户前置资料义务或执行授权。"
                + "允许具体化原成果内部有用的内容组成、必要操作关系及用户已委托的设计；拟议做法不冒充事实或用户确认。";
        String frozen = NaturalPromptIntentCompletionInstructions.ENHANCE;
        assertThat(frozen.indexOf(original)).isGreaterThanOrEqualTo(0).isEqualTo(frozen.lastIndexOf(original));
        assertThat(NaturalPromptContentScopeInstructions.ENHANCE).isEqualTo(frozen.replace(original, replacement));
        assertThat(NaturalPromptContentScopeInstructions.PREPARE).isEqualTo(NaturalPromptIntentCompletionInstructions.PREPARE);
        assertThat(NaturalPromptContentScopeInstructions.PLAN).isEqualTo(NaturalPromptIntentCompletionInstructions.PLAN);
        assertThat(NaturalPromptInstructions.CURRENT_VERSION).isEqualTo("31");
        assertThat(new NaturalPromptInput(RAW, null, List.of(), List.of(), List.of(), null).strategyVersion()).isEqualTo("31");
    }

    @Test
    void priorStableAndCandidateGuidanceKeepTheirFrozenBytes() throws Exception {
        // 来源为natural-preparation-routing-2026-10-24/development的四份UTF-8实际编译指导；测试不依赖运行期证据文件。
        assertThat(sha256(NaturalPromptInstructions.enhancementInstruction("31")))
                .isEqualTo("dcef8a376b6f9804a4f15f828beca7eabf3ed739fac396e055560059f255f8d8");
        assertThat(sha256(NaturalPromptInstructions.planningInstruction("31")))
                .isEqualTo("19fb065c8669b9ff6fca097030c5a091e27efa9aafe40200d7343275d26681d7");
        assertThat(sha256(NaturalPromptInstructions.enhancementInstruction("36")))
                .isEqualTo("508ab128b4ef2f1bbbab8eb1127dd697c2f22a0b3d04c1673d851851a1e0c76e");
        assertThat(sha256(NaturalPromptInstructions.planningInstruction("36")))
                .isEqualTo("4527fc1fcaf83f9975ed21da951e18b5001dea426a4cf3091026476659d35d90");
        // 37三份冻结指导来自natural-intent-completion-2026-10-24/regression，38注册不改写其历史。
        assertThat(sha256(NaturalPromptInstructions.enhancementInstruction("37")))
                .isEqualTo("2ec4dc53e57bbeed5ba941f82cd7de0ef7957c9bcf1f2e25a1d425f348c09969");
        assertThat(sha256(NaturalPromptInstructions.planningInstruction("37")))
                .isEqualTo("df755e4937cfc0aa53d69333d25920ecf0df02831e96068676eced3e7c914211");
        assertThat(sha256(NaturalPromptIntentCompletionInstructions.PREPARE))
                .isEqualTo("e4f54fa11ca0513ef446ddd79d0434903867c2a549e14bb2534cba508b190330");
    }

    /** 限定候选指导中应消失的冲突，不把文字命中当作模型实际理解或语义正确的证明。 */
    private void assertUnifiedGuidance(String instruction) {
        assertThat(instruction).contains("未要求独立成果", "完整原始提示词", "未见材料不推断为没有材料或无权访问")
                .doesNotContain("未指定时用合适的一般表达");
    }

    /** 仅提取协议中的唯一字段说明，避免阶段间又写出相反的动作、成果或充分度定义。 */
    private String fieldDescription(String instruction, String field) {
        var matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":\"([^\"]*)\"").matcher(instruction);
        assertThat(matcher.find()).as(field + " field exists").isTrue();
        String value = matcher.group(1);
        assertThat(matcher.find()).as(field + " field is unique").isFalse();
        return value;
    }

    /** 将不同题干的相同答案保留为独立来源；项目上下文也随两阶段传递。 */
    private NaturalPromptInput input(boolean reservedPermission, String version) {
        return new NaturalPromptInput(RAW + (reservedPermission ? "是否允许读取客户附件由我确认。" : ""),
                new ContextSnapshot("项目入口与目录摘要用于理解模块职责。", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                List.of(new PlanAnswer("q_production", "是否允许访问生产系统？", "不允许"),
                        new PlanAnswer("q_disclosure", "是否允许披露客户名称？", "不允许")),
                List.of(), List.of(), null).withStrategyVersion(version);
    }

    private ObjectNode scope(NaturalPromptInput input) {
        var result = json.createObjectNode();
        var action = result.putArray("actions").addObject().put("text", "准备阶段推定的项目说明")
                .put("timing", "CURRENT").put("condition", "");
        action.putArray("evidence").addObject().put("sourceId", "rawPrompt").put("quote", input.rawPrompt());
        result.putObject("response").put("basis", "UNSPECIFIED").put("description", "").putArray("evidence");
        return result;
    }

    /** 正文与反向引用均为夹具，验证服务端保留纠正后的实际记录和已有安全保护。 */
    private ObjectNode enhancement(NaturalPromptInput input) {
        var result = json.createObjectNode().put("optimizedPrompt", BODY);
        var task = task(input, true);
        task.putArray("additions").addObject().put("text", "沿模块入口梳理主要职责")
                .put("gap", "阅读对象的处理关系尚需具体化").put("benefit", "帮助执行者理解项目职责")
                .putArray("bodyQuotes").add("沿模块入口梳理主要职责");
        task.putArray("openDecisions");
        result.set("task", task);
        return result;
    }

    private ObjectNode planning(NaturalPromptInput input, boolean withQuestion) throws Exception {
        var result = json.createObjectNode().put("summary", "理解项目后等待下一步；仅在需要时确认附件读取权限。");
        var task = task(input, false);
        task.putArray("unaskedDecisions");
        result.set("task", task);
        result.set("questions", json.readTree(withQuestion ? """
                [{"id":"q_attachment","question":"是否允许读取客户附件？","hint":"影响可阅读的资料范围",
                 "type":"FREE_TEXT","options":[],"examples":[],"allowCustomAnswer":true,
                 "decisionBasis":[{"issue":"客户附件读取权限","kind":"USER_INFORMATION",
                  "whyAsk":"用户明确保留附件访问决定","answerDifference":"改变可以读取的材料范围"}]}]
                """ : "[]"));
        return result;
    }

    /** 三项真实要求各自有来源，增强再附正文片段，Plan不填写尚不存在的正文覆盖。 */
    private ObjectNode task(NaturalPromptInput input, boolean covered) {
        var task = json.createObjectNode().put("goal", "理解项目并等待下一步").put("deliverable", "")
                .put("inputReadiness", "NEEDS_COMPLETION");
        var requirements = task.putArray("requirements");
        var original = requirements.addObject().put("text", "阅读现有项目后等待，不修改文件");
        original.putArray("dimensions").add("GOAL").add("CONDITION");
        original.putArray("evidence").addObject().put("sourceId", "rawPrompt").put("quote", input.rawPrompt());
        if (covered) original.putArray("bodyQuotes").add(RAW);
        for (int i = 0; i < 2; i++) {
            var requirement = requirements.addObject().put("text", i == 0 ? "不访问生产系统" : "不对外披露客户名称");
            requirement.putArray("dimensions").add("USER_CONFIRMATION");
            requirement.putArray("evidence").addObject().put("sourceId", "actualAnswers." + i).put("quote", "不允许");
            if (covered) requirement.putArray("bodyQuotes").add(i == 0 ? "不访问生产系统" : "不对外披露客户名称");
        }
        return task;
    }

    private void assertBoundAnswers(List<com.promptoptimizer.provider.domain.NaturalTaskUnderstanding.Requirement> requirements) {
        assertThat(requirements).hasSize(3);
        assertThat(requirements.subList(1, 3)).allSatisfy(requirement -> assertThat(requirement.evidence().getFirst().origin())
                .isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER));
        assertThat(requirements.subList(1, 3)).extracting(requirement -> requirement.evidence().getFirst().sourceId())
                .containsExactly("actualAnswers.0", "actualAnswers.1");
    }

    private NaturalPromptDraft checkDraft(NaturalPromptDraft draft) {
        draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
        return draft;
    }

    private PlanningProviderResponse checkPlan(PlanningProviderResponse plan) {
        plan.taskUnderstanding().review().validatePlan(plan.questions().stream().map(question -> question.id())
                .collect(java.util.stream.Collectors.toSet()));
        return plan;
    }

    /** 模拟真实传输同时校验system、完整来源、实际答案与准备阶段的非权威边界。 */
    private void expect(ObjectNode content, NaturalPromptInput input, boolean prepared, Consumer<String> systemCheck) throws Exception {
        String envelope = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("content", json.writeValueAsString(content))))));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            systemCheck.accept(sent.path("messages").get(0).path("content").asText());
            String user = sent.path("messages").get(1).path("content").asText();
            JsonNode payload = json.readTree(user.substring(user.indexOf('{')));
            assertThat(payload.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
            assertThat(payload.path("context")).isEqualTo(json.valueToTree(input.context()));
            assertThat(payload.path("taskSources")).isEqualTo(json.valueToTree(input.taskSources()));
            assertThat(payload.path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
            assertThat(payload.path("strategyVersion").asText()).isEqualTo(input.strategyVersion());
            assertThat(payload.path("inputBoundary").path("stage").asText()).isEqualTo("PROMPT_OPTIMIZATION");
            assertThat(payload.has("taskScope")).isEqualTo(prepared);
            assertThat(payload.has("executionResponsibilities")).isFalse();
        }).andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }

    private String sha256(String instruction) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(instruction.getBytes(StandardCharsets.UTF_8)));
    }
}
