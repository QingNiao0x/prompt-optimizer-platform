package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.TaskEnhancementAssessment;
import com.promptoptimizer.template.domain.PromptTemplate;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 真实响应漏展示字段时只补元数据，不放宽实质缺口或非法枚举校验。 */
class MissingPresentationResponseTest {
    private static final String ENDPOINT = "https://model.example.com/v1/chat/completions";
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatiblePromptEnhancementProvider provider;

    @BeforeEach void setUp() {
        var client = RestClient.builder();
        server = MockRestServiceServer.bindTo(client).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("synthetic-test-key");
        properties.setModel("test-model");
        properties.setModels(List.of("test-model"));
        provider = new OpenAiCompatiblePromptEnhancementProvider(client.build(), json, properties);
    }

    @Test void missingDecorationDoesNotTriggerASecondPaidAttempt() throws Exception {
        var body = content("NEEDS_COMPLETION", true);
        server.expect(once(), requestTo(ENDPOINT)).andRespond(withSuccess(response(body), MediaType.APPLICATION_JSON));
        var result = provider.enhance(request());
        assertThat(result.assessment().presentation()).isEqualTo(TaskEnhancementAssessment.Presentation.STRUCTURED);
        assertThat(result.assessment().sufficiency()).isEqualTo(TaskEnhancementAssessment.Sufficiency.NEEDS_COMPLETION);
        assertThat(result.assessment().gaps()).hasSize(1);
        assertThat(result.sections()).extracting("content").contains("撰写原定方法正文，给出拟议评分方式及条件。");
        server.verify();
    }

    @Test void explicitInvalidPresentationIsStillRejected() throws Exception {
        var body = content("NEEDS_COMPLETION", true);
        ((com.fasterxml.jackson.databind.node.ObjectNode) body.get("assessment")).put("presentation", "UNKNOWN");
        server.expect(times(3), requestTo(ENDPOINT)).andRespond(withSuccess(response(body), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(request())).isInstanceOf(ProviderException.class);
        server.verify();
    }

    @Test void missingDecorationCannotHideAMissingSubstantiveGap() throws Exception {
        var body = content("NEEDS_COMPLETION", false);
        server.expect(times(3), requestTo(ENDPOINT)).andRespond(withSuccess(response(body), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(request())).isInstanceOf(ProviderException.class);
        server.verify();
    }

    /** 使用完整内部请求，测试仅针对传输解析，业务保真仍由实际组装入口独立验证。 */
    private EnhancementProviderRequest request() {
        return new EnhancementProviderRequest("为研究生撰写研究设计的方法部分，说明评价方式。",
                new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "test"),
                new PromptTemplate(TemplateCode.GENERAL, "按原文交付。", "保留原意。", "遵守示例选择。"),
                List.of(), List.of(), List.of(), new EnhancementOptions(TemplateCode.GENERAL, false, true, false), "test-model");
    }

    /** 不含展示字段的已复现结构，保留真正的专业补全而非虚构用户确认。 */
    private com.fasterxml.jackson.databind.node.ObjectNode content(String sufficiency, boolean includeGap) throws Exception {
        var root = json.createObjectNode();
        var assessment = root.putObject("assessment");
        assessment.put("sufficiency", sufficiency);
        var gaps = assessment.putArray("gaps");
        if (includeGap) gaps.addObject().put("kind", "PROFESSIONAL_OPTION").put("missing", "评分方式未具体化")
                .put("completion", "提出拟议评分方式及适用条件").put("basis", "用户委托设计评价方式");
        var sections = root.putArray("sections");
        sections.addObject().put("type", "BACKGROUND").put("title", "背景").put("content", "面向研究生，研究尚未实施。");
        sections.addObject().put("type", "TASK").put("title", "任务").put("content", "撰写原定方法正文，给出拟议评分方式及条件。");
        sections.addObject().put("type", "OUTPUT").put("title", "输出").put("content", "交付研究方法正文。");
        sections.addObject().put("type", "CONSTRAINTS").put("title", "约束").put("content", "拟议方法不等于用户已经确认。");
        root.putArray("ambiguities");
        return root;
    }

    /** 兼容端点返回真实协议形状，用实际解析器检验，不能直接构造已成功的结果。 */
    private String response(com.fasterxml.jackson.databind.JsonNode body) throws Exception {
        return json.writeValueAsString(Map.of("model", "test-model", "choices", List.of(Map.of(
                "message", Map.of("role", "assistant", "content", json.writeValueAsString(body))))));
    }
}
