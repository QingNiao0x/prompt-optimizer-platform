package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.provider.domain.NaturalPromptDraft;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.domain.NaturalTaskSource;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.PlanningProviderResponse;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
 * 验证准备分类仅供后续理解参考，不能关闭正文生成或覆盖完整原始来源。
 * 模拟响应只证明派发与协议保护；自由文本的判断质量仍须通过真实评测验收。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalPreparationRoutingProviderTest {
    private static final String ENDPOINT = "http://localhost:19000/v1/chat/completions";
    private static final String RAW = "请根据执行时提供的资料撰写总结，是否允许披露客户名称由我确认。";
    private static final String BODY = "请根据执行时提供的资料撰写总结，先梳理关键结论及依据；披露客户名称前须由我确认。";
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
    @ValueSource(strings = {"EXPLICIT", "INHERENT", "UNSPECIFIED"})
    void everyResponseClassificationStillCallsTheWriterWithOriginalEvidence(String basis) throws Exception {
        var input = input(RAW);
        expect(scope(input, basis), input, false);
        expect(enhancement(input, BODY), input, true);

        var draft = provider.enhance(input, this::checkDraft);

        assertThat(draft.optimizedPrompt()).isEqualTo(BODY).isNotEqualTo(RAW);
        assertThat(draft.taskUnderstanding().goal()).isEqualTo("根据资料撰写总结");
        assertThat(draft.taskUnderstanding().deliverable()).isEqualTo("总结正文");
        assertThat(draft.taskUnderstanding().additions()).hasSize(1);
        server.verify();
    }

    @Test
    void finalUnderstandingCanCorrectPreparationInsteadOfBeingOverwrittenByIt() throws Exception {
        var input = input(RAW);
        var preparation = scope(input, "UNSPECIFIED");
        ((ObjectNode) preparation.path("actions").get(0)).put("text", "熟悉现有材料");
        expect(preparation, input, false);
        expect(enhancement(input, BODY), input, true);

        var draft = provider.enhance(input, this::checkDraft);

        assertThat(draft.taskUnderstanding().goal()).isEqualTo("根据资料撰写总结");
        assertThat(draft.taskUnderstanding().deliverable()).isEqualTo("总结正文");
        server.verify();
    }

    /** 保存的35真实错分直接回放，不重新请求模型来寻找一次相同故障。 */
    @Test
    void savedResearchMisclassificationStillReachesWritingAndPreservesAllFourAnalyses() throws Exception {
        Path fixture = Path.of("docs/testing/evidence/natural-material-responsibility-2026-10-24/preparation-failure.json");
        if (!Files.exists(fixture)) fixture = Path.of("../..").resolve(fixture).normalize();
        JsonNode saved = json.readTree(Files.readString(fixture));
        String raw = saved.path("rawPrompt").asText();
        var input = input(raw);
        var preparation = (ObjectNode) saved.path("preparation").path("taskScope").deepCopy();
        assertThat(preparation.path("response").path("basis").asText()).isEqualTo("UNSPECIFIED");
        expect(preparation, input, false);
        String body = "请撰写2015—2025年某地区心脑血管疾病死亡率特征分析正文，包含长期趋势和季节性趋势、"
                + "分性别/地区/人群/亚类比较、年度及性别YLL和YLL率、Arriaga分解。"
                + "交代各项分析的方法、结果及相互联系；待确认事项集中说明，资料限制仅作用于依赖它的数值和结论。";
        var generated = enhancement(input, body);
        var task = (ObjectNode) generated.path("task");
        task.put("goal", "撰写心脑血管疾病死亡率特征分析").put("deliverable", "分析报告正文");
        var requirements = task.putArray("requirements");
        String[] sources = {"时间序列的长期趋势和季节性趋势", "分性别\\地区\\分人群\\分亚类去进行比较",
                "年度的\\性别的yll和yll率", "采用Arriaga分解方法去分析"};
        String[] anchors = {"长期趋势和季节性趋势", "分性别/地区/人群/亚类比较", "年度及性别YLL和YLL率", "Arriaga分解"};
        for (int i = 0; i < sources.length; i++) {
            var requirement = requirements.addObject().put("text", anchors[i]);
            requirement.putArray("dimensions").add("GOAL");
            requirement.putArray("evidence").addObject().put("sourceId", "rawPrompt").put("quote", sources[i]);
            requirement.putArray("bodyQuotes").add(anchors[i]);
        }
        expect(generated, input, true);

        var draft = provider.enhance(input, this::checkDraft);

        assertThat(draft.optimizedPrompt()).isEqualTo(body).isNotEqualTo(raw);
        assertThat(draft.taskUnderstanding().deliverable()).isEqualTo("分析报告正文");
        assertThat(draft.taskUnderstanding().requirements()).hasSize(4);
        assertThat(draft.taskUnderstanding().additions()).hasSize(1);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"数据执行时提供", "没有相关资料", "无权访问业务系统"})
    void materialAvailabilityCannotSkipGenerationOrEraseExplicitLimits(String limitation) throws Exception {
        String raw = "请撰写业务分析报告，" + limitation + "。";
        var input = input(raw);
        String body = raw + "先整理有依据的分析内容；仅将依赖资料的实证结果列为待确认事项，其余正文继续完成。";
        expect(scope(input, "UNSPECIFIED"), input, false);
        var generated = enhancement(input, body);
        ((ObjectNode) generated.path("task")).put("goal", "撰写业务分析报告").put("deliverable", "业务分析报告正文");
        expect(generated, input, true);

        var draft = provider.enhance(input, this::checkDraft);

        assertThat(draft.optimizedPrompt()).contains(limitation, "待确认事项", "其余正文继续完成");
        assertThat(draft.taskUnderstanding().deliverable()).isEqualTo("业务分析报告正文");
        server.verify();
    }

    @Test
    void familiarizationAndWaitingCanRemainWithoutAnIndependentDocument() throws Exception {
        String raw = "请先熟悉项目，完成后等待下一步任务，不修改文件，不提交说明文档。";
        var input = input(raw);
        String body = raw + "阅读现有项目入口与相关代码，理解模块职责及调用关系。";
        expect(scope(input, "INHERENT"), input, false);
        var generated = enhancement(input, body);
        ((ObjectNode) generated.path("task")).put("goal", "熟悉项目并等待下一步任务").put("deliverable", "");
        expect(generated, input, true);

        var draft = provider.enhance(input, this::checkDraft);

        assertThat(draft.optimizedPrompt()).isEqualTo(body);
        assertThat(draft.taskUnderstanding().deliverable()).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPLICIT", "UNSPECIFIED"})
    void executorRetrievalAllowsAZeroQuestionPlanWithoutDependencies(String basis) throws Exception {
        var input = input("请查询公开资料并撰写产品比较报告，比较维度和推荐方案由你设计。");
        expect(scope(input, basis), input, false);
        var generated = planning(input, false);
        ((ObjectNode) generated.path("task")).put("goal", "查询资料并撰写产品比较报告").put("deliverable", "比较报告");
        expect(generated, input, true);

        var plan = provider.plan(input, this::checkPlan);

        assertThat(plan.questions()).isEmpty();
        assertThat(plan.taskUnderstanding().goal()).isEqualTo("查询资料并撰写产品比较报告");
        assertThat(plan.taskUnderstanding().deliverable()).isEqualTo("比较报告");
        server.verify();
    }

    @Test
    void aUserReservedPermissionStillProducesAnActualPlanQuestion() throws Exception {
        var input = input(RAW);
        expect(scope(input, "UNSPECIFIED"), input, false);
        expect(planning(input, true), input, true);

        var plan = provider.plan(input, this::checkPlan);

        assertThat(plan.questions()).hasSize(1);
        assertThat(plan.questions().getFirst().question()).isEqualTo("是否允许披露客户名称？");
        assertThat(plan.taskUnderstanding().review().planImpacts()).hasSize(1);
        server.verify();
    }

    @Test
    void actualAnswersKeepBothSourceIdentitiesAndFinalBodyCoverage() throws Exception {
        var input = answeredInput();
        String body = BODY + "不访问客户系统，不披露客户名称。";
        expect(scope(input, "UNSPECIFIED"), input, false);
        var generated = enhancement(input, body);
        appendAnswerRequirements(generated, true);
        expect(generated, input, true);

        var draft = provider.enhance(input, this::checkDraft);

        assertThat(draft.optimizedPrompt()).contains("不访问客户系统", "不披露客户名称");
        assertThat(draft.taskUnderstanding().requirements().subList(1, 3)).allSatisfy(requirement ->
                assertThat(requirement.evidence().getFirst().origin()).isEqualTo(NaturalTaskSource.Kind.BOUND_PLAN_ANSWER));
        assertThat(draft.taskUnderstanding().requirements().subList(1, 3))
                .extracting(requirement -> requirement.evidence().getFirst().sourceId())
                .containsExactly("actualAnswers.0", "actualAnswers.1");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"enhancement", "planning"})
    void simplifiedPreparationStillRejectsOmittedActualAnswerSources(String phase) throws Exception {
        var input = answeredInput();
        expect(scope(input, "UNSPECIFIED"), input, false);
        var bad = "enhancement".equals(phase) ? enhancement(input, BODY) : planning(input, false);
        expect(bad, input, true);
        expect(bad, input, true);

        assertThatThrownBy(() -> {
            if ("enhancement".equals(phase)) provider.enhance(input, this::checkDraft);
            else provider.plan(input, this::checkPlan);
        }).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknownSource", "inventedQuote"})
    void sourceForgeryInPreparationFailsBeforeAnyWriterCall(String defect) throws Exception {
        var input = input(RAW);
        var bad = scope(input, "EXPLICIT");
        var evidence = (ObjectNode) bad.path("actions").get(0).path("evidence").get(0);
        if ("unknownSource".equals(defect)) evidence.put("sourceId", "preparation.generatedFact");
        else evidence.put("quote", "用户已经确认全部权限");
        expect(bad, input, false);
        expect(bad, input, false);

        assertThatThrownBy(() -> provider.enhance(input, this::checkDraft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sourceQuote", "sourceId", "bodyQuote"})
    void preparationDoesNotLetFinalGenerationForgeEvidenceOrCoverage(String defect) throws Exception {
        var input = input(RAW);
        expect(scope(input, "UNSPECIFIED"), input, false);
        var bad = enhancement(input, BODY);
        var requirement = (ObjectNode) bad.path("task").path("requirements").get(0);
        switch (defect) {
            case "sourceQuote" -> ((ObjectNode) requirement.path("evidence").get(0)).put("quote", "用户确认允许公开");
            case "sourceId" -> ((ObjectNode) requirement.path("evidence").get(0)).put("sourceId", "taskScope.actions.0");
            case "bodyQuote" -> requirement.putArray("bodyQuotes").add("实际正文没有这句话");
            default -> throw new IllegalArgumentException(defect);
        }
        expect(bad, input, true);
        expect(bad, input, true);

        assertThatThrownBy(() -> provider.enhance(input, this::checkDraft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    /** 重复回答文本仍属于两道独立权限问题，不可按文字去重。 */
    private NaturalPromptInput answeredInput() {
        return new NaturalPromptInput(RAW, null,
                List.of(new PlanAnswer("q_access", "允许访问客户系统吗？", "不允许"),
                        new PlanAnswer("q_disclosure", "允许披露客户名称吗？", "不允许")),
                List.of(), List.of(), null).withStrategyVersion("36");
    }

    private NaturalPromptInput input(String raw) {
        return new NaturalPromptInput(raw, null, List.of(), List.of(), List.of(), null).withStrategyVersion("36");
    }

    /** 合法的错分也应传入后续阶段，不能被解释为权威判断或新增事实。 */
    private ObjectNode scope(NaturalPromptInput input, String basis) {
        var result = json.createObjectNode();
        var action = result.putArray("actions").addObject().put("text", "理解现有任务")
                .put("timing", "CURRENT").put("condition", "");
        action.putArray("evidence").addObject().put("sourceId", "rawPrompt").put("quote", input.rawPrompt());
        var response = result.putObject("response").put("basis", basis)
                .put("description", "UNSPECIFIED".equals(basis) ? "" : "准备阶段推定的回应");
        var evidence = response.putArray("evidence");
        if (!"UNSPECIFIED".equals(basis)) evidence.addObject().put("sourceId", "rawPrompt").put("quote", input.rawPrompt());
        return result;
    }

    /** 固定模型正文用于检查协议传递；业务语义不是本夹具自动判定的结果。 */
    private ObjectNode enhancement(NaturalPromptInput input, String body) {
        var result = json.createObjectNode().put("optimizedPrompt", body);
        var task = baseTask(input);
        var requirement = (ObjectNode) task.path("requirements").get(0);
        requirement.putArray("bodyQuotes").add(body);
        task.putArray("additions").addObject().put("text", body).put("gap", "执行动作需要更清楚")
                .put("benefit", "把任务整理为可以执行的指令").putArray("bodyQuotes").add(body);
        task.putArray("openDecisions");
        result.set("task", task);
        return result;
    }

    /** Plan独立填写任务理解，不需要正文锚点或35的责任编号。 */
    private ObjectNode planning(NaturalPromptInput input, boolean withQuestion) throws Exception {
        var result = json.createObjectNode().put("summary", "保留原任务，必要时确认用户保留的发布权限。");
        var task = baseTask(input);
        task.putArray("unaskedDecisions");
        result.set("task", task);
        result.set("questions", json.readTree(withQuestion ? """
                [{"id":"q_permission","question":"是否允许披露客户名称？","hint":"仅改变对外披露范围",
                  "type":"FREE_TEXT","options":[],"examples":[],"allowCustomAnswer":true,
                  "decisionBasis":[{"issue":"客户名称披露权限","kind":"USER_INFORMATION",
                    "whyAsk":"原始提示词明确由用户确认","answerDifference":"决定是否公开客户名称"}]}]
                """ : "[]"));
        return result;
    }

    private ObjectNode baseTask(NaturalPromptInput input) {
        var task = json.createObjectNode().put("goal", "根据资料撰写总结").put("deliverable", "总结正文")
                .put("inputReadiness", "NEEDS_COMPLETION");
        var requirement = task.putArray("requirements").addObject().put("text", "保留原始任务及其条件");
        requirement.putArray("dimensions").add("GOAL").add("CONDITION");
        requirement.putArray("evidence").addObject().put("sourceId", "rawPrompt").put("quote", input.rawPrompt());
        return task;
    }

    /** 两份相同答案对应两个来源和不同正文位置，不能靠一份引用代表全部题答。 */
    private void appendAnswerRequirements(ObjectNode response, boolean covered) {
        var requirements = (ArrayNode) response.path("task").path("requirements");
        for (int i = 0; i < 2; i++) {
            var requirement = requirements.addObject().put("text", i == 0 ? "禁止访问客户系统" : "禁止披露客户名称");
            requirement.putArray("dimensions").add("USER_CONFIRMATION");
            requirement.putArray("evidence").addObject().put("sourceId", "actualAnswers." + i).put("quote", "不允许");
            if (covered) requirement.putArray("bodyQuotes").add(i == 0 ? "不访问客户系统" : "不披露客户名称");
        }
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

    /** 每次调用核对完整原始来源，准备结果只作为后阶段辅助输入，不能成为来源目录或责任清单。 */
    private void expect(ObjectNode content, NaturalPromptInput input, boolean prepared) throws Exception {
        String envelope = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("content", json.writeValueAsString(content))))));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            String message = sent.path("messages").get(1).path("content").asText();
            JsonNode payload = json.readTree(message.substring(message.indexOf('{')));
            assertThat(payload.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
            assertThat(payload.path("taskSources")).isEqualTo(json.valueToTree(input.taskSources()));
            assertThat(payload.path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
            assertThat(payload.path("inputBoundary").path("stage").asText()).isEqualTo("PROMPT_OPTIMIZATION");
            assertThat(payload.has("executionResponsibilities")).isFalse();
            assertThat(payload.has("taskScope")).isEqualTo(prepared);
            if (prepared) assertThat(payload.path("taskScope").has("actions")).isTrue();
        }).andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }
}
