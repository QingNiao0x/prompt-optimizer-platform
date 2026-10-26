package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.promptoptimizer.common.logging.*;
import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 真正的多模态 OpenAI 兼容适配：content parts 携带受控图片字节，不公开临时 URL。
 * 正常每阶段一次调用，只有 JSON 协议失败允许一次修复；超时、内容拒绝和网络异常不自动重试。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@ConditionalOnProperty(prefix = "app.provider", name = "mode", havingValue = "openai-compatible")
public class OpenAiImagePromptProvider implements ImagePromptProvider {
    private final RestClient client;
    private final ObjectMapper json;
    private final OpenAiCompatibleProperties properties;
    private final PlatformModelCatalog catalog;
    private final SensitiveValueScanner scanner = new SensitiveValueScanner();

    public OpenAiImagePromptProvider(@Qualifier("openAiCompatibleRestClient") RestClient client, ObjectMapper json,
                                     OpenAiCompatibleProperties properties, PlatformModelCatalog catalog) {
        this.client = client; this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS); this.properties = properties; this.catalog = catalog;
        // 启动时仅记录公开协议版本与指导哈希，便于区分源码已编译和运行进程实际加载，不打印指导正文。
        org.slf4j.LoggerFactory.getLogger(getClass()).info("event=image.provider.ready protocolVersion={} instructionsSha256={} analyzeInstructionsSha256={}",
                ImagePromptInstructions.VERSION, ImagePromptAssetService.hash(ImagePromptInstructions.SYSTEM.getBytes(StandardCharsets.UTF_8)),
                ImagePromptAssetService.hash(ImagePromptInstructions.ANALYZE_SYSTEM.getBytes(StandardCharsets.UTF_8)));
    }

    /** 按平台目录选择路由并逐次审计；只为协议不合法的响应提供一次有界修复。 */
    @Override
    public ProviderResult invoke(Call call) {
        var model = catalog.resolve(call.input().modelId());
        var route = properties.getConfiguredRoutes().stream().filter(r -> r.key().equals(model.routeKey())).findFirst()
                .orElseThrow(() -> new ProviderException(ProviderFailureType.CONFIGURATION, "图像模型路由不可用", false));
        String operation = call.stage() == Stage.PLAN ? "image.plan" : call.stage() == Stage.ANALYZE ? "image.analyze" : "image.generate";
        for (int attempt = 1; attempt <= call.maxAttempts(); attempt++) {
            long started = System.nanoTime();
            boolean retry = false;
            Integer status = null;
            try (var audit = ModelCallLogger.beginAttempt()) {
                try {
                    Map<String, Object> body = request(call, model.routeKey(), model.upstreamModel(), attempt > 1);
                    RawResponse response = client.post().uri(route.endpoint()).header(HttpHeaders.AUTHORIZATION, "Bearer " + route.apiKey())
                            .contentType(MediaType.APPLICATION_JSON).body(body).exchange((request, upstream) -> {
                                byte[] bytes = upstream.getBody().readNBytes(512 * 1024 + 1);
                                if (bytes.length > 512 * 1024) throw invalid("视觉模型响应超过安全长度限制。");
                                return new RawResponse(upstream.getStatusCode().value(), new String(bytes, StandardCharsets.UTF_8));
                            });
                    if (response == null) throw invalid("视觉模型响应为空。");
                    status = response.status();
                    JsonNode envelope;
                    try { envelope = json.readTree(response.body()); }
                    catch (JsonProcessingException failure) {
                        if (status >= 400) throw upstream(status);
                        throw invalid("视觉模型响应格式无效。");
                    }
                    if (envelope == null || !envelope.isObject()) throw invalid("视觉模型响应格式无效。");
                    ModelCallLogger.captureTokenUsage(usage(envelope));
                    if (status >= 400) throw upstream(status);
                    JsonNode choice = envelope.path("choices").path(0);
                    if (choice.path("message").hasNonNull("refusal") || "content_filter".equals(choice.path("finish_reason").asText())) {
                        throw new ProviderException(ProviderFailureType.REQUEST_REJECTED, "模型拒绝处理此内容，请检查图片或创作要求。", false);
                    }
                    if ("length".equals(choice.path("finish_reason").asText())) throw invalid("视觉模型输出未完成，请缩小输入范围后重试。");
                    JsonNode content = choice.path("message").path("content");
                    if (!content.isTextual()) throw invalid("视觉模型未返回有效正文。");
                    ProviderDraft draft;
                    try { draft = json.readValue(unwrap(content.asText()), ProviderDraft.class); }
                    catch (JsonProcessingException failure) { throw invalid("图像结果 JSON 协议无效。"); }
                    validate(draft, call);
                    ModelCallLogger.completed(operation, route.key(), model.publicId(), "PLATFORM_CATALOG", false,
                            attempt, call.images().size(), elapsed(started), usage(envelope));
                    return new ProviderResult(draft, model.publicId(), model.displayName(), false, attempt);
                } catch (ProviderException failure) {
                    retry = attempt < call.maxAttempts() && failure.getFailureType() == ProviderFailureType.INVALID_RESPONSE;
                    ModelCallLogger.failed(operation, route.key(), model.publicId(), "PLATFORM_CATALOG", failure.getFailureType().name(),
                            failure.isRetryable(), status, retry, attempt, call.images().size(), elapsed(started));
                    if (!retry) throw failure;
                } catch (ResourceAccessException failure) {
                    ModelCallLogger.failed(operation, route.key(), model.publicId(), "PLATFORM_CATALOG", "TIMEOUT", true, null, false,
                            attempt, call.images().size(), elapsed(started));
                    // 不将可能包含带凭据 URL/响应的客户端异常 cause 放入业务异常或普通日志。
                    throw new ProviderException(ProviderFailureType.TIMEOUT, "图像模型连接失败或超时，实际调用状态可能未知，请勿连续重试。", true);
                } catch (RestClientException failure) {
                    ModelCallLogger.failed(operation, route.key(), model.publicId(), "PLATFORM_CATALOG", "UPSTREAM_UNAVAILABLE", true, null, false,
                            attempt, call.images().size(), elapsed(started));
                    throw new ProviderException(ProviderFailureType.UPSTREAM_UNAVAILABLE, "图像模型服务暂不可用。", true);
                }
            }
        }
        throw invalid("图像结果协议修复失败。");
    }

    /** 图片使用 data URL 放入多模态内容，不把 Base64 塞进原始提示词或普通日志。 */
    private Map<String, Object> request(Call call, String routeKey, String upstream, boolean repair) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("stage", call.stage());
        payload.put("imageSources", call.images().stream().map(ImageData::source).toList());
        payload.put("materialSources", call.materialSources());
        payload.put("analysisBudget", call.stage() == Stage.ANALYZE ? Math.min(40, call.images().size() * 8) : 40);
        if (call.stage() == Stage.ANALYZE) payload.put("batchCoverage", batchCoverage(call));
        if (call.stage() != Stage.ANALYZE) {
            // 创作目标、指定文案、上下文、关系和题答只进入创作阶段，源识别不能看到这些新内容。
            payload.put("input", call.input());
            payload.put("evidenceWarnings", call.evidenceWarnings());
            payload.put("analysis", call.analysis()); payload.put("questions", call.questions()); payload.put("answers", call.answers());
            payload.put("previousPrompt", call.previousPrompt()); payload.put("revisionInstruction", call.revisionInstruction());
        }
        String text;
        try { text = json.writeValueAsString(payload); }
        catch (JsonProcessingException failure) { throw invalid("图像请求无法编码。"); }
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", text));
        for (ImageData image : call.images()) {
            parts.add(Map.of("type", "image_url", "image_url", Map.of("url", "data:" + image.mimeType() + ";base64," + Base64.getEncoder().encodeToString(image.bytes()))));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("model", upstream); body.put("stream", false);
        String instructions = call.stage() == Stage.ANALYZE ? ImagePromptInstructions.ANALYZE_SYSTEM : ImagePromptInstructions.SYSTEM;
        body.put("messages", List.of(Map.of("role", "system", "content", instructions
                + (repair ? "\n上次响应协议无效。请仅修复JSON结构并完整遵守阶段协议，不改变用户任务，不输出额外说明。" : "")),
                Map.of("role", "user", "content", parts)));
        // 沿用平台已验证的路由兼容项，不从名字推断视觉能力；Kimi K3 的 TokenHub 参数与普通兼容模型不同。
        boolean tokenHub = "tokenhub".equalsIgnoreCase(routeKey);
        if (tokenHub && "kimi-k3".equalsIgnoreCase(upstream)) body.put("max_completion_tokens", Math.min(6000, properties.getMaxTokens()));
        else { body.put("max_tokens", Math.min(6000, properties.getMaxTokens())); body.put("temperature", properties.getTemperature()); }
        if (properties.isJsonResponseFormatEnabled()) body.put("response_format", Map.of("type", "json_object"));
        if (properties.isJsonResponseFormatEnabled() && ("deepseek".equalsIgnoreCase(routeKey)
                && Set.of("deepseek-flash", "deepseek-v4-pro").contains(upstream)
                || tokenHub && (upstream.startsWith("deepseek-v4-") || "minimax-m3".equals(upstream)))) {
            body.put("thinking", Map.of("type", "disabled"));
        }
        return body;
    }

    /** 任务选择与本次传输分开计数；只携带来源单位标识，不让模型把有界分批误判为任务漏页或漏帧。 */
    private Map<String, Object> batchCoverage(Call call) {
        var current = call.images().stream().map(image -> image.source().id()).toList();
        var selected = new LinkedHashSet<UUID>();
        var scopes = new ArrayList<Map<String, Object>>();
        for (var image : call.input().images()) {
            selected.add(image.assetId());
            if (current.contains(image.assetId())) scopes.add(Map.of("assetId", image.assetId(), "taskSelectedUnitCount", 1,
                    "currentBatchUnitCount", 1, "taskSelectedUnitIds", List.of(image.assetId()), "currentBatchUnitIds", List.of(image.assetId())));
        }
        for (var material : call.input().materials()) {
            var units = material.units().stream().map(unit -> unit.unitId()).toList();
            selected.addAll(units);
            var batch = units.stream().filter(current::contains).toList();
            if (!batch.isEmpty()) scopes.add(Map.of("assetId", material.assetId(), "taskSelectedUnitCount", units.size(),
                    "currentBatchUnitCount", batch.size(), "taskSelectedUnitIds", units, "currentBatchUnitIds", batch));
        }
        return Map.of("scope", "CURRENT_BATCH", "taskSelectedUnitCount", selected.size(), "currentBatchUnitCount", current.size(),
                "currentBatchUnitIds", current, "sources", scopes);
    }

    /** 校验来源、题目和输出边界；结构合法不等于视觉语义正确，实际图片质量需独立验收。 */
    void validate(ProviderDraft draft, Call call) {
        if (draft == null || draft.summary() == null || draft.summary().length() > 600 || draft.analysis() == null
                || draft.questions() == null || draft.warnings() == null || draft.analysis().size() > 40
                || draft.questions().size() > 8 || draft.warnings().size() > 12 || draft.optimizedPrompt() == null
                || draft.optimizedPrompt().length() > 24000) throw invalid("图像结果字段或数量不符合协议。");
        if (call.stage() != Stage.PLAN && call.stage() != Stage.ANALYZE && (draft.optimizedPrompt().isBlank() || !draft.questions().isEmpty())) throw invalid("生成阶段缺少正文或返回了额外问题。");
        if (call.stage() == Stage.PLAN && !draft.optimizedPrompt().isEmpty()) throw invalid("Plan 阶段不能用未确认结果替代问答。");
        if (call.stage() == Stage.ANALYZE && (!draft.optimizedPrompt().isEmpty() || !draft.questions().isEmpty())) throw invalid("素材识别阶段不能生成提示词或创作问题。");
        Set<UUID> sources = new HashSet<>();
        call.images().forEach(i -> sources.add(i.source().id())); call.analysis().forEach(i -> sources.add(i.imageId()));
        if (call.stage() != Stage.ANALYZE) call.materialSources().forEach(source -> source.units().forEach(unit -> sources.add(unit.id())));
        for (Observation observation : draft.analysis()) {
            if (observation == null || !sources.contains(observation.imageId()) || observation.status() == null
                    || !bounded(observation.category(), 40) || !bounded(observation.description(), 1200)) throw invalid("图像观察缺少有效来源或内容。");
        }
        if (call.stage() == Stage.ANALYZE && (draft.analysis().size() > Math.min(40, call.images().size() * 8)
                || draft.analysis().stream().collect(java.util.stream.Collectors.groupingBy(Observation::imageId,
                java.util.stream.Collectors.counting())).values().stream().anyMatch(count -> count > 8))) {
            throw invalid("单个素材单位最多8项观察，请合并相关维度并保留来源事实。");
        }
        var questionIds = new HashSet<String>();
        for (Question question : draft.questions()) {
            if (question == null || !code(question.id()) || !questionIds.add(question.id()) || question.type() == null
                    || !bounded(question.question(), 500) || question.options() == null || question.options().size() > 8) throw invalid("图像 Plan 问题协议无效。");
            if (question.type() == QuestionType.TEXT && !question.options().isEmpty()
                    || question.type() != QuestionType.TEXT && question.options().size() < 2) throw invalid("图像 Plan 题型与选项不一致。");
            var ids = new HashSet<String>();
            for (Option option : question.options()) if (option == null || !code(option.id()) || !ids.add(option.id())
                    || !bounded(option.label(), 300)) throw invalid("图像 Plan 选项无效。");
        }
        if (draft.warnings().stream().anyMatch(v -> !bounded(v, 500))) throw invalid("图像警告字段无效。");
        try {
            if (scanner.containsCredential(json.writeValueAsString(draft))) throw new ProviderException(ProviderFailureType.REQUEST_REJECTED,
                    "结果含疑似凭据，已阻止保存和展示。请遮挡图片中的敏感内容后重新提交。", false);
        } catch (JsonProcessingException failure) { throw invalid("图像结果无法检查。"); }
    }

    private boolean code(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,40}"); }
    private boolean bounded(String value, int size) { return value != null && !value.isBlank() && value.length() <= size; }
    /** 只去掉完整 JSON 围栏，不猜补模型字段或截断内容。 */
    private String unwrap(String text) {
        String value = text.strip();
        if (value.startsWith("```json\n") && value.endsWith("```")) return value.substring(8, value.length() - 3).strip();
        return value;
    }
    /** 只接受上游非负整数字段；缺失用量保持未知，不以零伪装免费调用。 */
    private ModelCallLogger.TokenUsage usage(JsonNode envelope) {
        JsonNode value = envelope.path("usage");
        return new ModelCallLogger.TokenUsage(number(value.path("prompt_tokens")), number(value.path("completion_tokens")), number(value.path("total_tokens")));
    }
    private Long number(JsonNode node) { return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0 ? node.longValue() : null; }
    /** 将上游状态映射为稳定错误类别，不回显可能含凭据或图像数据的响应正文。 */
    private ProviderException upstream(int status) {
        var type = status == 401 || status == 403 ? ProviderFailureType.AUTHENTICATION : status == 429 ? ProviderFailureType.RATE_LIMIT
                : status == 408 || status == 504 ? ProviderFailureType.TIMEOUT : status >= 500 ? ProviderFailureType.UPSTREAM_UNAVAILABLE : ProviderFailureType.REQUEST_REJECTED;
        return new ProviderException(type, "图像模型请求失败，HTTP " + status + "。请检查模型能力和服务状态。", status == 429 || status >= 500);
    }
    private ProviderException invalid(String message) { return new ProviderException(ProviderFailureType.INVALID_RESPONSE, message, false); }
    private long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
    private record RawResponse(int status, String body) { }
}
