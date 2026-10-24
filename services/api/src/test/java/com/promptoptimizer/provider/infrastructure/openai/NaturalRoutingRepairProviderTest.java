package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.enhancement.domain.NaturalProcessing;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 用真实Provider和受控HTTP重现31提前返回，并验证39与所有分类、空Plan及实际题答的关系。
 * 预置正文只证明路由及来源保护，语义和实际作品质量由另外的同信息评估检查。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class NaturalRoutingRepairProviderTest {
    private static final String ENDPOINT = "https://fixture.invalid/chat/completions";
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
        provider = new OpenAiCompatibleNaturalPromptProvider(builder.build(), json, settings, (PlatformModelCatalog) null);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "INHERENT|请帮我查询全球使用AI最多的职业/行业|请查询全球AI使用较广泛的职业和行业，分别整理主要发现、典型用途及公开来源。",
            "INHERENT|解释一下浏览器缓存|请用易懂的语言解释浏览器缓存的作用，并结合访问过程说明何时复用、何时更新。",
            "UNSPECIFIED|比较两种备份方式|请比较两种备份方式，说明恢复过程、维护要求及各自适用条件。",
            "EXPLICIT|只输出三行，每行一个数字：1、2、3|只输出三行，每行一个数字：1、2、3",
            "UNSPECIFIED|阅读项目后等我提问，不修改文件，也不写说明文档。|阅读项目后等我提问，不修改文件，也不写说明文档。",
            "UNSPECIFIED|没有相关资料，请写一份社区活动建议。|请写一份社区活动建议；目前没有相关资料，可提出拟议做法，不编造本社区现状。"
    })
    void everyClassificationReachesTheWriterAndKeepsTheOriginalInput(String basis, String raw, String body) throws Exception {
        var input = input(raw, List.of());
        expect(scope(raw, basis), NaturalPromptRoutingRepairInstructions.PREPARE, payload -> {});
        expect(writing(input, body), NaturalPromptRoutingRepairInstructions.ENHANCE, payload -> {
            assertThat(payload.path("taskScope").path("response").path("basis").asText()).isEqualTo(basis);
            assertThat(payload.path("rawPrompt").asText()).isEqualTo(raw);
        });
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).isEqualTo(body);
        assertThat(draft.completedStages()).containsExactly(NaturalProcessing.Stage.SCOPE_ANALYSIS, NaturalProcessing.Stage.BODY_GENERATION);
        assertThat(NaturalProcessing.completed(draft.completedStages(), raw, body).originalPreserved()).isEqualTo(raw.equals(body));
        server.verify();
    }

    @Test
    void zeroQuestionPlanStillGeneratesUsefulAdditions() throws Exception {
        var input = input("请比较两种通勤方式。", List.of());
        expect(scope(input.rawPrompt(), "INHERENT"), NaturalPromptRoutingRepairInstructions.PREPARE, payload -> {});
        var plan = json.createObjectNode().put("summary", "按原范围比较，不需要用户决定检索步骤。");
        plan.set("questions", json.createArrayNode());
        plan.set("taskUnderstanding", json.valueToTree(task(input, null)));
        expect(plan, NaturalPromptInstructions.planningInstruction(input.strategyVersion()), payload -> {});
        expect(scope(input.rawPrompt(), "INHERENT"), NaturalPromptRoutingRepairInstructions.PREPARE, payload -> {});
        String body = "请比较两种通勤方式，说明时间、换乘和费用方面的差异及适用条件。";
        var writing = writing(input, body);
        var task = (ObjectNode) writing.get("taskUnderstanding");
        task.withArray("additions").addObject().put("text", "说明时间、换乘和费用差异")
                .put("gap", "原文未说明比较结果包括什么").put("benefit", "让比较可直接使用");
        task.withObject("review").withArray("additionCoverage").addObject().put("index", 0)
                .putArray("quotes").add("说明时间、换乘和费用方面的差异");
        expect(writing, NaturalPromptRoutingRepairInstructions.ENHANCE, payload -> {});
        assertThat(provider.plan(input, result -> {
            result.taskUnderstanding().resolve(input).review().validatePlan(java.util.Set.of());
            return result;
        }).questions()).isEmpty();
        assertThat(provider.enhance(input, value -> checked(value, input)).taskUnderstanding().additions()).hasSize(1);
        server.verify();
    }

    @Test
    void allActualAnswersAndTheirBodyCoverageRemainMandatory() throws Exception {
        var answers = List.of(new PlanAnswer("place", "地点？", "青禾中心二层会议室"),
                new PlanAnswer("date", "日期？", "日期保持原定，不能承诺提前"));
        var input = input("请修改活动通知。", answers);
        expect(scope(input.rawPrompt(), "INHERENT"), NaturalPromptRoutingRepairInstructions.PREPARE, payload -> {});
        String body = "请修改活动通知。地点为青禾中心二层会议室；日期保持原定，不能承诺提前。";
        expect(writing(input, body), NaturalPromptRoutingRepairInstructions.ENHANCE, payload ->
                assertThat(payload.path("actualAnswers").size()).isEqualTo(2));
        assertThat(provider.enhance(input, value -> checked(value, input)).optimizedPrompt()).contains(answers.get(1).answer());
        server.verify();
    }

    @Test
    void forgedEvidenceStillFailsInsteadOfFallingBackToRawText() throws Exception {
        var input = input("请解释事务。", List.of());
        expect(scope(input.rawPrompt(), "INHERENT"), NaturalPromptRoutingRepairInstructions.PREPARE, payload -> {});
        var bad = writing(input, "请解释事务，并说明原子性。");
        ((ObjectNode) bad.path("taskUnderstanding").path("requirements").get(0).path("evidence").get(0)).put("quote", "未出现的原句");
        expect(bad, NaturalPromptRoutingRepairInstructions.ENHANCE, payload -> {});
        expect(bad, null, payload -> {});
        assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test
    void old31PreservationIsExplicitlyUnassessedAndIdentifiesItsActualStage() throws Exception {
        var input = input("请阅读并等待。", List.of()).withStrategyVersion("31");
        expect(scope(input.rawPrompt(), "INHERENT"), NaturalPromptScopedInstructions.PREPARE, payload -> {});
        var draft = provider.enhance(input, value -> checked(value, input));
        assertThat(draft.optimizedPrompt()).isEqualTo(input.rawPrompt());
        assertThat(draft.taskUnderstanding().review().inputReadiness()).isEqualTo(NaturalTaskReview.InputReadiness.NOT_ASSESSED);
        assertThat(draft.completedStages()).containsExactly(NaturalProcessing.Stage.SCOPE_ANALYSIS);
        server.verify();
    }

    @Test
    void missingCurrentClassificationDoesNotBlockTheActualUserQuestionIn40() throws Exception {
        var input = input("先问我新的地点，再修改通知。", List.of()).withStrategyVersion("40");
        var preparation = scope(input.rawPrompt(), "EXPLICIT");
        ((ObjectNode) preparation.path("actions").get(0)).put("timing", "CONDITIONAL").put("condition", "用户给出地点后");
        expect(preparation, NaturalPromptRoutingRepairInstructions.PREPARE, payload -> {});
        var plan = json.createObjectNode().put("summary", "确认用户保留的地点");
        plan.set("questions", json.createArrayNode());
        plan.set("taskUnderstanding", json.valueToTree(task(input, null)));
        expect(plan, NaturalPromptRoutingRepairInstructions.PLAN, payload -> {});
        // 此处只证明合法分类不再封死Plan调用；是否实际提对问题由本轮真实定向验证检查。
        assertThat(provider.plan(input, value -> value).summary()).isEqualTo("确认用户保留的地点");
        server.verify();
    }

    @Test
    void planOwnershipChangeKeepsTheVerifiedBodyComponentIdentical() {
        assertThat(NaturalPromptInstructions.enhancementInstruction("41")).isEqualTo(NaturalPromptInstructions.enhancementInstruction("40"));
        assertThat(NaturalPromptInstructions.planningInstruction("41")).contains("决定权条件", "不足以提问");
    }

    @ParameterizedTest
    @CsvSource({"quote", "condition"})
    void advisoryTimingStillRejectsForgedSourcesAndMissingConditions(String defect) throws Exception {
        var input = input("先问我地点，再修改通知。", List.of());
        var preparation = scope(input.rawPrompt(), "EXPLICIT");
        var action = (ObjectNode) preparation.path("actions").get(0);
        action.put("timing", "CONDITIONAL").put("condition", "得到地点后");
        if (defect.equals("condition")) action.put("condition", "");
        else ((ObjectNode) action.path("evidence").get(0)).put("quote", "来源中不存在的内容");
        expect(preparation, NaturalPromptRoutingRepairInstructions.PREPARE, payload -> {});
        expect(preparation, null, payload -> {});
        assertThatThrownBy(() -> provider.enhance(input, value -> checked(value, input))).isInstanceOf(ProviderException.class);
        server.verify();
    }

    private NaturalPromptInput input(String raw, List<PlanAnswer> answers) {
        return new NaturalPromptInput(raw, null, answers, List.of(), List.of(), null).withStrategyVersion("41");
    }

    private NaturalPromptDraft checked(NaturalPromptDraft value, NaturalPromptInput input) {
        var task = value.taskUnderstanding().resolve(input);
        task.review().validateEnhancement(task, value.optimizedPrompt());
        return value;
    }

    private ObjectNode scope(String raw, String basis) {
        var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", raw));
        return json.valueToTree(Map.of("actions", List.of(Map.of("text", raw, "timing", "CURRENT", "condition", "", "evidence", evidence)),
                "response", Map.of("basis", basis, "description", basis.equals("UNSPECIFIED") ? "" : "原定回应",
                        "evidence", basis.equals("UNSPECIFIED") ? List.of() : evidence)));
    }

    private ObjectNode writing(NaturalPromptInput input, String body) {
        var response = json.createObjectNode().put("optimizedPrompt", body).put("framework", "APE");
        response.set("taskUnderstanding", json.valueToTree(task(input, body)));
        return response;
    }

    private NaturalTaskUnderstanding task(NaturalPromptInput input, String body) {
        var requirements = new java.util.ArrayList<NaturalTaskUnderstanding.Requirement>();
        var coverage = new java.util.ArrayList<NaturalTaskReview.Coverage>();
        for (var source : input.taskSources()) {
            requirements.add(new NaturalTaskUnderstanding.Requirement(source.text(),
                    List.of(new NaturalTaskUnderstanding.Evidence(source.id(), source.text())), List.of(NaturalTaskUnderstanding.Dimension.GOAL)));
            if (body != null) coverage.add(new NaturalTaskReview.Coverage(coverage.size(), List.of(body)));
        }
        return new NaturalTaskUnderstanding(input.rawPrompt(), "", requirements, List.of(), List.of(),
                new NaturalTaskReview(NaturalTaskReview.InputReadiness.SUFFICIENT, coverage, List.of(), List.of()));
    }

    private void expect(ObjectNode content, String expectedSystem, Consumer<com.fasterxml.jackson.databind.JsonNode> check) throws Exception {
        String envelope = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                Map.of("content", json.writeValueAsString(content))))));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var requestJson = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            if (expectedSystem != null) assertThat(requestJson.path("messages").get(0).path("content").asText()).isEqualTo(expectedSystem);
            String user = requestJson.path("messages").get(1).path("content").asText();
            check.accept(json.readTree(user.substring(user.indexOf('{'))));
        }).andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
    }
}
