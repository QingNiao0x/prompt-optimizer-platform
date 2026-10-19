package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.EffectiveTaskView;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.policy.domain.ConstraintBundle;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatiblePromptEnhancementProvider;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 回放第 472 次的真实引用格式失败，核对有限修复与来源作用域保护同时生效。
 * Mock 上游只验证恢复协议，不将人工修正响应当作模型质量复验通过。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class RequirementReferenceRecoveryTest {
    private static final String ENDPOINT = "https://model.example.test/v1/chat/completions";
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode fixture;
    private ObjectNode originalResponse;
    private EffectiveTaskView task;
    private ConstraintBundle constraints;
    private EnhancementProviderRequest request;
    private BoundRequirementProjection.Slot source;
    private MockRestServiceServer server;
    private OpenAiCompatiblePromptEnhancementProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        try (var stream = getClass().getResourceAsStream("/fixtures/requirement-reference-472.json")) {
            fixture = json.readTree(stream);
        }
        String raw = fixture.path("rawPrompt").asText();
        String modelResponse = fixture.path("modelResponseRaw").asText();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(modelResponse.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(fixture.path("modelResponseSha256").asText());
        originalResponse = (ObjectNode) json.readTree(modelResponse);
        var context = new ContextSnapshot("", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "EMPTY");
        task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, List.of(), List.of(), false);
        constraints = new ConstraintCompleterImpl().completeForTask(context, PermissionPolicyInput.empty(), false, task.intent());
        request = new EnhancementProviderRequest(raw, context,
                new PromptTemplateRegistryImpl().resolve(TemplateCode.AUTO, raw), List.of(), List.of(),
                fixture.path("planConfirmed").asBoolean(), constraints.visibleConstraints(), List.of(),
                new EnhancementOptions(TemplateCode.AUTO, false, true, false, PromptFormat.COMPACT), "test-model",
                List.of(), List.of(), task);
        var references = BoundRequirementProjection.forProvider(request);
        JsonNode serializedReferences = json.valueToTree(references);
        assertThat(serializedReferences).isEqualTo(fixture.path("requirementReferences"));
        source = references.getFirst();
        var client = RestClient.builder();
        server = MockRestServiceServer.bindTo(client).build();
        var properties = new OpenAiCompatibleProperties();
        properties.setEndpoint(URI.create(ENDPOINT));
        properties.setApiKey("synthetic-test-key");
        properties.setModel("test-model");
        properties.setModels(List.of("test-model"));
        provider = new OpenAiCompatiblePromptEnhancementProvider(client.build(), json, properties);
    }

    @Test
    void actual472IsARepairableReferenceViolationWithoutLosingAnySource() {
        var sections = sections(originalResponse);
        var unchanged = Map.copyOf(sections);
        assertThat(task.confirmedDecisions()).isEmpty();
        assertThat(sections.get(source.destination()).content()).startsWith(source.reference() + "只使用记录");
        assertThatThrownBy(() -> BoundRequirementProjection.expandTracked(sections, task, constraints))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, error -> {
                    assertThat(error.getReason()).isEqualTo(Reason.REQUIREMENT_REFERENCE_INVALID);
                    assertThat(error.getField()).isEqualTo("sections.CONSTRAINTS.requirementReference");
                    assertThat(error.isModelRepairable()).isTrue();
                });
        assertThat(sections).isEqualTo(unchanged);
        assertThat(BoundRequirementProjection.forProvider(request)).containsExactly(source);
    }

    @Test
    void actual472UsesTheExistingRepairLoopThenExpandsOnlyTheValidStandaloneSource() throws Exception {
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(envelope(originalResponse), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(ENDPOINT)).andExpect(httpRequest -> {
            var body = json.readTree(((MockClientHttpRequest) httpRequest).getBodyAsString());
            var messages = body.path("messages");
            String repair = messages.get(messages.size() - 1).path("content").asText();
            assertThat(repair).contains("REQUIREMENT_REFERENCE_INVALID", "sections.CONSTRAINTS.requirementReference",
                    "独立一行", "不得发明引用或更改来源要求")
                    .doesNotContain("CONFIRMED_DECISION_MISSING", "不添加记录中未出现的负责人");
            assertThat(messages.get(1).path("content").asText()).contains(request.rawPrompt(), source.reference());
        }).andRespond(withSuccess(envelope(standaloneResponse()), MediaType.APPLICATION_JSON));

        var result = provider.enhanceValidated(request, response -> {
            var sections = sections(response.sections());
            var expansion = BoundRequirementProjection.expandTracked(sections, task, constraints);
            assertThat(expansion.coveredRules(sections)).containsExactly(source.statement());
            return sections;
        });
        assertThat(result.get(source.destination()).content()).isEqualTo(source.statement() + "\n"
                + sections(originalResponse).get(source.destination()).content().substring(source.reference().length()));
        assertThat(BoundRequirementProjection.forProvider(request)).containsExactly(source);
        server.verify();
    }

    @Test
    void repeatedReferenceViolationsStillStopAfterThreeTotalCalls() throws Exception {
        server.expect(times(3), requestTo(ENDPOINT))
                .andRespond(withSuccess(envelope(originalResponse), MediaType.APPLICATION_JSON));
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> provider.enhanceValidated(request, response -> {
            attempts.incrementAndGet();
            return BoundRequirementProjection.expandTracked(sections(response.sections()), task, constraints);
        })).isInstanceOfSatisfying(ProviderResponseValidationException.class, error -> {
            assertThat(error.getReason()).isEqualTo(Reason.REQUIREMENT_REFERENCE_INVALID);
            assertThat(error.getModelAttempts()).isEqualTo(3);
        });
        assertThat(attempts).hasValue(3);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "WRONG_DESTINATION", "CONDITIONAL", "NEGATED", "SUFFIX_CONDITION",
            "QUOTED", "CODE", "DUPLICATE_CONDITIONAL"})
    void repairEligibilityNeverMakesInvalidSourceScopesExpandable(String violation) {
        var sections = sections(standaloneResponse());
        String reference = source.reference();
        PromptSectionType destination = violation.equals("WRONG_DESTINATION") ? PromptSectionType.TASK : source.destination();
        String content = switch (violation) {
            case "UNKNOWN" -> "{{REQ_foreign_source}}";
            case "WRONG_DESTINATION" -> reference;
            case "CONDITIONAL" -> "如果以后批准：\n" + reference;
            case "NEGATED" -> "不得" + reference;
            case "SUFFIX_CONDITION" -> reference + "仅当用户批准后执行";
            case "QUOTED" -> "> " + reference;
            case "CODE" -> "```text\n" + reference + "\n```";
            case "DUPLICATE_CONDITIONAL" -> reference + "\n如果以后批准：\n" + reference;
            default -> throw new IllegalArgumentException(violation);
        };
        sections.put(destination, new PromptSection(destination, "任务规则", content));
        var unchanged = Map.copyOf(sections);
        assertThatThrownBy(() -> BoundRequirementProjection.expandTracked(sections, task, constraints))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, error -> {
                    assertThat(error.getReason()).isEqualTo(Reason.REQUIREMENT_REFERENCE_INVALID);
                    assertThat(error.isModelRepairable()).isTrue();
                });
        assertThat(sections).isEqualTo(unchanged);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMED_DECISION", "CONTEXT", "METADATA"})
    void immutableServerFailuresStillDoNotAskTheModelToRepairThem(String failure) throws Exception {
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(envelope(standaloneResponse()), MediaType.APPLICATION_JSON));
        Reason reason = switch (failure) {
            case "CONFIRMED_DECISION" -> Reason.CONFIRMED_DECISION_MISSING;
            case "CONTEXT" -> Reason.REQUIREMENT_REFERENCE_INVALID;
            case "METADATA" -> Reason.METADATA_INVALID;
            default -> throw new IllegalArgumentException(failure);
        };
        String field = failure.equals("CONTEXT") ? "context.requirementReference" : "sections.CONSTRAINTS";
        var originalFailure = new ProviderResponseValidationException(reason, field);
        assertThat(originalFailure.isModelRepairable()).isFalse();
        assertThatThrownBy(() -> provider.enhanceValidated(request, response -> {
            throw originalFailure;
        })).isSameAs(originalFailure);
        server.verify();
    }

    /** 只构造协议正确的对照响应；原件保持原始字节和哈希，不把测试夹具记为真实模型修复。 */
    private ObjectNode standaloneResponse() {
        var response = originalResponse.deepCopy();
        for (var section : response.path("sections")) {
            if (section.path("type").asText().equals(source.destination().name())) {
                String original = section.path("content").asText();
                ((ObjectNode) section).put("content", source.reference() + "\n" + original.substring(source.reference().length()));
            }
        }
        return response;
    }

    /** 保留真实段落类型与正文，单独验证源引用的归类和展开。 */
    private EnumMap<PromptSectionType, PromptSection> sections(JsonNode response) {
        return sections(json.convertValue(response.path("sections"), new TypeReference<List<PromptSection>>() { }));
    }

    /** 每轮使用新容器，防止上一次展开改变下一次失败回放。 */
    private EnumMap<PromptSectionType, PromptSection> sections(List<PromptSection> values) {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        values.forEach(section -> sections.put(section.type(), section));
        return sections;
    }

    /** 经过真实 Provider 的兼容响应解析与修复循环，不直接伪造成功回调。 */
    private String envelope(JsonNode response) throws Exception {
        return json.writeValueAsString(Map.of("model", "test-model", "choices", List.of(Map.of(
                "message", Map.of("role", "assistant", "content", json.writeValueAsString(response))))));
    }
}
