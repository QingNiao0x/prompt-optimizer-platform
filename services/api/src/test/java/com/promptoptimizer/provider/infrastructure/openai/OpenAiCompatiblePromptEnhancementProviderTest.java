package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderFailureType;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.template.domain.PromptTemplate;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.promptoptimizer.context.domain.FileSnippet;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatiblePromptEnhancementProviderTest {

    private static final String ENDPOINT = "https://model.example.com/v1/chat/completions";
    private static final String API_KEY = "test-api-key";
    private static final String MODEL = "test-model";
    private static final String DEEPSEEK_ENDPOINT = "https://deepseek.example.com/v1/chat/completions";
    private static final String TOKENHUB_ENDPOINT = "https://tokenhub.example.com/v1/chat/completions";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private OpenAiCompatibleProperties properties;
    private OpenAiCompatiblePromptEnhancementProvider provider;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(java.net.URI.create(ENDPOINT));
        properties.setApiKey(API_KEY);
        properties.setModel(MODEL);
        properties.setModels(List.of(MODEL, "tokenhub-model"));
        properties.setProviderName("test-provider");
        provider = new OpenAiCompatiblePromptEnhancementProvider(
                builder.build(),
                objectMapper,
                properties
        );
    }

    @Test
    void shouldSendCompatibleRequestAndParseStructuredResponse() throws Exception {
        String structuredContent = """
                {
                  "sections": [
                    {"type":"BACKGROUND","title":"背景","content":"Spring Boot 项目"},
                    {"type":"TASK","title":"任务目标","content":"实现登录功能"},
                    {"type":"OUTPUT","title":"输入输出","content":"输入凭据，输出令牌"},
                    {"type":"CONSTRAINTS","title":"约束条件","content":"- 校验输入\\n- 防止暴力破解"},
                    {"type":"ACCEPTANCE","title":"验收标准","content":"测试全部通过"}
                  ]
                }
                """;
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "model", "resolved-model",
                "usage", Map.of("prompt_tokens", 18, "completion_tokens", 31, "total_tokens", 49),
                "choices", List.of(Map.of(
                        "message", Map.of(
                                "role", "assistant",
                                "content", "```json\n" + structuredContent + "\n```"
                        )
                ))
        ));

        server.expect(once(), requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].content").value(org.hamcrest.Matchers.containsString("增加登录功能")))
                .andExpect(jsonPath("$.max_tokens").value(3000))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        Logger callLogger = (Logger) LoggerFactory.getLogger(com.promptoptimizer.common.logging.ModelCallLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        callLogger.addAppender(appender);
        EnhancementProviderResponse response;
        try {
            response = provider.enhance(createRequest());
        } finally {
            callLogger.detachAppender(appender);
            appender.stop();
        }

        assertThat(response.provider()).isEqualTo("test-provider");
        assertThat(response.model()).isEqualTo("resolved-model");
        assertThat(response.mock()).isFalse();
        assertThat(response.sections()).extracting("type")
                .containsExactly(
                        PromptSectionType.BACKGROUND,
                        PromptSectionType.TASK,
                        PromptSectionType.OUTPUT,
                        PromptSectionType.CONSTRAINTS,
                        PromptSectionType.ACCEPTANCE
                );
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains("event=model.call.completed")
                        .contains("operation=prompt.optimize")
                        .contains("model=test-model")
                        .contains("inputTokens=18")
                        .contains("outputTokens=31")
                        .contains("totalTokens=49")
                        .doesNotContain(API_KEY, ENDPOINT));
        server.verify();
    }

    @Test
    void shouldMapRateLimitResponseToRetryableProviderException() {
        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"rate limited\"}}"));

        assertThatThrownBy(() -> provider.enhance(createRequest()))
                .isInstanceOfSatisfying(ProviderException.class, exception -> {
                    assertThat(exception.getFailureType()).isEqualTo(ProviderFailureType.RATE_LIMIT);
                    assertThat(exception.isRetryable()).isTrue();
                    assertThat(exception.getMessage()).doesNotContain("rate limited", API_KEY);
                });
        server.verify();
    }

    @Test
    void shouldRejectResponseWhenRequiredSectionsAreMissing() throws Exception {
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "model", MODEL,
                "choices", List.of(Map.of(
                        "message", Map.of(
                                "role", "assistant",
                                "content", "{\"sections\":[{\"type\":\"TASK\",\"title\":\"任务目标\",\"content\":\"实现登录\"}]}"
                        )
                ))
        ));
        server.expect(times(3), requestTo(ENDPOINT))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.enhance(createRequest()))
                .isInstanceOfSatisfying(ProviderException.class, exception -> {
                    assertThat(exception.getFailureType()).isEqualTo(ProviderFailureType.INVALID_RESPONSE);
                    assertThat(exception.isRetryable()).isFalse();
                });
        server.verify();
    }

    @Test
    void shouldGenerateDomainSpecificPlanningQuestions() throws Exception {
        String responseBody = objectMapper.writeValueAsString(Map.of(
                "model", "resolved-model",
                "choices", List.of(Map.of(
                        "message", Map.of(
                                "role", "assistant",
                                "content", """
                                        {"summary":"还需要确认研究地区。","questions":[{
                                          "id":"research-region",
                                          "question":"这项研究具体覆盖哪个地区？",
                                          "hint":"请填写省、市或区域名称。",
                                          "type":"FREE_TEXT",
                                          "options":[],
                                          "examples":["广东省"],
                                          "allowCustomAnswer":true
                                        }]}
                                        """
                        )
                ))
        ));
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.messages[0].content")
                        .value(org.hamcrest.Matchers.containsString("科研、教育、写作")))
                .andExpect(jsonPath("$.messages[1].content")
                        .value(org.hamcrest.Matchers.containsString("心脑血管疾病死亡率")))
                .andExpect(jsonPath("$.messages[1].content")
                        .value(org.hamcrest.Matchers.containsString("Spring Boot 3")))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        var response = provider.plan(new PlanningProviderRequest(
                "分析某地区心脑血管疾病死亡率",
                "公共卫生研究",
                List.of(),
                new PlanningContextDigest(
                        "Spring Boot 服务",
                        List.of("Spring Boot 3"),
                        List.of(),
                        List.of("pom.xml"),
                        List.of("pom.xml：Maven 项目配置"),
                        "COMPLETE",
                        1,
                        List.of()
                )
        ));

        assertThat(response.questions()).hasSize(1);
        assertThat(response.questions().get(0).question()).isEqualTo("这项研究具体覆盖哪个地区？");
        assertThat(response.questions().get(0).type()).isEqualTo(com.promptoptimizer.enhancement.domain.PlanQuestionType.FREE_TEXT);
        server.verify();
    }

    @Test
    void shouldPreserveRecommendationReasonFromPlanningResponse() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of(
                "content", """
                {"summary":"请确认实现方案","questions":[{"id":"framework","question":"采用哪种方案？",
                "hint":"需保持兼容","type":"SINGLE_CHOICE","options":[
                {"id":"vue","label":"Vue 3","description":"复用组件","answer":"使用 Vue 3",
                "recommended":true,"recommendationReason":"package.json 已使用 Vue 3"},
                {"id":"react","label":"React","description":"迁移组件","answer":"迁移至 React","recommended":false}],
                "examples":[],"allowCustomAnswer":true}]}
                """)))));
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.messages[0].content")
                        .value(org.hamcrest.Matchers.containsString("没有足够依据时允许没有推荐")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var response = provider.plan(new PlanningProviderRequest("开发订单页面", "Vue 项目", List.of()));
        assertThat(response.questions().getFirst().options().getFirst().recommendationReason())
                .isEqualTo("package.json 已使用 Vue 3");
        assertThat(response.questions().getFirst().options().get(1).recommendationReason()).isEmpty();
        server.verify();
    }

    @Test
    void shouldUseTheRequestedModelWhenItIsInTheServerAllowList() throws Exception {
        String responseBody = completionWithFindings("[]");
        server.expect(once(), requestTo(ENDPOINT))
                .andExpect(jsonPath("$.model").value("tokenhub-model"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        EnhancementProviderResponse response = provider.enhance(createRequest("tokenhub-model"));

        assertThat(response.model()).isEqualTo(MODEL);
        server.verify();
    }

    @Test
    void shouldRouteAQualifiedTokenHubModelToTheTokenHubEndpoint() throws Exception {
        OpenAiCompatibleRouteProperties deepseek = route(
                "deepseek",
                "https://deepseek.example.com/v1/chat/completions",
                "deepseek-secret",
                "deepseek-chat",
                List.of("deepseek-chat")
        );
        OpenAiCompatibleRouteProperties tokenhub = route(
                "tokenhub",
                TOKENHUB_ENDPOINT,
                "tokenhub-secret",
                "glm-5.3-flashx",
                List.of("glm-5.3-flashx")
        );
        LinkedHashMap<String, OpenAiCompatibleRouteProperties> routes = new LinkedHashMap<>();
        routes.put("deepseek", deepseek);
        routes.put("tokenhub", tokenhub);
        properties.setMultiProviderEnabled(true);
        properties.setDefaultProvider("deepseek");
        properties.setProviders(routes);
        provider = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), objectMapper, properties);

        server.expect(once(), requestTo(TOKENHUB_ENDPOINT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tokenhub-secret"))
                .andExpect(jsonPath("$.model").value("glm-5.3-flashx"))
                .andRespond(withSuccess(completionWithFindings("[]"), MediaType.APPLICATION_JSON));

        EnhancementProviderResponse response = provider.enhance(createRequest("tokenhub:glm-5.3-flashx"));

        assertThat(response.provider()).isEqualTo("tokenhub");
        assertThat(response.model()).isEqualTo("tokenhub:glm-5.3-flashx");
        server.verify();
    }

    @Test
    void shouldSendStructuredDeepSeekV41FlashRequestsToTheDirectEndpoint() throws Exception {
        OpenAiCompatibleRouteProperties deepseek = route(
                "deepseek",
                DEEPSEEK_ENDPOINT,
                "deepseek-secret",
                "deepseek-chat",
                List.of("deepseek-chat")
        );
        OpenAiCompatibleRouteProperties tokenhub = route(
                "tokenhub",
                TOKENHUB_ENDPOINT,
                "tokenhub-secret",
                "deepseek-v4-pro-0813",
                List.of("deepseek/deepseek-flash", "deepseek-v4-pro-0813")
        );
        properties.setMultiProviderEnabled(true);
        properties.setDefaultProvider("tokenhub");
        properties.setProviders(Map.of("deepseek", deepseek, "tokenhub", tokenhub));
        provider = new OpenAiCompatiblePromptEnhancementProvider(builder.build(), objectMapper, properties);

        server.expect(times(2), requestTo(DEEPSEEK_ENDPOINT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer deepseek-secret"))
                .andExpect(jsonPath("$.model").value("deepseek-flash"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andRespond(withSuccess(completionWithFindings("[]"), MediaType.APPLICATION_JSON));

        EnhancementProviderResponse current = provider.enhance(createRequest("deepseek:deepseek-flash"));
        EnhancementProviderResponse migrated = provider.enhance(createRequest("tokenhub:deepseek/deepseek-flash"));

        assertThat(current.provider()).isEqualTo("deepseek");
        assertThat(current.model()).isEqualTo("deepseek:deepseek-flash");
        assertThat(migrated.provider()).isEqualTo("deepseek");
        assertThat(migrated.model()).isEqualTo("deepseek:deepseek-flash");
        server.verify();
    }

    @Test
    void shouldRejectARequestedModelOutsideTheServerAllowList() {
        assertThatThrownBy(() -> provider.enhance(createRequest("not-configured")))
                .isInstanceOfSatisfying(ProviderException.class, exception -> {
                    assertThat(exception.getFailureType()).isEqualTo(ProviderFailureType.REQUEST_REJECTED);
                    assertThat(exception.isRetryable()).isFalse();
                });
    }

    @Test
    void shouldRepairStructuredRequestWhenFirstEnhancementResponseIsInvalid() throws Exception {
        String invalidResponse = objectMapper.writeValueAsString(Map.of(
                "model", MODEL,
                "choices", List.of(Map.of(
                        "message", Map.of(
                                "role", "assistant",
                                "content", "{\"sections\":[{\"type\":\"TASK\",\"title\":\"任务\",\"content\":\"缺少必要段落\"}]}"
                        )
                ))
        ));
        String validResponse = completionWithFindings("[]");
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(invalidResponse, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(ENDPOINT))
                .andExpect(jsonPath("$.max_tokens").value(6000))
                .andExpect(jsonPath("$.messages[2].content")
                        .value(org.hamcrest.Matchers.containsString("平台结构化输出修复要求")))
                .andRespond(withSuccess(validResponse, MediaType.APPLICATION_JSON));

        EnhancementProviderResponse response = provider.enhance(createRequest());

        assertThat(response.sections()).extracting("type")
                .contains(PromptSectionType.BACKGROUND, PromptSectionType.TASK,
                        PromptSectionType.OUTPUT, PromptSectionType.CONSTRAINTS);
        server.verify();
    }

    @Test
    void shouldRetryWithLargerOutputBudgetWhenModelReportsLengthLimit() throws Exception {
        String truncatedResponse = objectMapper.writeValueAsString(Map.of(
                "model", MODEL,
                "choices", List.of(Map.of(
                        "finish_reason", "length",
                        "message", Map.of("role", "assistant", "content", "{}")
                ))
        ));
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(truncatedResponse, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(ENDPOINT))
                .andExpect(jsonPath("$.max_tokens").value(6000))
                .andRespond(withSuccess(completionWithFindings("[]"), MediaType.APPLICATION_JSON));

        EnhancementProviderResponse response = provider.enhance(createRequest());

        assertThat(response.sections()).hasSize(4);
        server.verify();
    }

    private EnhancementProviderRequest createRequest() {
        return createRequest(null);
    }

    private EnhancementProviderRequest createRequest(String model) {
        ContextSnapshot context = new ContextSnapshot(
                "Spring Boot 用户服务",
                List.of(),
                List.of(),
                List.of("src/main/java/UserController.java"),
                List.of(new FileSnippet("src/main/java/UserController.java", "java",
                        "@PostMapping(\"/login\") public User login(LoginRequest input) { return service.login(input); }",
                        "登录控制器", false)),
                List.of(),
                List.of(),
                "v1"
        );
        PromptTemplate template = new PromptTemplate(
                TemplateCode.FEATURE_DEVELOPMENT,
                "说明接口输入输出",
                "提供自动化测试",
                "提供调用示例"
        );
        return new EnhancementProviderRequest(
                "给用户模块增加登录功能",
                context,
                template,
                List.of("登录方式未明确"),
                List.of("不得读取生产环境密钥"),
                List.of(),
                EnhancementOptions.defaults(),
                model
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[\"登录接口是否需要支持同一账号多设备同时在线？\"]"})
    void shouldAssessContextAndReturnFindingsInTheSameEnhancementCall(String findings) throws Exception {
        server.expect(once(), requestTo(ENDPOINT))
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("不得重复询问")))
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("不得因为没有")))
                .andExpect(jsonPath("$.messages[1].content").value(org.hamcrest.Matchers.containsString("@PostMapping")))
                .andExpect(jsonPath("$.messages[1].content").value(org.hamcrest.Matchers.containsString("Spring Boot")))
                .andRespond(withSuccess(completionWithFindings(findings), MediaType.APPLICATION_JSON));

        var response = provider.enhance(createRequest());
        assertThat(objectMapper.writeValueAsString(response.ambiguities())).isEqualTo(findings);
        server.verify();
    }

    @Test
    void shouldSendPlanBoundFactEvidenceAndSourceToTheFinalProvider() throws Exception {
        EnhancementProviderRequest base = createRequest();
        PlanningFactCard fact = new PlanningFactCard("F01", PlanningFactCategory.BUSINESS_RULE,
                "docs/订单审批方案.txt", "订单金额超过五万元时必须先由财务复核。");
        EnhancementProviderRequest request = new EnhancementProviderRequest(
                base.rawPrompt(), base.context(), base.template(), base.ambiguities(), base.planAnswers(),
                true, base.constraints(), base.conversationHistory(), base.options(), base.model(), List.of(fact));
        server.expect(once(), requestTo(ENDPOINT))
                .andExpect(jsonPath("$.messages[1].content").value(org.hamcrest.Matchers.containsString("订单审批方案.txt")))
                .andExpect(jsonPath("$.messages[1].content").value(org.hamcrest.Matchers.containsString("订单金额超过五万元时必须先由财务复核")))
                .andRespond(withSuccess(completionWithFindings("[]"), MediaType.APPLICATION_JSON));

        provider.enhance(request);

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "\"问题\"", "[3]", "[null]", "[\" \" ]",
            "[\"一\",\"二\",\"三\",\"四\",\"五\",\"六\",\"七\",\"八\",\"九\"]"})
    void shouldRejectMalformedAmbiguityArrayInsteadOfCoercingOrHidingIt(String findings) throws Exception {
        server.expect(times(3), requestTo(ENDPOINT))
                .andRespond(withSuccess(completionWithFindings(findings), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.enhance(createRequest()))
                .isInstanceOfSatisfying(ProviderException.class,
                        error -> assertThat(error.getFailureType()).isEqualTo(ProviderFailureType.INVALID_RESPONSE));
        server.verify();
    }

    private String completionWithFindings(String findings) throws Exception {
        String content = """
                {"sections":[
                  {"type":"BACKGROUND","title":"背景","content":"Spring Boot 用户服务"},
                  {"type":"TASK","title":"任务","content":"补充登录能力"},
                  {"type":"OUTPUT","title":"输出","content":"保持接口兼容并补充测试"},
                  {"type":"CONSTRAINTS","title":"约束","content":"保留安全边界"}
                ],"ambiguities":%s}
                """.formatted(findings);
        return objectMapper.writeValueAsString(Map.of("model", MODEL, "choices",
                List.of(Map.of("message", Map.of("role", "assistant", "content", content)))));
    }

    private OpenAiCompatibleRouteProperties route(
            String providerName,
            String endpoint,
            String apiKey,
            String model,
            List<String> models
    ) {
        OpenAiCompatibleRouteProperties properties = new OpenAiCompatibleRouteProperties();
        properties.setProviderName(providerName);
        properties.setEndpoint(java.net.URI.create(endpoint));
        properties.setApiKey(apiKey);
        properties.setModel(model);
        properties.setModels(models);
        return properties;
    }
}
