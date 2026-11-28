package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.context.domain.MaterialPurpose;
import com.promptoptimizer.enhancement.domain.NaturalProcessing;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 正常Provider入口核对指导选择、原输入和协议保护，模拟响应不作为语义质量证明。 */
class NaturalExpressionProviderTest {
    private static final String URL = "https://fixture.invalid/chat/completions";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void engineeringAdoptionRetainsTheExactV5WritingAndPlanInstructions() {
        assertThat(NaturalPromptInstructions.enhancementInstruction(NaturalPromptMaterialHandoffInstructions.VERSION))
                .isEqualTo(NaturalPromptProtocolRepairInstructions.ENHANCE);
        assertThat(NaturalPromptInstructions.planningInstruction(NaturalPromptMaterialHandoffInstructions.VERSION))
                .isEqualTo(NaturalPromptProtocolRepairInstructions.PLAN);
        assertThat(NaturalPromptMaterialHandoffInstructions.PREPARE).isEqualTo(NaturalPromptPlanContextInstructions.PREPARE);
        assertThat(NaturalPromptInstructions.requiresTaskReview(NaturalPromptMaterialHandoffInstructions.VERSION)).isTrue();
    }

    @Test
    void executionDetailsReplacesConflictingGuidanceAndKeepsTheV6Protocol() {
        String version = NaturalPromptExecutionDetailsInstructions.VERSION;
        String baseline = NaturalPromptInstructions.enhancementInstruction(NaturalPromptMaterialHandoffInstructions.VERSION);
        String instruction = NaturalPromptInstructions.enhancementInstruction(version);
        String protocol = "只返回以下完整JSON对象";
        int start = baseline.indexOf(protocol);
        assertThat(start).isPositive();
        assertThat(instruction.substring(instruction.indexOf(protocol))).isEqualTo(baseline.substring(start));
        assertThat(instruction).contains("先写执行者需要完成的工作", "环节之间的关系", "可观察的完成条件",
                        "新增独立任务、成果或交付义务须有用户依据", "篇幅随所需信息调整", "信息已充分可少改或保留")
                .doesNotContain("不能另设要向用户展示或交付的内容", "自然、紧凑、可直接交给执行者");
        assertThat(NaturalPromptInstructions.planningInstruction(version)).isEqualTo(NaturalPromptMaterialHandoffInstructions.PLAN);
        assertThat(NaturalPromptInstructions.requiresTaskReview(version)).isTrue();
    }

    @Test
    void currentTaskReplacesOnlyTheDirectWritingGuidance() {
        String version = NaturalPromptExecutionDetailsInstructions.CURRENT_TASK_VERSION;
        String baseline = NaturalPromptInstructions.enhancementInstruction(NaturalPromptMaterialHandoffInstructions.VERSION);
        String instruction = NaturalPromptInstructions.enhancementInstruction(version);
        String protocol = "只返回以下完整JSON对象";
        assertThat(instruction.substring(instruction.indexOf(protocol))).isEqualTo(baseline.substring(baseline.indexOf(protocol)));
        assertThat(instruction).contains("正文先写本轮要做什么", "后续打算与本轮授权分开", "先给结论",
                        "其他任务不要套用这组检查", "不预设必须新增", "独立物理表", "需求已经充分时少改或保留原句")
                .doesNotContain("常备流调", "ieis_", "ExpertActivityConst", "对口指导");
        assertThat(NaturalPromptInstructions.enhancementInstruction(NaturalPromptExecutionDetailsInstructions.VERSION))
                .contains("先明确处理对象、当前动作及原定结果")
                .doesNotContain("其他任务不要套用这组检查");
        assertThat(NaturalPromptInstructions.planningInstruction(version)).isEqualTo(NaturalPromptMaterialHandoffInstructions.PLAN);
        assertThat(NaturalPromptInstructions.supports(version)).isTrue();
        assertThat(NaturalPromptInstructions.requiresTaskReview(version)).isTrue();
    }

    @Test
    void deliverableGuidanceChangesTheCurrentBodyButKeepsPlanAndResponseProtocol() {
        String version = NaturalPromptExecutionDetailsInstructions.DELIVERY_BOUNDARY_VERSION;
        String previous = NaturalPromptInstructions.enhancementInstruction(NaturalPromptExecutionDetailsInstructions.CURRENT_TASK_VERSION);
        String current = NaturalPromptInstructions.enhancementInstruction(version);
        String guideline = com.promptoptimizer.provider.domain.PromptDeliverable.GUIDANCE;
        assertThat(current).contains(guideline);
        assertThat(current.replace(guideline, "")).isEqualTo(previous);
        assertThat(NaturalPromptInstructions.planningInstruction(version)).isEqualTo(NaturalPromptMaterialHandoffInstructions.PLAN);
        assertThat(NaturalPromptInstructions.requiresTaskReview(version)).isTrue();
    }

    @Test
    void currentTaskDirectCallKeepsTheRequestAndSendsOnlyApplicableContext() throws Exception {
        String raw = "我需要做一个常备流调队功能模块, 代码放在E:\\CompanyProject\\中科软\\二期项目\\智能流调\\btew-iefs-vue3\\src\\views\\ieis\\epidemiological-investigation-team\\epidemic-investigation目录中, 请先评估是否可以使用同一张数据库表，先给出结论，先不要修改代码。";
        String body = "本轮只评估能否共用数据库表，先查证再给出结论，不修改代码。";
        var withoutSchema = input(raw).withStrategyVersion(NaturalPromptExecutionDetailsInstructions.CURRENT_TASK_VERSION);
        assertDirectContext(withoutSchema, body, payload -> assertThat(payload.at("/context/fileSnippets")).isEmpty());
        var mapping = "专家流调队人员主表为 ieis_expert_member。活动记录表为 ieis_expert_activity，活动类型是字段而不是物理子表。";
        var withSchema = new NaturalPromptInput(raw, new ContextSnapshot("模块资料", List.of(), List.of(), List.of(), List.of(new FileSnippet(
                "team-schema.md", "markdown", mapping, "已核对的表映射", false, MaterialPurpose.FACT_REFERENCE)),
                List.of(), List.of(), "fixture"), List.of(), List.of(), List.of(), null)
                .withStrategyVersion(NaturalPromptExecutionDetailsInstructions.CURRENT_TASK_VERSION);
        assertDirectContext(withSchema, body, payload -> assertThat(payload.at("/context/fileSnippets/0/content").asText()).isEqualTo(mapping));
    }

    private void assertDirectContext(NaturalPromptInput input, String body, Consumer<JsonNode> verifyPayload) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expect(server, scope(input), input, NaturalPromptMaterialHandoffInstructions.PREPARE);
        expect(server, enhancement(input, body), input, NaturalPromptExecutionDetailsInstructions.CURRENT_TASK_ENHANCE, verifyPayload);
        var result = provider(builder.build()).enhance(input, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.optimizedPrompt()).isEqualTo(body);
        server.verify();
    }

    /** 各类模拟正文走相同的Provider入口。这里验证传输、协议和正文保留，模型补全质量由真实采样验证。 */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            在专家档案表单的性别后添加民族字段，复用已有民族字典并新增对应数据库字段。 | 在性别后新增民族字段，复用已有民族字典，沿现有提交和存储约定补齐数据库字段。核对选项、保存和重新打开后的回显。
            把统计表中已作废订单排除在净收入之外，退款仍按原规则处理。 | 仅调整净收入计算，排除已作废订单并沿用退款规则。用正常、作废和退款订单各核对一次结果。
            按现有模板整理会议纪要，区分已决定事项和待讨论事项，只输出纪要。 | 按现有模板整理会议纪要，将已决定事项与待讨论事项放在各自对应的位置。只输出纪要，不将待讨论事项写成决定。
            只改前端显示，把性别后的民族代码显示为字典标签。 | 只改前端显示，在性别后的民族位置沿用已有字典解析方式显示标签，保持接口、存储和表结构原样。
            民族字段已存在，只修改编辑表单以支持回显，禁止迁移。 | 在编辑表单中用已有民族字段回显选择，并核对打开已有记录时的显示。字段已存在，禁止迁移。
            写一则不超过20字的通知，提醒周五提前保存工作，只输出正文。 | 写一则不超过20字的通知，提醒周五提前保存工作，只输出正文。
            只读附件并理解，完成后等待。 | 只读附件并理解，完成后等待。
            写一份复核报告，不修改原文。 | 写一份复核报告，列出发现及对应原文位置，不修改原文。
            """)
    void executionDetailsUsesTheSameContractAcrossTasks(String raw, String body) throws Exception {
        var input = input(raw).withStrategyVersion(NaturalPromptExecutionDetailsInstructions.VERSION);
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expect(server, scope(input), input, NaturalPromptMaterialHandoffInstructions.PREPARE);
        expect(server, enhancement(input, body), input, NaturalPromptExecutionDetailsInstructions.ENHANCE);

        var result = provider(builder.build()).enhance(input, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.optimizedPrompt()).isEqualTo(body);
        assertThat(result.completedStages()).containsExactly(
                NaturalProcessing.Stage.SCOPE_ANALYSIS, NaturalProcessing.Stage.BODY_GENERATION);
        server.verify();
    }

    @Test
    void replacesWritingGuidanceWhileKeepingTheV5WireContract() {
        String marker = "只返回以下完整JSON对象";
        String previous = NaturalPromptProtocolRepairInstructions.ENHANCE;
        String current = NaturalPromptInstructions.enhancementInstruction(NaturalPromptExpressionInstructions.VERSION);
        assertThat(current.substring(current.indexOf(marker))).isEqualTo(previous.substring(previous.indexOf(marker)));
        assertThat(current).contains("明确指代、先后顺序和条件归属", "作为拟议做法表达")
                .doesNotContain("不能另设要向用户展示或交付的内容");
        assertThat(NaturalPromptInstructions.planningInstruction(NaturalPromptExpressionInstructions.VERSION))
                .isEqualTo(NaturalPromptExpressionInstructions.PLAN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"请帮我查询全球使用AI最多的职业/行业", "只阅读附件，理解后等待，不改文件。", "请写分析报告，只给报告，不执行修复。"})
    void normalEntryAlwaysGeneratesAndRetainsTheFullRequest(String raw) throws Exception {
        var input = input(raw);
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expect(server, scope(input), input, NaturalPromptExpressionInstructions.PREPARE);
        expect(server, enhancement(input, raw), input, NaturalPromptExpressionInstructions.ENHANCE);
        var result = provider(builder.build()).enhance(input, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        });
        assertThat(result.optimizedPrompt()).isEqualTo(raw);
        assertThat(result.completedStages()).containsExactly(NaturalProcessing.Stage.SCOPE_ANALYSIS, NaturalProcessing.Stage.BODY_GENERATION);
        server.verify();
    }

    @Test
    void missingBodyCoverageStillFailsAfterAProtocolRepair() throws Exception {
        var input = input("写一则通知，保留例外条件。");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expect(server, scope(input), input, NaturalPromptExpressionInstructions.PREPARE);
        var invalid = enhancement(input, input.rawPrompt());
        ((ObjectNode) invalid.at("/task/requirements/0")).putArray("bodyQuotes").add("正文没有这句话");
        expect(server, invalid, input, null);
        expect(server, invalid, input, null);
        assertThatThrownBy(() -> provider(builder.build()).enhance(input, draft -> {
            draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
            return draft;
        })).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"core-repair-v7", "core-repair-v8"})
    void noticeReviewPassesTheCallSiteAndCheckedScopeToTheWriter(String version) throws Exception {
        var callSite = new FileSnippet("src/MessageClient.java", "java", "request.setSignName(settings.getSignName());",
                "调用使用配置传入的签名", false, MaterialPurpose.TASK_TARGET);
        var reference = new FileSnippet("fixtures/migration.md", "text", "历史测试使用旧签名。", "",
                false, MaterialPurpose.DIAGNOSTIC_REFERENCE);
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(reference, callSite), List.of(), List.of(), "fixture");
        var input = new NaturalPromptInput("请查看下面的短信，我是否需要修改？通知称旧签名停用。", context,
                List.of(), List.of(), List.of(), null).withStrategyVersion(version);
        var scope = scope(input);
        ((ObjectNode) scope.at("/actions/0")).putArray("detailEvidence").addObject()
                .put("sourceId", "context.fileSnippets.1").put("quote", "request.setSignName");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expect(server, scope, input, NaturalPromptProjectTaskInstructions.PREPARE);
        String body = "沿 src/MessageClient.java 的签名传参追踪生效配置。对照通知判断是否需要修改并说明依据。";
        expect(server, enhancement(input, body), input, NaturalPromptInstructions.enhancementInstruction(version), payload -> {
            var links = payload.at("/taskScope/actions/0/evidence");
            assertThat(links).hasSize(2);
            assertThat(links.get(0).path("sourceId").asText()).isEqualTo("rawPrompt");
            assertThat(links.get(1).path("sourceId").asText()).isEqualTo("context.fileSnippets.1");
        });
        var draft = provider(builder.build()).enhance(input, result -> {
            result.taskUnderstanding().resolve(input).review().validateEnhancement(result.taskUnderstanding(), result.optimizedPrompt());
            return result;
        });
        assertThat(draft.optimizedPrompt()).isEqualTo(body);
        assertThat(draft.completedStages()).containsExactly(NaturalProcessing.Stage.SCOPE_ANALYSIS, NaturalProcessing.Stage.BODY_GENERATION);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"日期稍后确认，不承诺额外赔偿。", "没有相关资料，按现有信息继续。", "无权访问该系统，请保留这个限制。"})
    void currentAndPlanningMaterialsAndActualAnswersReachBothStages(String answer) throws Exception {
        for (String version : List.of(NaturalPromptExpressionInstructions.VERSION, NaturalPromptMaterialHandoffInstructions.VERSION)) {
            var input = materialInput(answer).withStrategyVersion(version);
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            expect(server, scope(input), input, NaturalPromptMaterialHandoffInstructions.VERSION.equals(version)
                    ? NaturalPromptMaterialHandoffInstructions.PREPARE : NaturalPromptExpressionInstructions.PREPARE);
            String body = input.rawPrompt() + answer;
            expect(server, enhancement(input, body), input, NaturalPromptInstructions.enhancementInstruction(version));
            var result = provider(builder.build()).enhance(input, draft -> {
                draft.taskUnderstanding().review().validateEnhancement(draft.taskUnderstanding(), draft.optimizedPrompt());
                return draft;
            });
            assertThat(result.optimizedPrompt()).isEqualTo(body);
            assertThat(input.context().fileSnippets().getFirst().content()).isEqualTo("病例一览表、密切接触者人员表、病例发病时间轴、传播关系图。");
            server.verify();
        }
    }

    @Test
    void realAnswerMustHaveItsOwnCoverageEvenWhenAnotherAnswerHasTheSameText() throws Exception {
        var input = materialInput("日期稍后确认，不承诺额外赔偿。");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expect(server, scope(input), input, NaturalPromptExpressionInstructions.PREPARE);
        var invalid = enhancement(input, input.rawPrompt() + input.actualAnswers().getFirst().answer());
        ((com.fasterxml.jackson.databind.node.ArrayNode) invalid.at("/task/requirements")).remove(2);
        expect(server, invalid, input, null);
        expect(server, invalid, input, null);
        assertThatThrownBy(() -> provider(builder.build()).enhance(input, draft -> draft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"core-expression-v1,日期稍后确认，不承诺额外赔偿。", "core-expression-v1,''",
            "core-repair-v7,日期稍后确认，不承诺额外赔偿。", "core-repair-v7,''",
            "core-repair-v8,日期稍后确认，不承诺额外赔偿。", "core-repair-v8,''",
            "execution-details-v1,日期稍后确认，不承诺额外赔偿。", "execution-details-v1,''",
            "execution-details-v2,日期稍后确认，不承诺额外赔偿。", "execution-details-v2,''"})
    void planSupportsNoQuestionAndAnImportantUserDecision(String version, String answer) throws Exception {
        var input = (answer.isEmpty() ? input("附件可多选，最大10MB，请先确认大小限制的含义。") : materialInput(answer))
                .withStrategyVersion(version);
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String preparation = switch (version) {
            case NaturalPromptExecutionDetailsInstructions.VERSION, NaturalPromptExecutionDetailsInstructions.CURRENT_TASK_VERSION
                    -> NaturalPromptMaterialHandoffInstructions.PREPARE;
            case NaturalPromptExpressionInstructions.VERSION -> NaturalPromptExpressionInstructions.PREPARE;
            default -> NaturalPromptProjectTaskInstructions.PREPARE;
        };
        expect(server, scope(input), input, preparation);
        ObjectNode plan = enhancement(input, input.rawPrompt());
        plan.remove(List.of("optimizedPrompt", "framework"));
        plan.put("summary", "整理原任务及需要用户决定的边界");
        var task = (ObjectNode) plan.get("task");
        task.remove(List.of("additions", "openDecisions"));
        task.putArray("unaskedDecisions");
        task.path("requirements").forEach(item -> ((ObjectNode) item).remove("bodyQuotes"));
        var questions = plan.putArray("questions");
        if (answer.isEmpty()) {
            var question = questions.addObject().put("id", "sizeLimit").put("question", "10MB限制单文件还是总大小？")
                    .put("hint", "影响多文件校验结果").put("type", "FREE_TEXT").put("allowCustomAnswer", true);
            question.putArray("options");
            question.putArray("examples");
            question.putArray("decisionBasis").addObject().put("issue", "大小限制对象").put("kind", "USER_INFORMATION")
                    .put("whyAsk", "需要用户明确业务限制对象").put("answerDifference", "决定按单文件或文件总量拒绝上传");
        }
        expect(server, plan, input, NaturalPromptInstructions.planningInstruction(version));
        var result = provider(builder.build()).plan(input, value -> value);
        assertThat(result.questions()).hasSize(answer.isEmpty() ? 1 : 0);
        server.verify();
    }

    @Test
    void metadataPatchKeepsBodyAndRejectsARewrittenBody() throws Exception {
        var input = input("保留原条件，整理这则通知。");
        for (boolean rewrite : List.of(false, true)) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            expect(server, scope(input), input, NaturalPromptExpressionInstructions.PREPARE);
            var invalid = enhancement(input, input.rawPrompt());
            ((ObjectNode) invalid.at("/task/requirements/0/evidence/0")).put("quote", "不存在的引文");
            expect(server, invalid, input, null);
            var patch = json.createObjectNode();
            var change = patch.putArray("changes").addObject().put("path", rewrite ? "/optimizedPrompt" : "/task/requirements/0/evidence");
            if (rewrite) change.put("value", "改写正文");
            else change.set("value", json.valueToTree(List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()))));
            expect(server, patch, input, null);
            if (rewrite) assertThatThrownBy(() -> provider(builder.build()).enhance(input, draft -> draft)).isInstanceOf(ProviderException.class);
            else assertThat(provider(builder.build()).enhance(input, draft -> draft).optimizedPrompt()).isEqualTo(input.rawPrompt());
            server.verify();
        }
    }

    @Test
    void projectMaterialCannotSupplyTheOnlyUserGoal() throws Exception {
        var input = materialInput("日期待定。");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expect(server, scope(input), input, NaturalPromptExpressionInstructions.PREPARE);
        var invalid = enhancement(input, input.rawPrompt() + input.actualAnswers().getFirst().answer());
        var requirement = (ObjectNode) invalid.at("/task/requirements/0");
        requirement.putArray("evidence").addObject().put("sourceId", "context.fileSnippets.0")
                .put("quote", input.context().fileSnippets().getFirst().content());
        expect(server, invalid, input, null);
        expect(server, invalid, input, null);
        assertThatThrownBy(() -> provider(builder.build()).enhance(input, draft -> draft)).isInstanceOf(ProviderException.class);
        server.verify();
    }

    private NaturalPromptInput materialInput(String answer) {
        var current = new ContextSnapshot("按当前资料整理原任务", List.of(), List.of(), List.of(), List.of(new FileSnippet(
                "appendix.md", "text", "病例一览表、密切接触者人员表、病例发病时间轴、传播关系图。", "附件栏目示意", false, MaterialPurpose.STYLE_REFERENCE)),
                List.of(), List.of(), "fixture");
        var planning = new ContextSnapshot("此前的材料", List.of(), List.of(), List.of(), List.of(new FileSnippet(
                "earlier.md", "text", "旧草案日期仅供核对。", "旧草案", false, MaterialPurpose.FACT_REFERENCE)), List.of(), List.of(), "fixture");
        return new NaturalPromptInput("按附件整理现有内容，保留我的实际回答。", current,
                List.of(new PlanAnswer("date", "日期何时确定？", answer), new PlanAnswer("scope", "有哪些限制？", answer)),
                List.of(), List.of(), null, planning).withStrategyVersion(NaturalPromptExpressionInstructions.VERSION);
    }

    private NaturalPromptInput input(String raw) {
        return new NaturalPromptInput(raw, new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "fixture"),
                List.of(), List.of(), List.of(), null).withStrategyVersion(NaturalPromptExpressionInstructions.VERSION);
    }

    private OpenAiCompatibleNaturalPromptProvider provider(RestClient client) {
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(URL));
        properties.setApiKey("fixture");
        properties.setModel("fixture");
        return new OpenAiCompatibleNaturalPromptProvider(client, json, properties, (PlatformModelCatalog) null);
    }

    private ObjectNode scope(NaturalPromptInput input) {
        var evidence = List.of(Map.of("sourceId", "rawPrompt", "quote", input.rawPrompt()));
        return json.valueToTree(Map.of("actions", List.of(Map.of("text", input.rawPrompt(), "timing", "CURRENT", "condition", "",
                "userEvidence", evidence, "detailEvidence", List.of())), "response", Map.of("basis", "UNSPECIFIED", "description", "",
                "userEvidence", List.of(), "detailEvidence", List.of())));
    }

    private ObjectNode enhancement(NaturalPromptInput input, String body) {
        var root = json.createObjectNode().put("optimizedPrompt", body).put("framework", "APE");
        var task = root.putObject("task").put("goal", input.rawPrompt()).put("deliverable", "原定结果").put("inputReadiness", "SUFFICIENT");
        var item = task.putArray("requirements").addObject().put("text", input.rawPrompt());
        item.putArray("dimensions").add("GOAL");
        item.putArray("evidence").addObject().put("sourceId", "rawPrompt").put("quote", input.rawPrompt());
        item.putArray("bodyQuotes").add(body);
        for (int i = 0; i < input.actualAnswers().size(); i++) {
            var answer = input.actualAnswers().get(i);
            var requirement = task.withArray("requirements").addObject().put("text", answer.answer());
            requirement.putArray("dimensions").add("USER_CONFIRMATION");
            requirement.putArray("evidence").addObject().put("sourceId", "actualAnswers." + i).put("quote", answer.answer());
            requirement.putArray("bodyQuotes").add(body);
        }
        task.putArray("additions");
        task.putArray("openDecisions");
        return root;
    }

    /** 核对实际发出的system与完整原始提示词，响应经真实DTO与覆盖读取器。 */
    private void expect(MockRestServiceServer server, JsonNode content, NaturalPromptInput input, String instruction) throws Exception {
        expect(server, content, input, instruction, payload -> { });
    }

    private void expect(MockRestServiceServer server, JsonNode content, NaturalPromptInput input, String instruction,
            Consumer<JsonNode> verifyPayload) throws Exception {
        String response = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                Map.of("content", json.writeValueAsString(content))))));
        server.expect(requestTo(URL)).andExpect(request -> {
            var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            if (instruction != null) assertThat(sent.at("/messages/0/content").asText()).isEqualTo(instruction);
            String user = sent.at("/messages/1/content").asText();
            var payload = json.readTree(user.substring(user.indexOf('{')));
            verifyPayload.accept(payload);
            if (payload.has("rawPrompt")) {
                assertThat(payload.path("rawPrompt").asText()).isEqualTo(input.rawPrompt());
                assertThat(payload.path("actualAnswers")).isEqualTo(json.valueToTree(input.actualAnswers()));
                if (NaturalPromptProjectTaskInstructions.supports(input.strategyVersion())) {
                    for (int i = 0; i < input.context().fileSnippets().size(); i++) {
                        var snippet = input.context().fileSnippets().get(i);
                        var sentFile = payload.at("/context/fileSnippets/" + i);
                        assertThat(sentFile.path("sourceId").asText()).isEqualTo("context.fileSnippets." + i);
                        assertThat(sentFile.path("path").asText()).isEqualTo(snippet.path());
                        assertThat(sentFile.path("content").asText()).isEqualTo(snippet.content());
                        assertThat(sentFile.path("purpose").asText()).isEqualTo(snippet.purpose().name());
                    }
                }
                if (input.planningContext() != null) {
                    assertThat(payload.at("/context/fileSnippets/0/sourceId").asText()).isEqualTo("context.fileSnippets.0");
                    assertThat(payload.at("/planningContext/fileSnippets/0/sourceId").asText()).isEqualTo("planningContext.fileSnippets.0");
                    assertThat(payload.at("/context/fileSnippets/0/content").asText()).isEqualTo(input.context().fileSnippets().getFirst().content());
                    assertThat(payload.at("/context/fileSnippets/0/purpose").asText()).isEqualTo("STYLE_REFERENCE");
                    assertThat(payload.at("/planProgress/state").asText()).isEqualTo("ANSWERS_RECEIVED");
                }
            }
        }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
}
