package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 将九至十四的真实原件交给生产组装器，区分模型已写内容和平台重复追加；不调用模型。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CandidateTwelveAssemblyReplayTest {
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @ParameterizedTest
    @CsvSource({"9,205,q05-flash/input.json,true", "10,215,replay/plan/input.json,true",
            "10,217,replay/direct/input.json,false", "11,219,direct-copy/input.json,false",
            "12,220,live/plan/input.json,true", "12,221,live/direct/input.json,false",
            "13,230,live/plan/input.json,true", "13,231,live/direct/input.json,false",
            "14,240,live/plan/replay-input.json,true", "14,241,live/direct/replay-input.json,false"})
    void shouldNotReappendCoveredDelegationTaskIntroductionOrAnswerHeading(
            int candidate, int attempt, String inputFile, boolean plan) throws Exception {
        var input = fixture(candidate, inputFile);
        String raw = input.path("rawPrompt").asText();
        assertThat(raw).isNotBlank();
        var answers = answers(plan);
        var result = assemble(model(candidate, attempt), raw, answers);
        if (attempt != 221 && attempt != 240) assertThat(result.optimizedPrompt()).doesNotContain(
                "- 请根据原始研究目标提出可开展的推荐设计并说明适用条件");
        assertThat(result.optimizedPrompt()).doesNotContain(
                "- 为研究生撰写中文研究设计的方法部分，研究Plan问答对提示词质量的影响。",
                "- 本次测试实际确认信息（逐字题答）：");
        assertThat(result.optimizedPrompt()).contains("研究生", "方法部分", "Plan", "拟议", "800", "1200");
        if (attempt == 219) {
            assertThat(result.optimizedPrompt()).doesNotContain("- 设计必须保留：4类任务，每类5个，共20个任务",
                    "- 比较直接增强和Plan，并另设两组获得相同确认信息的公平对照。",
                    "- 不提供代码，不得把拟定样本写成已纳入样本，不得编造效应量、P值、参考文献或显著性结论，不得使用假设数据。",
                    "- 不确认任何既有参数。");
            // 模型正文没有落实这项真实资料状态时仍补入；不能为了缩短正文抹掉缺项。
            assertThat(result.optimizedPrompt()).contains("- 我没有额外真实数据");
        }
        if (attempt == 220) {
            assertThat(result.optimizedPrompt()).contains("尚未实施，没有额外真实数据，不确认任何既有参数。");
            assertThat(result.optimizedPrompt()).doesNotContain("- 我没有额外真实数据");
        }
        if (attempt == 221) {
            // 部分事项有适用条件，不能证明整个委托已覆盖；缺少的资料与全称未确认状态仍须补入。
            assertThat(result.optimizedPrompt()).contains("- 面向研究生读者。",
                    "- 请根据原始研究目标提出可开展的推荐设计并说明适用条件",
                    "- 我没有额外真实数据", "- 不确认任何既有参数。");
        }
        if (attempt == 230) {
            // 同对象的拟议设计要求与实际未确认边界已完整落入正文，不再复制原始问题标题。
            assertThat(result.optimizedPrompt()).doesNotContain("- 已委托提出拟议设计，实际采用参数仍未确认：");
            assertThat(result.ambiguities()).hasSize(4);
            assertThat(result.optimizedPrompt()).contains("不确认任何既有参数", "拟议主方案", "适用条件");
        }
        if (attempt == 231) {
            // 同句禁假设数据不应让已经完整表达的“尚未实施”被误判为条件分支。
            assertThat(result.optimizedPrompt()).doesNotContain("- 当前只是拟定研究，尚未实施。");
            assertThat(result.optimizedPrompt()).contains("- 我没有额外真实数据");
        }
        if (attempt == 240) {
            // 已委托的字段应作为正向任务交付，未确认状态继续保存；不让题答标题替代设计。
            var taskSection = result.sections().stream().filter(section -> section.type()
                    == com.promptoptimizer.enhancement.domain.PromptSectionType.TASK).findFirst().orElseThrow();
            assertThat(taskSection.content()).contains("拟议主方案", "4类任务的分类框架", "每类5个任务的构造来源",
                    "盲评的设盲范围", "公平对照组的确认信息获取流程", "拟议评分尺度", "可操作定义");
            assertThat(result.optimizedPrompt()).doesNotContain("- 已委托提出拟议设计，实际采用参数仍未确认：");
            assertThat(result.ambiguities()).hasSize(4);
            assertThat(result.optimizedPrompt()).contains("不确认任何既有参数");
        }
        if (plan) {
            var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, answers, List.of(), false);
            assertThat(task.pendingDecisions()).hasSize(4);
            assertThat(task.modelInput().pendingDecisions()).allMatch(value -> value.designDelegated());
            assertThat(task.modelInput().executionRequirements().stream()
                    .filter(value -> value.subject().equals("委托要求")).findFirst().orElseThrow().sourceQuestionIds())
                    .containsExactlyElementsOf(answers.stream().map(PlanAnswer::questionId).toList());
        }
        verifyHistoryRoundTripAndOptionallyExport(attempt, raw, answers, result);
    }

    @Test
    void participantHeadingFirstAppearedInTheEnhancementResponseRatherThanPlatformAppend() throws Exception {
        var trace = fixture(10, "traces/215.json");
        var requestMessages = new ArrayList<String>();
        trace.path("request").path("messages").forEach(message -> requestMessages.add(message.path("content").asText()));
        assertThat(requestMessages).noneMatch(message -> message.contains("参与者与随机化"));
        assertThat(model(10, 215).path("sections").toString()).contains("参与者与随机化");
        var input = fixture(10, "replay/plan/input.json");
        assertThat(assemble(model(10, 215), input.path("rawPrompt").asText(), answers(true)).optimizedPrompt())
                .contains("参与者与随机化");
        // 来源定位不等于该栏目一定不合法，更不允许据此全局禁止有条件的人类参与者设计。
    }

    /** 重放内置模板与安全校验，不用更容易通过的手造模型段落代替真实响应。 */
    private com.promptoptimizer.enhancement.domain.OptimizationResult assemble(JsonNode model, String raw,
            List<PlanAnswer> answers) throws Exception {
        var sections = new ArrayList<PromptSection>();
        for (var section : model.path("sections")) sections.add(json.treeToValue(section, PromptSection.class));
        var ambiguities = new ArrayList<String>();
        model.path("ambiguities").forEach(value -> ambiguities.add(value.asText()));
        var response = new EnhancementProviderResponse(sections, "deepseek", "deepseek-flash", false, ambiguities,
                List.of(), json.treeToValue(model.path("assessment"), TaskEnhancementAssessment.class));
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "v1");
        var template = new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw,
                ConfirmedDecisionSet.from(answers).knownDecisions());
        return new OptimizationResultAssembler().assemble(response, context, template, List.of(), answers,
                !answers.isEmpty(), List.of(), false, 1, raw);
    }

    /** 实际四题逐字回答只从九的原件读取，不补充或修复平台遗漏的信息。 */
    private List<PlanAnswer> answers(boolean plan) throws Exception {
        if (!plan) return List.of();
        var answers = new ArrayList<PlanAnswer>();
        for (var answer : fixture(9, "q05-flash/actual-answers.json").path("answers"))
            answers.add(json.treeToValue(answer, PlanAnswer.class));
        return List.copyOf(answers);
    }

    /** 只读取已提交的公开夹具，原始响应和历史文件不改写。 */
    private JsonNode fixture(int candidate, String name) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null) {
            String date = candidate >= 13 ? "2026-10-15" : "2026-10-14";
            Path file = root.resolve("docs/testing/evidence/me00a-candidate" + candidate + "-" + date).resolve(name);
            if (Files.isRegularFile(file)) return json.readTree(Files.readString(file));
            root = root.getParent();
        }
        throw new IllegalStateException("缺少冻结原件：" + candidate + "/" + name);
    }

    private JsonNode model(int candidate, int attempt) throws Exception {
        return json.readTree(fixture(candidate, "traces/" + attempt + ".json").path("response")
                .path("choices").get(0).path("message").path("content").asText());
    }

    /**
     * 使用真实历史转换服务及受控身份夹具验证复制正文和恢复正文；持久化边界用 Mock，不连接或修改用户数据库。
     * 导出仅写本仓库固定 tmp 目录，派生回放不能被当成新增真实模型响应或新数据库历史。
     */
    private void verifyHistoryRoundTripAndOptionallyExport(int attempt, String raw, List<PlanAnswer> answers,
            com.promptoptimizer.enhancement.domain.OptimizationResult result) throws Exception {
        var actor = new com.promptoptimizer.identity.service.ActorIdentity(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"), "demo@local", "Local Demo User");
        var currentActor = mock(com.promptoptimizer.identity.service.CurrentActor.class);
        var mapper = mock(com.promptoptimizer.history.mapper.OptimizationRecordMapper.class);
        var persistence = mock(com.promptoptimizer.history.service.impl.OptimizationHistoryPersistenceService.class);
        when(currentActor.require()).thenReturn(actor);
        var captured = new java.util.concurrent.atomic.AtomicReference<com.promptoptimizer.history.entity.OptimizationRecordEntity>();
        when(persistence.persist(any(), eq(actor))).thenAnswer(invocation -> {
            com.promptoptimizer.history.entity.OptimizationRecordEntity entity = invocation.getArgument(0);
            captured.set(entity);
            return entity.getId();
        });
        var history = new com.promptoptimizer.history.service.impl.MybatisOptimizationHistoryService(mapper, persistence,
                mock(com.promptoptimizer.enhancement.service.EnhancementOrchestrator.class), currentActor, json);
        var confirmation = answers.isEmpty() ? null : new com.promptoptimizer.enhancement.dto.PlanConfirmation(
                "00000000-0000-0000-0000-000000000004", null, answers);
        var request = new com.promptoptimizer.enhancement.dto.OptimizationRequest(raw, null, null, List.of(), null, confirmation);
        var savedId = history.save(request, result);
        var entity = captured.get();
        when(mapper.selectByIdAndScope(savedId, actor.tenantId(), actor.workspaceId())).thenReturn(entity);
        var restored = history.get(savedId);
        assertThat(entity.getRawPrompt()).isEqualTo(raw);
        assertThat(entity.getOptimizedPrompt()).isEqualTo(result.optimizedPrompt());
        assertThat(restored.optimizedPrompt()).isEqualTo(result.optimizedPrompt());
        assertThat(restored.sections()).isEqualTo(result.sections());
        if (!answers.isEmpty()) {
            assertThat(json.valueToTree(entity.getResultMetadata()).path("planConfirmation").path("answers"))
                    .isEqualTo(json.valueToTree(answers));
        }
        verify(mapper).selectByIdAndScope(savedId, actor.tenantId(), actor.workspaceId());
        if (!Boolean.getBoolean("me00a.exportReplayEvidence")) return;
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("AGENTS.md"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("缺少仓库根目录");
        // 后续回放写入本节点目录，已冻结的候选十二原件保持只读。
        String node = System.getProperty("me00a.replayNode", "candidate12");
        if (!node.matches("candidate(?:12|13|14|15)")) throw new IllegalStateException("非法回放节点");
        String date = node.equals("candidate14") || node.equals("candidate15") ? "20261015" : "20261014";
        Path output = root.resolve("tmp/me00a-" + node + "-" + date + "/replays/" + attempt);
        Files.createDirectories(output);
        Files.writeString(output.resolve("current-copy.md"), result.optimizedPrompt());
        Files.writeString(output.resolve("result.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        Files.writeString(output.resolve("history-payload.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("rawPrompt", raw, "optimizedPrompt", entity.getOptimizedPrompt(), "resultMetadata", entity.getResultMetadata())));
        Files.writeString(output.resolve("checks.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "sourceAttempt", attempt, "offlineReplay", true, "newModelCalls", 0,
                "historyPersistenceBoundary", "MOCK_NO_DATABASE_WRITE", "copiedEqualsHistoryPayload", true,
                "copiedEqualsRestoredHistory", true, "rawPromptUnchanged", true,
                "actualAnswerCount", answers.size(), "noScriptAnswerRepair", true)));
    }
}
