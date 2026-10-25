package com.promptoptimizer.textpolish.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.promptoptimizer.common.logging.ModelCallLogger;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry;
import com.promptoptimizer.textpolish.domain.TextPolishModels.Input;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 独立文章润色适配，复用受限HTTP客户端、路由和全平台调用计量。
 * 仅JSON协议错误允许一次修复；网络、拒绝、长度截断和保真失败不自动质量重试。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@ConditionalOnProperty(prefix = "app.provider", name = "mode", havingValue = "openai-compatible")
public class OpenAiTextPolishProvider implements TextPolishProvider {
    private final RestClient client;
    private final ObjectMapper json;
    private final OpenAiCompatibleProperties properties;
    private final TextPolishFidelity fidelity;
    public OpenAiTextPolishProvider(@Qualifier("openAiCompatibleRestClient") RestClient client, ObjectMapper json,
                                     OpenAiCompatibleProperties properties, TextPolishFidelity fidelity) {
        this.client = client; this.json = json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.properties = properties; this.fidelity = fidelity;
        org.slf4j.LoggerFactory.getLogger(getClass()).info("event=text.polish.provider.ready strategyVersion={}", TextPolishInstructions.VERSION);
    }
    /** 每个真实尝试单独审计；失败日志只包含固定类别和元数据。 */
    @Override
    public String polish(Input input, ModelEntry model) {
        var route = properties.getConfiguredRoutes().stream().filter(r -> r.key().equals(model.routeKey())).findFirst()
                .orElseThrow(() -> new ProviderException(ProviderFailureType.CONFIGURATION, "润色模型路由不可用。", false));
        for (int attempt = 1; attempt <= 2; attempt++) {
            long started = System.nanoTime(); Integer status = null; boolean protocolFailure = false;
            try (var audit = ModelCallLogger.beginAttempt()) {
                try {
                    Map<String, Object> body = request(input, model, attempt > 1);
                    Raw response = client.post().uri(route.endpoint()).header(HttpHeaders.AUTHORIZATION, "Bearer " + route.apiKey())
                            .contentType(MediaType.APPLICATION_JSON).body(body).exchange((req, upstream) -> {
                                byte[] bytes = upstream.getBody().readNBytes(256 * 1024 + 1);
                                if (bytes.length > 256 * 1024) throw invalid("润色响应超过安全长度。");
                                return new Raw(upstream.getStatusCode().value(), new String(bytes, StandardCharsets.UTF_8));
                            });
                    if (response == null) throw invalid("润色响应为空。");
                    status = response.status();
                    if (status >= 400) throw new ProviderException(status == 401 || status == 403 ? ProviderFailureType.AUTHENTICATION
                            : status == 429 ? ProviderFailureType.RATE_LIMIT : ProviderFailureType.UPSTREAM_UNAVAILABLE, "润色模型服务暂不可用，请稍后重试。", status >= 429);
                    JsonNode envelope;
                    try { envelope = json.readTree(response.body()); }
                    catch (JsonProcessingException failure) { protocolFailure = true; throw invalid("润色响应协议无效。"); }
                    if (envelope == null || !envelope.isObject()) { protocolFailure = true; throw invalid("润色响应协议无效。"); }
                    ModelCallLogger.captureTokenUsage(usage(envelope));
                    var choice = envelope.path("choices").path(0);
                    if (choice.path("message").hasNonNull("refusal") || "content_filter".equals(choice.path("finish_reason").asText()))
                        throw new ProviderException(ProviderFailureType.REQUEST_REJECTED, "模型未处理这篇文章，请检查输入内容。", false);
                    if ("length".equals(choice.path("finish_reason").asText())) throw invalid("润色输出未完成，请缩小文章范围后重试。");
                    String output;
                    try {
                        var content = choice.path("message").path("content");
                        if (!content.isTextual()) throw new IllegalArgumentException();
                        var parsed = json.readTree(unwrap(content.textValue()));
                        if (parsed == null || !parsed.isObject() || parsed.size() != 1 || !parsed.path("polishedText").isTextual()) throw new IllegalArgumentException();
                        output = parsed.path("polishedText").textValue();
                    } catch (JsonProcessingException | IllegalArgumentException failure) { protocolFailure = true; throw invalid("润色正文JSON协议无效。"); }
                    fidelity.check(input, output);
                    ModelCallLogger.completed("text.polish", route.key(), model.publicId(), "PLATFORM_CATALOG", false, attempt, 1, elapsed(started), usage(envelope));
                    return output;
                } catch (TextPolishException failure) {
                    ModelCallLogger.failed("text.polish", route.key(), model.publicId(), "PLATFORM_CATALOG", "INVALID_RESPONSE", false, status, false, attempt, 1, elapsed(started));
                    throw failure;
                } catch (ProviderException failure) {
                    boolean repair = attempt == 1 && protocolFailure;
                    ModelCallLogger.failed("text.polish", route.key(), model.publicId(), "PLATFORM_CATALOG", failure.getFailureType().name(), failure.isRetryable(), status, repair, attempt, 1, elapsed(started));
                    if (!repair) throw failure;
                } catch (RestClientException failure) {
                    ModelCallLogger.failed("text.polish", route.key(), model.publicId(), "PLATFORM_CATALOG", "UPSTREAM_UNAVAILABLE", true, status, false, attempt, 1, elapsed(started));
                    throw new ProviderException(ProviderFailureType.UPSTREAM_UNAVAILABLE, "润色连接失败或超时；请先查询处理结果，避免重复调用。", true);
                }
            }
        }
        throw invalid("润色响应协议修复失败。");
    }
    /** 平台已有路由兼容规则仅影响传输参数，不增加文体专属提示词或模型阶段。 */
    private Map<String, Object> request(Input input, ModelEntry model, boolean repair) {
        String user;
        try { user = json.writeValueAsString(input); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("润色输入无法编码", failure); }
        Map<String, Object> body = new LinkedHashMap<>(); body.put("model", model.upstreamModel()); body.put("stream", false);
        body.put("messages", List.of(Map.of("role", "system", "content", TextPolishInstructions.SYSTEM
                + (repair ? "\n上次响应JSON协议不合法，仅修复输出协议，完整保留原文要求。" : "")), Map.of("role", "user", "content", user)));
        if ("tokenhub".equals(model.routeKey()) && "kimi-k3".equals(model.upstreamModel())) body.put("max_completion_tokens", Math.min(8000, properties.getMaxTokens()));
        else { body.put("max_tokens", Math.min(8000, properties.getMaxTokens())); body.put("temperature", properties.getTemperature()); }
        if (properties.isJsonResponseFormatEnabled()) body.put("response_format", Map.of("type", "json_object"));
        if (model.upstreamModel().startsWith("deepseek-v4-") || "deepseek-flash".equals(model.upstreamModel()) || "minimax-m3".equals(model.upstreamModel()))
            body.put("thinking", Map.of("type", "disabled"));
        return body;
    }
    private String unwrap(String text) { String value = text.strip(); return value.startsWith("```json\n") && value.endsWith("```") ? value.substring(8, value.length() - 3).strip() : value; }
    private ModelCallLogger.TokenUsage usage(JsonNode envelope) { var u = envelope.path("usage"); return new ModelCallLogger.TokenUsage(number(u.path("prompt_tokens")), number(u.path("completion_tokens")), number(u.path("total_tokens"))); }
    private Long number(JsonNode node) { return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0 ? node.longValue() : null; }
    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private ProviderException invalid(String message) { return new ProviderException(ProviderFailureType.INVALID_RESPONSE, message, false); }
    private record Raw(int status, String body) { }
}
