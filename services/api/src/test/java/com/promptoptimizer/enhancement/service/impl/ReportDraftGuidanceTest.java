package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.PromptOptimizationGuidance;
import com.promptoptimizer.provider.infrastructure.MockPromptEnhancementProvider;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatiblePromptEnhancementProvider;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 复现科研正文被模板降为方案的指导缺口，核对真实请求携带初稿、事实及范围边界。
 * 模拟上游只证明指导传递，不证明真实下游已经交付合格报告。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ReportDraftGuidanceTest {
    private static final String RESEARCH_REPORT = "我是科研工作者，我想要撰写2015-2025年某地区心脑血管疾病死亡率特征分析，"
            + "我需要从以下几个方面进行分析：1.时间序列长期趋势和季节性趋势；2.分性别地区分人群分亚类进行比较；"
            + "3.进行减寿分析：年度的性别的YLL和YLL率；4.采用Arriaga分解方法去分析。";
    private final PromptTemplateRegistryImpl registry = new PromptTemplateRegistryImpl();

    @Test
    void researchWritingKeepsItsBodyInsteadOfBeingDowngradedToAPlan() {
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, RESEARCH_REPORT);
        assertThat(intent.templateCode()).isEqualTo(TemplateCode.RESEARCH_ANALYSIS);
        assertThat(intent.deliveryProfile()).isEqualTo(TaskDeliveryProfile.DATA_ANALYSIS);
        assertThat(intent.engineeringConstraints()).isFalse();

        var result = new MockPromptEnhancementProvider().enhance(request(RESEARCH_REPORT));
        assertThat(result.sections().stream().filter(section -> section.type() == PromptSectionType.TASK)
                .findFirst().orElseThrow().content()).contains("2015-2025", "长期趋势", "季节性趋势", "分性别", "YLL", "Arriaga");
        assertThat(result.sections().stream().filter(section -> section.type() == PromptSectionType.OUTPUT)
                .findFirst().orElseThrow().content()).contains("要求撰写分析正文或报告时", "初稿",
                        "不能用方案、提纲或补充信息清单替代正文", "真实数值", "结论", "不默认未知口径");
    }

    @Test
    void anAnalysisPlanStillDeliversOnlyTheRequestedPlanAndEmptyTables() {
        String raw = "依据模拟月度数据制定统计分析方案，只交付方案和空表，不运行程序，不写报告或真实分析结果。";
        var template = registry.resolve(TemplateCode.AUTO, raw);
        assertThat(template.deliveryProfile()).isEqualTo(TaskDeliveryProfile.DATA_ANALYSIS);
        assertThat(template.outputGuidance()).contains("要求方案或表结构时只交付原定内容")
                .doesNotContain("必须交付报告", "必须先生成报告");
        var result = new MockPromptEnhancementProvider().enhance(request(raw));
        assertThat(result.sections().stream().filter(section -> section.type() == PromptSectionType.TASK)
                .findFirst().orElseThrow().content()).contains("只交付方案和空表", "不运行程序", "不写报告或真实分析结果");
    }

    @Test
    void theActualEnhancementRequestCarriesDraftProgressAndItsExceptions() throws Exception {
        var mapper = new ObjectMapper();
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create("https://report-guidance.example.test/v1/chat/completions"));
        properties.setApiKey("test-report-guidance-key");
        properties.setModel("test-report-model");
        properties.setModels(List.of("test-report-model"));
        var provider = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), mapper, properties);
        String response = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", """
                {"sections":[
                  {"type":"BACKGROUND","title":"背景","content":"原始需求已给出研究年份及分析方向。"},
                  {"type":"TASK","title":"任务","content":"撰写原定分析正文，保持资料边界。"},
                  {"type":"OUTPUT","title":"输出","content":"先交付可修订初稿，未知结果在对应位置说明。"},
                  {"type":"CONSTRAINTS","title":"约束","content":"不编造数据和研究结论。"}
                ],"ambiguities":[],"warnings":[]}
                """)))));
        // 请求匹配检查真实适配器拼装后的 system/user 消息，避免只检查未被使用的常量。
        server.expect(requestTo(properties.getEndpoint()))
                .andExpect(jsonPath("$.messages[0].content").value(containsString("先完成现有依据支持的有用初稿")))
                .andExpect(jsonPath("$.messages[0].content").value(containsString("不能只交提纲或先发问卷等待补齐")))
                .andExpect(jsonPath("$.messages[0].content").value(containsString("缺口只限制依赖它的内容")))
                .andExpect(jsonPath("$.messages[0].content").value(containsString("用户明确要求先提问")))
                .andExpect(jsonPath("$.messages[0].content").value(containsString("只输出译文、只读比较或代码")))
                .andExpect(jsonPath("$.messages[0].content").value(containsString("执行前确认仍须遵守")))
                .andExpect(jsonPath("$.messages[1].content").value(containsString("要求撰写分析正文或报告时")))
                .andExpect(jsonPath("$.messages[1].content").value(containsString("Arriaga")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThat(provider.enhance(request(RESEARCH_REPORT)).sections()).hasSize(4);
        server.verify();
    }

    @Test
    void professionalSuggestionsNeverBecomeSelectedParametersOrStudyFacts() {
        for (String guidance : List.of(PromptOptimizationGuidance.ENHANCEMENT, PromptOptimizationGuidance.PLANNING)) {
            assertThat(guidance).contains("本项目或研究的具体事实、已选参数和实际结果", "专业建议", "适用前提",
                    "不得写成用户已确认、资料已证实或研究已完成", "建议、示例和待选参数不得升级为已确认事实")
                    .doesNotContain("具体细节只能来自原始需求、相关材料或有效确认。");
        }
        assertThat(PromptOptimizationGuidance.ENHANCEMENT).contains("无法可靠给出的数值、结论和未决专业口径",
                "不能用示例值或惯例填成真实结果", "新增指标、计算或交付物");
    }

    @Test
    void translationMethodsAndCodeKeepTheirExistingDeliveryProfiles() {
        assertThat(registry.resolve(TemplateCode.AUTO, "翻译为英语，只输出译文：明天开会。").outputGuidance())
                .contains("不附解释").doesNotContain("报告初稿", "补充信息清单");
        assertThat(registry.resolve(TemplateCode.AUTO, "制定研究方案，只输出方法提纲和空表。").outputGuidance())
                .contains("只交付指定论文部分或研究方案", "不补写实际结果");
        assertThat(registry.resolve(TemplateCode.FEATURE_DEVELOPMENT, "开发登录接口。").outputGuidance())
                .contains("关键代码", "接口契约").doesNotContain("报告初稿");
        assertThat(PromptOptimizationGuidance.ENHANCEMENT).contains("仅要求方案、提纲、只输出译文、只读比较或代码",
                "不新增报告或附录", "禁止占位", "明确的执行前确认仍须遵守");
    }

    /** 沿用正式注册中心构造请求，使本例经过实际交付画像选择。 */
    private EnhancementProviderRequest request(String raw) {
        var context = new ContextSnapshot("仅有原始需求，未提供实际统计数据。", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "report-guidance-test");
        return new EnhancementProviderRequest(raw, context, registry.resolve(TemplateCode.AUTO, raw), List.of(),
                List.of(), List.of(), new EnhancementOptions(TemplateCode.AUTO, false, true, false), null);
    }
}
