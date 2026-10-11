package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.OptimizationResult;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.service.impl.OptimizationResultAssembler;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.template.domain.PromptTemplate;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 通过真实适配器和结果组装器回放后段校验失败，验证安全约束与共享重试上限。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class OpenAiCompatibleValidatedResponseTest {
    private static final String ENDPOINT = "https://model.example.com/chat/completions";
    private static final String REPORT_REQUEST = """
            我是一名科研工作者,我想要撰写2015-2025年某地区心脑血管疾病死亡率特征分析,我需要从以下几个方面进行分析:
            1.时间序列的长期趋势和季节性趋势
            2.分性别\\地区\\分人群\\分亚类去进行比较
            3.进行减寿分析:年度的\\性别的yll和yll率
            4.采用Arriaga分解方法去分析""";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ContextSnapshot context = new ContextSnapshot("", List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), "v1");
    private final PromptTemplate template = new PromptTemplate(TemplateCode.GENERAL, "明确交付", "逐项核对", "示例");
    private MockRestServiceServer server;
    private OpenAiCompatiblePromptEnhancementProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleProperties properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("test-placeholder");
        properties.setModel("test-model");
        provider = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), mapper, properties);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRepairLegacyFindingsWithoutDroppingConditionsOrConfirmedAnswers(boolean confirmed) throws Exception {
        var invalid = content("保留原有功能", List.of());
        invalid.remove("ambiguities");
        @SuppressWarnings("unchecked")
        var sections = (List<Map<String, String>>) invalid.get("sections");
        sections.add(Map.of("type", "CLARIFICATIONS", "title", "待确认", "content",
                String.join("\n", IntStream.rangeClosed(1, 9).mapToObj(i -> "- 待确认条件" + i).toList())));
        expect(invalid);
        List<String> repaired = new ArrayList<>(IntStream.rangeClosed(1, 7).mapToObj(i -> "待确认条件" + i).toList());
        repaired.add("待确认条件8及待确认条件9");
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            String body = ((MockClientHttpRequest) request).getBodyAsString();
            assertThat(body).contains("AMBIGUITY_COUNT_INVALID");
        }).andRespond(withSuccess(completion(content("保留原有功能", repaired)), MediaType.APPLICATION_JSON));
        OptimizationResult result = provider.enhanceValidated(request(), response -> assemble(response, confirmed));
        assertThat(result.ambiguities()).anyMatch(value -> value.contains("条件9"));
        assertThat(result.optimizedPrompt()).contains("不得读取生产密钥", "保留原有功能");
        if (confirmed) assertThat(result.optimizedPrompt()).contains("只交付 Markdown 表格和验收清单");
        server.verify();
    }

    @Test
    void shouldRepairSensitiveLookingOutputWithoutSendingItsValueBackToTheModel() throws Exception {
        expect(content("password:请勿在任何位置记录真实密码", List.of()));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            String body = ((MockClientHttpRequest) request).getBodyAsString();
            assertThat(body).contains("SENSITIVE_CONTENT").doesNotContain("password:请勿");
        }).andRespond(withSuccess(completion(content("不得记录真实密码", List.of())), MediaType.APPLICATION_JSON));
        var result = provider.enhanceValidated(request(), response -> assemble(response, false));
        assertThat(result.optimizedPrompt()).contains("不得记录真实密码", "不得读取生产密钥");
        server.verify();
    }

    @Test
    void shouldShareThreeAttemptsBetweenParserAndApplicationValidation() throws Exception {
        expect(Map.of("sections", List.of()));
        expect(content("sk-DIAGNOSTIC0000000", List.of()));
        expect(content("保留原有功能", List.of()));
        assertThat(provider.enhanceValidated(request(), response -> assemble(response, false)).optimizedPrompt())
                .contains("保留原有功能").doesNotContain("sk-DIAGNOSTIC");
        server.verify();
    }

    @Test
    void shouldRejectSensitiveResponsesAfterTheSharedBudgetIsExhausted() throws Exception {
        server.expect(times(3), requestTo(ENDPOINT)).andRespond(withSuccess(
                completion(content("sk-DIAGNOSTIC0000000", List.of())), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhanceValidated(request(), response -> assemble(response, false)))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, error ->
                        assertThat(error.getReason()).isEqualTo(ProviderResponseValidationException.Reason.SENSITIVE_CONTENT));
        server.verify();
    }

    @Test
    void shouldNotRetryUnchangeableServerConflictEvidence() throws Exception {
        expect(content("保留原有功能", List.of()));
        var failure = new ProviderResponseValidationException(
                ProviderResponseValidationException.Reason.AMBIGUITY_VALUE_INVALID, "context.conflicts");
        assertThatThrownBy(() -> provider.enhanceValidated(request(), response -> { throw failure; }))
                .isSameAs(failure);
        server.verify();
    }

    @Test
    void shouldNotRetryUnexpectedApplicationErrors() throws Exception {
        expect(content("保留原有功能", List.of()));
        IllegalStateException unexpected = new IllegalStateException("unexpected assembly bug");
        assertThatThrownBy(() -> provider.enhanceValidated(request(), response -> { throw unexpected; }))
                .isSameAs(unexpected);
        server.verify();
    }

    private EnhancementProviderRequest request() {
        return new EnhancementProviderRequest("整理活动执行方案", context, template, List.of(),
                List.of("不得读取生产密钥"), List.of(), EnhancementOptions.defaults(), null);
    }

    @Test
    void shouldRepairReversedRulesAndKeepCopyPrerequisitesUsingTheSameBudget() throws Exception {
        expect(Map.of("sections", List.of()));
        expect(content("取消原值：移除保持原值的逻辑选项。", List.of()));
        server.expect(requestTo(ENDPOINT)).andExpect(request ->
                assertThat(((MockClientHttpRequest) request).getBodyAsString()).contains("RULE_CONFLICT"))
                .andRespond(withSuccess(completion(content("取消时保持原值。", List.of("匹配存在多条记录时使用哪一条？"))),
                        MediaType.APPLICATION_JSON));
        var result = provider.enhanceValidated(request(), response -> new OptimizationResultAssembler().assemble(
                response, context, template, List.of(), List.of(), false,
                List.of("不得读取生产密钥"), false, 1, "取消时保持原值。"));
        assertThat(result.optimizedPrompt()).contains("取消时保持原值", "执行前须确认", "匹配存在多条记录")
                .doesNotContain("移除保持原值");
        server.verify();
    }

    @Test
    void shouldFailClearlyWhenRuleReversalPersistsWithoutReturningASuccessfulDraft() throws Exception {
        server.expect(times(3), requestTo(ENDPOINT)).andRespond(withSuccess(
                completion(content("可以编造参考文献。", List.of())), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhanceValidated(request(), response -> new OptimizationResultAssembler().assemble(
                response, context, template, List.of(), List.of(), false, List.of(), false, 1, "不得编造参考文献。")))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(ProviderResponseValidationException.Reason.RULE_CONFLICT));
        server.verify();
    }

    /** 回放模型将分析正文改成方案的响应，必须修正文段，不能靠平台追加初稿条款放行。 */
    @Test
    void shouldRepairAReportDowngradedToAPlanBeforeReturningTheAssembledPrompt() throws Exception {
        expect(reportContent("为上述研究撰写一份可执行的分析方案，供下游执行者据此开展分析。",
                "交付一份结构化的分析方案文档，包括方法和表结构。"));
        String repairedTask = "撰写2015-2025年某地区心脑血管疾病死亡率特征分析正文，"
                + "覆盖长期与季节性趋势、分组比较、年度及性别YLL和YLL率、Arriaga分解四个方向。";
        String repairedOutput = "交付有实质内容的分析报告初稿，沿用四个分析方向；"
                + "缺少真实数据的数值和结论保持未核实，其余有依据的正文继续完成。";
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var messages = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString()).path("messages");
            assertThat(messages.get(messages.size() - 1).path("content").asText()).contains("RULE_CONFLICT");
            String input = messages.get(1).path("content").asText();
            assertThat(mapper.readTree(input.substring(input.indexOf('{'))).path("rawPrompt").asText())
                    .isEqualTo(REPORT_REQUEST);
        }).andRespond(withSuccess(completion(reportContent(repairedTask, repairedOutput)), MediaType.APPLICATION_JSON));

        var result = provider.enhanceValidated(reportRequest(), this::assembleReport);

        assertThat(result.sections().stream().filter(section -> section.type() == PromptSectionType.TASK)
                .findFirst().orElseThrow().content()).isEqualTo(repairedTask);
        assertThat(result.sections().stream().filter(section -> section.type() == PromptSectionType.OUTPUT)
                .findFirst().orElseThrow().content()).contains(repairedOutput);
        assertThat(result.optimizedPrompt()).doesNotContain("撰写一份可执行的分析方案", "交付一份结构化的分析方案文档")
                .contains("参考寿命表尚未确定", "Arriaga", "季节性", "YLL");
        server.verify();
    }

    /** 正确尾注不能掩盖旧错误任务；连续违约消耗同一三次预算后必须明确失败。 */
    @Test
    void shouldExhaustThreeAttemptsWhenReportDowngradesRemainBesideCorrectTailNotes() throws Exception {
        expect(reportContent("为上述研究撰写一份分析方案。", "交付一份分析方案文档。"));
        expect(reportContent("为上述研究撰写一份分析方案。",
                "交付完整分析报告正文，资料不足时完成有依据的初稿。"));
        server.expect(requestTo(ENDPOINT)).andExpect(request -> {
            var messages = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString()).path("messages");
            // 修复提示替换前一条提示；不能因反复追加正确尾注而把矛盾的模型响应视为成功。
            assertThat(messages).hasSize(3);
            assertThat(messages.get(2).path("content").asText()).contains("RULE_CONFLICT");
        }).andRespond(withSuccess(completion(reportContent("撰写2015-2025年某地区心脑血管疾病死亡率特征分析正文。",
                "交付一份结构化的分析方案文档。\n资料充分时交付报告正文，不足时完成有依据的初稿。")),
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.enhanceValidated(reportRequest(), this::assembleReport))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(ProviderResponseValidationException.Reason.RULE_CONFLICT));
        server.verify();
    }

    /** 使用实际任务画像与直接增强选项，保持截图任务未开启 Plan、示例且没有资料的条件。 */
    private EnhancementProviderRequest reportRequest() {
        return new EnhancementProviderRequest(REPORT_REQUEST, context,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, REPORT_REQUEST), List.of(), List.of(),
                List.of(), EnhancementOptions.defaults(), null);
    }

    /** 校验回调使用生产组装器，不能在测试里提前纠正模型的错误交付目标。 */
    private OptimizationResult assembleReport(EnhancementProviderResponse response) {
        return new OptimizationResultAssembler().assemble(response, context,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, REPORT_REQUEST),
                List.of(), List.of(), false, List.of(), false, 1, REPORT_REQUEST);
    }

    /** 只替换模型的任务与输出；真实缺口继续保留，证明修复没有靠删除未知换取通过。 */
    private Map<String, Object> reportContent(String task, String output) {
        return Map.of("sections", List.of(
                Map.of("type", "BACKGROUND", "title", "背景", "content", "研究目标是2015-2025年某地区心脑血管疾病死亡率特征。"),
                Map.of("type", "TASK", "title", "任务", "content", task),
                Map.of("type", "OUTPUT", "title", "输出", "content", output),
                Map.of("type", "CONSTRAINTS", "title", "约束", "content", "不编造数据、引用或统计结论。")),
                "ambiguities", List.of("参考寿命表尚未确定，仅影响依赖它的YLL计算。"));
    }

    private OptimizationResult assemble(EnhancementProviderResponse response, boolean confirmed) {
        return new OptimizationResultAssembler().assemble(response, context, template, List.of(),
                confirmed ? List.of(new PlanAnswer("q1", "输出格式是什么？", "只交付 Markdown 表格和验收清单")) : List.of(),
                confirmed, List.of("不得读取生产密钥"), false, 1);
    }

    private void expect(Map<String, Object> content) throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(completion(content), MediaType.APPLICATION_JSON));
    }

    private String completion(Map<String, Object> content) throws Exception {
        return mapper.writeValueAsString(Map.of("model", "test-model", "choices", List.of(Map.of(
                "finish_reason", "stop", "message", Map.of("role", "assistant", "content", mapper.writeValueAsString(content))))));
    }

    private Map<String, Object> content(String constraint, List<String> ambiguities) {
        Map<String, Object> content = new java.util.LinkedHashMap<>();
        List<Map<String, String>> sections = new ArrayList<>();
        for (String type : List.of("BACKGROUND", "TASK", "OUTPUT", "CONSTRAINTS")) {
            sections.add(Map.of("type", type, "title", type, "content", type.equals("CONSTRAINTS") ? constraint : "活动执行内容"));
        }
        content.put("sections", sections);
        content.put("ambiguities", ambiguities);
        return content;
    }
}
