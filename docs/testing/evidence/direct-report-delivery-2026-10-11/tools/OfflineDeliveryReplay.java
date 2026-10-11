package com.promptoptimizer.enhancement.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.promptoptimizer.context.service.impl.BinaryContentExtractor;
import com.promptoptimizer.context.service.impl.DefaultContextAnalyzer;
import com.promptoptimizer.context.service.impl.FileContentSummarizer;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.enhancement.dto.EnhancementOptions;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.dto.PermissionPolicyInput;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleRouteProperties;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatiblePromptEnhancementProvider;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在生产编排器内离线重放 v1 的真实上游响应，并核对 v2 请求及最终交付字段。
 * 不读取配置或环境凭据；底层 request factory 永远拒绝网络，拦截器不调用 execution。
 * 结果只证明固定响应下的行为等价，不代表重新调用模型或下游作品验收。
 */
public final class OfflineDeliveryReplay {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Path REPO = Path.of("").toAbsolutePath().normalize();
    private static final Path ROOT = REPO.resolve("tmp/direct-report-20261011");
    private static final List<String> FIELDS = List.of("optimizedPrompt", "sections", "appliedConstraints", "ambiguities");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC);

    private OfflineDeliveryReplay() { }

    /** 只创建新结果目录，错误也保存；任何既有结果都拒绝覆盖。 */
    public static void main(String[] args) throws Exception {
        require(args.length == 2, "CLASSES_AND_OUTPUT_REQUIRED");
        Path classes = Path.of(args[0]).toAbsolutePath().normalize().toRealPath();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        require(classes.equals(ROOT.resolve("candidate-v2/target/classes").toRealPath()), "V2_CLASSES_REQUIRED");
        require(output.startsWith(ROOT.resolve("replay-v2")) && !Files.exists(output), "NEW_REPLAY_OUTPUT_REQUIRED");
        Files.createDirectories(output);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scope", "OFFLINE_V1_RESPONSE_REPLAY_NO_NETWORK_NO_DB_NO_HISTORY_NO_DOWNSTREAM_WORK");
        summary.put("startedAt", Instant.now().toString());
        summary.put("productionClassesSha256", classHash(classes));
        summary.put("toolClassesSha256", classHash(Path.of(OfflineDeliveryReplay.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
        summary.put("toolSourceSha256", sha(Files.readAllBytes(ROOT.resolve("replay-v2/OfflineDeliveryReplay.java"))));
        summary.put("sourceManifestSha256", sha(Files.readAllBytes(ROOT.resolve("candidate-v2/source-sha256.json"))));
        summary.put("sourceVerification", verifySources());
        summary.put("comparisonFields", FIELDS);
        summary.put("ignoredResultFields", List.of("id", "createdAt", "provider", "contextSummary", "templateCode", "originalPrompt"));
        summary.put("requestNormalization", "None; JsonNode equality compares all body values, ignoring JSON object key order only");
        summary.put("responseEnvelope", "Reconstructed from preserved content, actualModel, finishReason, usage, httpStatus; unrecorded envelope metadata is not claimed identical");
        summary.put("networkFactory", "Always throws NETWORK_FORBIDDEN; interceptor never executes transport");
        var results = new ArrayList<Map<String, Object>>();
        boolean passed = true;
        for (String arm : List.of("flash", "pro")) {
            Path input = ROOT.resolve("cases-" + arm + ".json");
            JsonNode fixture = JSON.readTree(Files.readAllBytes(input));
            require("USER_PROVIDED_SCREENSHOT".equals(fixture.path("dataAuthorization").asText()), "FROZEN_FIXTURE_REQUIRED");
            Path reference = ROOT.resolve("candidate-" + arm + "-v1");
            require(JSON.readTree(Files.readAllBytes(reference.resolve("input.json"))).equals(fixture), "INPUT_CHANGED_SINCE_V1");
            for (JsonNode sample : fixture.path("cases")) {
                Map<String, Object> row = replay(sample, fixture, reference, output.resolve(arm));
                row.put("inputSha256", sha(Files.readAllBytes(input)));
                results.add(row);
                passed &= "MATCH".equals(row.get("status"));
            }
        }
        summary.put("cases", results);
        summary.put("status", passed ? "ALL_REQUESTS_AND_DELIVERY_FIELDS_MATCH" : "REPLAY_DIFFERENCE_OR_FAILURE");
        summary.put("finishedAt", Instant.now().toString());
        write(output.resolve("summary.json"), summary);
        System.out.println(summary.get("status"));
        if (!passed) System.exit(2);
    }

    /** 限定相同输入、选项和目录投影；固定身份与时钟不计入待比较的业务字段。 */
    private static Map<String, Object> replay(JsonNode sample, JsonNode fixture, Path reference, Path output) throws Exception {
        Files.createDirectories(output);
        String id = sample.path("id").asText();
        require(id.matches("[A-Z0-9-]{1,80}"), "INVALID_CASE_ID");
        String model = fixture.path("modelId").asText();
        require(Set.of("deepseek:deepseek-flash", "deepseek:deepseek-v4-pro").contains(model), "UNFROZEN_MODEL");
        var traces = new ArrayList<JsonNode>();
        var traceHashes = new ArrayList<Map<String, Object>>();
        for (int index = 1; Files.exists(reference.resolve(id + "-generation-trace-" + index + ".json")); index++) {
            Path file = reference.resolve(id + "-generation-trace-" + index + ".json");
            byte[] bytes = Files.readAllBytes(file);
            JsonNode trace = JSON.readTree(bytes);
            require(trace.path("httpStatus").asInt() == 200 && trace.has("content"), "SUCCESS_RESPONSE_TRACE_REQUIRED");
            traces.add(trace);
            traceHashes.add(Map.of("path", ROOT.relativize(file).toString(), "sha256", sha(bytes)));
        }
        require(!traces.isEmpty() && traces.size() <= 3, "TRACE_COUNT_INVALID");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", id);
        row.put("modelId", model);
        row.put("traces", traceHashes);
        row.put("referenceResultSha256", sha(Files.readAllBytes(reference.resolve(id + "-generation.json"))));
        var calls = new AtomicInteger();
        var comparisons = new ArrayList<Map<String, Object>>();
        try {
            var properties = offlineProperties(model, traces.getFirst().path("requestBody"));
            RestClient client = RestClient.builder()
                    .requestFactory((uri, method) -> { throw new IllegalStateException("NETWORK_FORBIDDEN"); })
                    .requestInterceptor((request, bytes, execution) -> {
                        int index = calls.getAndIncrement();
                        require(index < traces.size(), "REPLAY_RESPONSE_EXHAUSTED");
                        JsonNode saved = traces.get(index);
                        JsonNode actualRequest = JSON.readTree(bytes);
                        JsonNode expectedRequest = saved.path("requestBody");
                        Map<String, Object> comparison = new LinkedHashMap<>();
                        comparison.put("attempt", index + 1);
                        comparison.put("requestBodyEqual", expectedRequest.equals(actualRequest));
                        comparison.put("requestBody", actualRequest);
                        comparison.put("expectedRequestSha256", sha(JSON.writeValueAsBytes(expectedRequest)));
                        comparison.put("actualRequestSha256", sha(JSON.writeValueAsBytes(actualRequest)));
                        comparisons.add(comparison);
                        write(output.resolve(id + "-replayed-request-" + (index + 1) + ".json"), comparison);
                        // 不调用 execution.execute；只把安全归档字段恢复成 Provider 可读取的响应。
                        var response = Map.of("model", saved.path("actualModel").asText(), "choices", List.of(Map.of(
                                "index", 0, "message", Map.of("role", "assistant", "content", saved.path("content").asText()),
                                "finish_reason", saved.path("finishReason").asText())), "usage", saved.path("usage"));
                        return new OfflineResponse(saved.path("httpStatus").asInt(), JSON.writeValueAsBytes(response));
                    }).build();
            var provider = new OpenAiCompatiblePromptEnhancementProvider(client, JSON, properties);
            var analyzer = new DefaultContextAnalyzer(JSON, new BinaryContentExtractor(), new FileContentSummarizer());
            UUID frozenId = UUID.fromString("00000000-0000-0000-0000-000000000011");
            var identity = new ActorIdentity(frozenId, frozenId, frozenId, "synthetic@evaluation.invalid", "Synthetic report evaluation");
            var orchestrator = new DefaultEnhancementOrchestrator(analyzer, new AmbiguityDetector(),
                    new PromptTemplateRegistryImpl(), new ConstraintCompleterImpl(), provider, () -> identity, CLOCK);
            var selection = properties.resolveModel(model);
            var entry = new PlatformModelCatalog.ModelEntry(UUID.nameUUIDFromBytes(model.getBytes(StandardCharsets.UTF_8)), model,
                    selection.route().key(), selection.model(), fixture.path("modelDisplayName").asText(), true, true, 0);
            PlatformModelCatalog catalog = (PlatformModelCatalog) Proxy.newProxyInstance(PlatformModelCatalog.class.getClassLoader(),
                    new Class<?>[]{PlatformModelCatalog.class}, (proxy, method, values) -> {
                        if (method.getName().equals("resolve")) {
                            require(values[0] == null || values[0].toString().isBlank() || model.equals(values[0]), "UNFROZEN_MODEL");
                            return entry;
                        }
                        if (Set.of("available", "all").contains(method.getName())) return List.of(entry);
                        throw new IllegalStateException("UNSUPPORTED_FROZEN_CATALOG_OPERATION");
                    });
            orchestrator.setModelCatalog(catalog);
            var files = new ArrayList<ContextFileInput>();
            for (JsonNode file : sample.path("files")) files.add(new ContextFileInput(file.path("path").asText(), file.path("content").asText(), file.path("language").asText()));
            var options = new EnhancementOptions(TemplateCode.AUTO, false, true, false);
            require(!sample.path("includeExamples").asBoolean() && "direct".equals(sample.path("mode").asText()), "DIRECT_NO_EXAMPLES_REQUIRED");
            var request = new OptimizationRequest(sample.path("rawPrompt").asText(), new ContextAnalysisRequest(sample.path("contextDescription").asText(), files),
                    options, List.of(), PermissionPolicyInput.empty(), null, model);
            var result = orchestrator.optimize(request);
            JsonNode actual = JSON.valueToTree(result);
            JsonNode original = JSON.readTree(Files.readAllBytes(reference.resolve(id + "-generation.json"))).path("result");
            var fields = new LinkedHashMap<String, Object>();
            boolean equal = calls.get() == traces.size();
            for (String field : FIELDS) {
                require(actual.has(field) && original.has(field), "MISSING_COMPARISON_FIELD_" + field);
                boolean same = actual.get(field).equals(original.get(field));
                fields.put(field, same);
                equal &= same;
            }
            equal &= comparisons.stream().allMatch(comparison -> Boolean.TRUE.equals(comparison.get("requestBodyEqual")));
            row.put("effectiveOptions", options);
            row.put("planEnabled", false);
            row.put("fieldEqual", fields);
            row.put("status", equal ? "MATCH" : "DIFFERENT");
            write(output.resolve(id + "-result.json"), actual);
        } catch (Exception exception) {
            row.put("status", "FAILED");
            row.put("failureType", exception.getClass().getSimpleName());
            row.put("failureLocation", exception.getStackTrace().length == 0 ? "" : exception.getStackTrace()[0].toString());
        }
        row.put("expectedAttempts", traces.size());
        row.put("replayedAttempts", calls.get());
        row.put("requestBodiesEqual", comparisons.stream().allMatch(comparison -> Boolean.TRUE.equals(comparison.get("requestBodyEqual"))));
        write(output.resolve(id + "-comparison.json"), row);
        return row;
    }

    /** 从归档请求取非敏感生成参数，内存构造假凭据，不读取 application.yml 或环境变量。 */
    private static OpenAiCompatibleProperties offlineProperties(String model, JsonNode request) {
        String bare = model.substring(model.indexOf(':') + 1);
        var route = new OpenAiCompatibleRouteProperties();
        route.setProviderName("deepseek");
        route.setEndpoint(URI.create("https://offline.invalid/chat/completions"));
        route.setApiKey("OFFLINE-NOT-A-CREDENTIAL");
        route.setModel(bare);
        route.setModels(List.of("deepseek-flash", "deepseek-v4-pro"));
        var properties = new OpenAiCompatibleProperties();
        properties.setMultiProviderEnabled(true);
        properties.setDefaultProvider("deepseek");
        properties.setProviders(Map.of("deepseek", route));
        properties.setTemperature(request.path("temperature").asDouble());
        properties.setMaxTokens(request.path("max_tokens").asInt());
        properties.setJsonResponseFormatEnabled(request.has("response_format"));
        return properties;
    }

    /** 核对冻结源目录，确保编译前后没有混入其他会话文件。 */
    private static Map<String, Object> verifySources() throws Exception {
        Path v2 = ROOT.resolve("candidate-v2");
        JsonNode entries = JSON.readTree(Files.readAllBytes(v2.resolve("source-sha256.json")));
        require(entries.isArray() && entries.size() == 379, "EXPECTED_379_SOURCE_FILES");
        int changedFromV1 = 0;
        for (JsonNode entry : entries) {
            String path = entry.path("Path").asText();
            String actual = sha(Files.readAllBytes(v2.resolve(path)));
            require(actual.equalsIgnoreCase(entry.path("Sha256").asText()), "FROZEN_SOURCE_CHANGED");
            if (!actual.equals(sha(Files.readAllBytes(ROOT.resolve("candidate-v1").resolve(path))))) {
                require(path.replace('\\', '/').equals("main/java/com/promptoptimizer/provider/domain/DraftDeliveryContract.java"), "UNEXPECTED_SOURCE_CHANGE");
                changedFromV1++;
            }
        }
        require(changedFromV1 == 1, "EXPECTED_ONE_SOURCE_CHANGE");
        return Map.of("files", entries.size(), "changedFromV1", changedFromV1, "allFrozenHashesMatch", true);
    }

    /** 以与原真实工具相同的算法摘要全部生产 class 文件。 */
    private static String classHash(Path classes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> files;
        try (var paths = Files.walk(classes)) { files = paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class")).sorted().toList(); }
        require(!files.isEmpty(), "CLASSES_REQUIRED");
        for (Path file : files) {
            digest.update(classes.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", exception);
        }
    }

    private static void write(Path output, Object value) throws java.io.IOException {
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalStateException(code);
    }

    /** 完全由内存提供响应，不持有真实连接或底层 transport。 */
    private record OfflineResponse(int status, byte[] bytes) implements ClientHttpResponse {
        @Override public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(status); }
        @Override public String getStatusText() { return "Offline replay"; }
        @Override public void close() { }
        @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
        @Override public HttpHeaders getHeaders() {
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return headers;
        }
    }
}
