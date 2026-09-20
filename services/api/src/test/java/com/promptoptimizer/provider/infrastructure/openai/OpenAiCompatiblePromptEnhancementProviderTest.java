package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.EnhancementOptions;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.EnhancementProviderResponse;
import com.promptoptimizer.provider.domain.ProviderException;
import com.promptoptimizer.provider.domain.ProviderFailureType;
import com.promptoptimizer.provider.domain.PlanningProviderRequest;
import com.promptoptimizer.template.domain.PromptTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatiblePromptEnhancementProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        OpenAiCompatibleProperties properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(java.net.URI.create(ENDPOINT));
        properties.setApiKey(API_KEY);
        properties.setModel(MODEL);
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
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].content").value(org.hamcrest.Matchers.containsString("增加登录功能")))
                .andExpect(jsonPath("$.max_tokens").value(3000))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        EnhancementProviderResponse response = provider.enhance(createRequest());

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
        server.expect(requestTo(ENDPOINT))
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

    private EnhancementProviderRequest createRequest() {
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
                EnhancementOptions.defaults()
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

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "\"问题\"", "[3]", "[null]", "[\" \" ]",
            "[\"一\",\"二\",\"三\",\"四\",\"五\",\"六\",\"七\",\"八\",\"九\"]"})
    void shouldRejectMalformedAmbiguityArrayInsteadOfCoercingOrHidingIt(String findings) throws Exception {
        server.expect(requestTo(ENDPOINT))
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
}
