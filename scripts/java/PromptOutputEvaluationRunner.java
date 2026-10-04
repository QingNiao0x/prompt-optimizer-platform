import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.service.impl.SensitiveValueDetector;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleRoute;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 执行冻结的业务评测请求，复用服务端模型路由，仅保存实际作品和白名单元数据。
 * 不启动 Spring，不连接数据库，不改变平台增强、索引、登录或权限逻辑。
 * 密钥只留在进程内存及出站认证头，失败时不记录上游正文或异常消息。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class PromptOutputEvaluationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(PromptOutputEvaluationRunner.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SensitiveValueDetector SENSITIVE = new SensitiveValueDetector();
    private static final Path REPO = Path.of("").toAbsolutePath().normalize();
    private static final Path EVALUATION_ROOT = REPO.resolve("tmp/prompt-output-evaluation").normalize();

    private static final int MAX_JOBS = 60;


    private PromptOutputEvaluationRunner() { }

    /** 仅接受 -ValidateJobs / -RunJobs 和评测目录中的冻结批次文件。 */
    public static void main(String[] args) {
        try {
            OpenAiCompatibleProperties properties = bindProperties();
            if (args.length == 2 && ("-RunJobs".equals(args[0]) || "-ValidateJobs".equals(args[0]))) {
                runJobs(properties, safeInput(args[1]), "-ValidateJobs".equals(args[0]));
                return;
            }
            fail("INVALID_ARGUMENTS");
        } catch (SafeFailure exception) {
            fail(exception.code);
        } catch (Exception exception) {
            // Exception messages can contain upstream URLs, response bodies or credentials.
            fail("SETUP_OR_FILE_FAILURE");
        }
    }

    /** Binds only ordinary application.yml and inherited process environment; no bootstrapping. */
    private static OpenAiCompatibleProperties bindProperties() throws IOException {
        var environment = new StandardEnvironment();
        Path yaml = REPO.resolve("services/api/src/main/resources/application.yml");
        for (var source : new YamlPropertySourceLoader().load("downstream-eval", new FileSystemResource(yaml))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment).bind("app.provider.openai-compatible", OpenAiCompatibleProperties.class)
                .orElseThrow(() -> new SafeFailure("MODEL_PROPERTIES_UNAVAILABLE"));
    }

    /** Validates the entire batch before its first model request, then executes serially without retry. */
    private static void runJobs(OpenAiCompatibleProperties properties, Path input, boolean validateOnly) throws Exception {
        if (Files.size(input) > 8_000_000L) { throw new SafeFailure("JOBS_FILE_TOO_LARGE"); }
        JsonNode root = JSON.readTree(Files.readString(input, StandardCharsets.UTF_8));
        if (!root.isObject() || root.path("schemaVersion").asInt(0) != 2
                || root.path("repetitions").asInt(0) < 1 || root.path("repetitions").asInt(0) > 3) {
            throw new SafeFailure("INVALID_BATCH_SCHEMA_OR_REPETITIONS");
        }
        // 准备阶段从真实公开模型 API 取得目录；执行时检查哈希和新鲜度，不把环境路由当成已发布列表。
        JsonNode manifest = JSON.readTree(Files.readString(safeInput(input.resolveSibling("manifest.json").toString()), StandardCharsets.UTF_8));
        if (!sha256(Files.readString(input, StandardCharsets.UTF_8)).equals(manifest.path("jobsSha256").asText())) {
            throw new SafeFailure("FROZEN_JOBS_CHANGED");
        }
        Path catalogInput = safeInput(input.resolveSibling("models.json").toString());
        String catalogText = Files.readString(catalogInput, StandardCharsets.UTF_8);
        if (Files.size(catalogInput) > 1_000_000L || !sha256(catalogText).equals(manifest.path("modelsSha256").asText())) {
            throw new SafeFailure("FROZEN_CATALOG_CHANGED");
        }
        Path originalInput = safeInput(input.resolveSibling("input.json").toString());
        if (!sha256(Files.readString(originalInput, StandardCharsets.UTF_8)).equals(manifest.path("inputSha256").asText())) {
            throw new SafeFailure("FROZEN_INPUT_CHANGED");
        }
        JsonNode catalog = JSON.readTree(catalogText);
        if (!"/api/v1/models".equals(catalog.path("source").asText()) || !catalog.path("executionModels").isArray()) {
            throw new SafeFailure("INVALID_PUBLISHED_CATALOG_SNAPSHOT");
        }
        Instant captured = Instant.parse(requiredText(catalog.path("capturedAt"), 80, "INVALID_CATALOG_TIME"));
        long ageSeconds = Duration.between(captured, Instant.now()).getSeconds();
        if (ageSeconds < -60 || ageSeconds > 86_400) { throw new SafeFailure("CATALOG_SNAPSHOT_EXPIRED"); }
        Set<String> published = new LinkedHashSet<>();
        for (JsonNode entry : catalog.path("models")) {
            published.add(requiredText(entry.path("id"), 160, "INVALID_PUBLISHED_MODEL"));
        }
        Set<String> selectedExecutors = new LinkedHashSet<>();
        for (JsonNode entry : catalog.path("executionModels")) {
            String id = requiredText(entry.path("id"), 160, "INVALID_EXECUTOR_MODEL");
            if (!published.contains(id)) { throw new SafeFailure("EXECUTOR_NOT_PUBLISHED"); }
            selectedExecutors.add(id);
        }
        if (selectedExecutors.isEmpty() || selectedExecutors.size() > 4) { throw new SafeFailure("INVALID_EXECUTOR_COUNT"); }
        JsonNode values = root.path("jobs");
        if (!values.isArray() || values.isEmpty() || values.size() > MAX_JOBS || values.size() > root.path("maxCalls").asInt(0)) {
            throw new SafeFailure("INVALID_JOB_COUNT");
        }
        List<Job> jobs = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode value : values) {
            String id = requiredText(value.path("id"), 120, "INVALID_JOB_ID");
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,119}") || !unique.add(id)) {
                throw new SafeFailure("INVALID_OR_DUPLICATE_JOB_ID");
            }
            String model = requiredText(value.path("modelId"), 160, "INVALID_MODEL_ID");
            if (!selectedExecutors.contains(model)) { throw new SafeFailure("MODEL_NOT_PUBLISHED_IN_BATCH"); }
            var selection = properties.resolveModel(model);
            if (!usable(selection.route())) { throw new SafeFailure("MODEL_ROUTE_UNAVAILABLE"); }
            String user = requiredText(value.path("user"), 280_000, "INVALID_TASK_PROMPT");
            String system = requiredText(value.path("system"), 5_000, "INVALID_EXECUTION_SYSTEM");
            String caseId = requiredText(value.path("caseId"), 120, "INVALID_CASE_ID");
            String arm = requiredText(value.path("arm"), 80, "INVALID_ARM");
            String promptHash = requiredText(value.path("promptHash"), 64, "INVALID_PROMPT_HASH");
            String materialsHash = requiredText(value.path("materialsHash"), 64, "INVALID_MATERIALS_HASH");
            if (!promptHash.matches("[a-f0-9]{64}") || !materialsHash.matches("[a-f0-9]{64}")) {
                throw new SafeFailure("INVALID_CONTENT_HASH");
            }
            int maxTokens = value.path("maxTokens").asInt(0);
            if (value.path("temperature").asDouble(-1D) != 0.2D
                    || maxTokens < 256 || maxTokens > 16384) {
                throw new SafeFailure("UNAPPROVED_GENERATION_BUDGET");
            }
            if (SENSITIVE.containsCredential(user) || SENSITIVE.containsCredential(system)
                    || includesBoundCredential(properties, user) || includesBoundCredential(properties, system)) {
                throw new SafeFailure("SENSITIVE_INPUT_REJECTED");
            }
            if (!Set.of("raw", "direct", "plan", "raw_matched", "direct_matched").contains(arm)) {
                throw new SafeFailure("INVALID_ARM");
            }
            String userHash = requiredText(value.path("userHash"), 64, "INVALID_USER_HASH");
            String systemHash = requiredText(value.path("systemHash"), 64, "INVALID_SYSTEM_HASH");
            if (!sha256(user).equals(userHash) || !sha256(system).equals(systemHash)) {
                throw new SafeFailure("JOB_PAYLOAD_HASH_MISMATCH");
            }
            jobs.add(new Job(id, caseId, arm, model, system, user, promptHash, materialsHash, maxTokens, selection));
        }
        String outputDirectory = requiredText(root.path("outputDirectory"), 2000, "INVALID_OUTPUT_DIRECTORY");
        Path results = Path.of(outputDirectory).toAbsolutePath().normalize();
        if (!results.equals(input.getParent().resolve("execution"))) {
            throw new SafeFailure("RESULT_PATH_OUTSIDE_CURRENT_RUN");
        }
        Files.createDirectories(results);
        if (!results.toRealPath().startsWith(EVALUATION_ROOT.toRealPath())) {
            throw new SafeFailure("RESULT_SYMLINK_OUTSIDE_OWNED_DIRECTORY");
        }
        for (Job job : jobs) {
            if (Files.exists(results.resolve(job.id + ".json"))
                    || Files.exists(results.resolve(job.id + ".md"))
                    || Files.exists(results.resolve(job.id + ".claim.json"))) {
                throw new SafeFailure("RESULT_ALREADY_EXISTS_USE_NEW_JOB_ID");
            }
        }
        if (validateOnly) {
            LOGGER.info("event=evaluation.status data={}", JSON.writeValueAsString(Map.of("mode", "VALIDATE_ONLY_NO_HTTP", "jobCount", jobs.size(),
                    "publishedExecutorIds", selectedExecutors.stream().sorted().toList(), "paidCalls", 0)));
            return;
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(120));
        RestClient client = RestClient.builder().requestFactory(factory).build();
        for (Job job : jobs) { execute(client, properties, job, results); }
    }

    /** Reserves an immutable job identifier before HTTP, retaining every first failure. */
    private static void execute(RestClient client, OpenAiCompatibleProperties properties, Job job, Path results)
            throws Exception {
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("jobId", job.id);
        record.put("id", job.id);
        record.put("caseId", safeMetadata(job.caseId));
        record.put("arm", safeMetadata(job.arm));
        record.put("requestId", requestId);
        record.put("requestedPublishedModelId", job.modelId);
        record.put("upstreamModel", safeMetadata(job.selection.model()));
        record.put("startedAt", Instant.now().toString());
        record.put("attempt", 1);
        record.put("automaticRetry", false);
        record.put("promptHash", job.promptHash);
        record.put("materialsHash", job.materialsHash);
        record.put("executionUserSha256", sha256(job.user));
        record.put("systemSha256", sha256(job.system));
        record.put("maxOutputTokens", job.maxTokens);
        record.put("temperature", "kimi-k3".equalsIgnoreCase(job.selection.model()) ? null : 0.2D);
        record.put("thinking", directDeepSeek(job) ? "disabled" : "route_default");
        record.put("status", "STARTED");
        writeNew(results.resolve(job.id + ".claim.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record));
        long started = System.nanoTime();
        try {
            Completion completion = client.post().uri(job.selection.route().endpoint())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + job.selection.route().apiKey())
                    .header("X-Request-Id", requestId)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(requestBody(job))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status < 200 || status >= 300) { return new Completion(status, null); }
                        return new Completion(status, JSON.readTree(response.getBody()));
                    });
            // Includes connection, upstream inference, response transfer and JSON parse; not pure inference.
            record.put("singleHttpCallMs", (System.nanoTime() - started) / 1_000_000L);
            record.put("httpStatus", completion.status);
            if (completion.status < 200 || completion.status >= 300) {
                record.put("status", "FAILED");
                record.put("failureCategory", statusCategory(completion.status));
            } else {
                mapCompletion(record, completion.body, properties);
            }
        } catch (ResourceAccessException exception) {
            record.put("singleHttpCallMs", (System.nanoTime() - started) / 1_000_000L);
            record.put("httpStatus", null);
            record.put("status", "FAILED");
            record.put("failureCategory", hasTimeoutCause(exception) ? "TIMEOUT" : "NETWORK_FAILURE");
            record.put("causeTypes", safeCauseTypes(exception));
            record.put("threadInterrupted", Thread.currentThread().isInterrupted());
        } catch (RestClientException exception) {
            record.put("singleHttpCallMs", (System.nanoTime() - started) / 1_000_000L);
            record.put("httpStatus", null);
            record.put("status", "FAILED");
            record.put("failureCategory", "INVALID_OR_UNREADABLE_RESPONSE");
        }
        record.put("endedAt", Instant.now().toString());
        writeNew(results.resolve(job.id + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record));
        if (record.get("output") instanceof String output) { writeNew(results.resolve(job.id + ".md"), output); }
        LOGGER.info("event=evaluation.status data={}", JSON.writeValueAsString(Map.of("jobId", job.id, "status", record.get("status"),
                "attempt", 1, "result", "results/" + job.id + ".json")));
    }

    /** 仅保存异常类型链辅助定位 DNS/连接/超时，不保存可能带端点、正文或凭据的异常消息。 */
    private static List<String> safeCauseTypes(Throwable exception) {
        List<String> types = new ArrayList<>();
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 10; depth++, current = current.getCause()) {
            types.add(current.getClass().getSimpleName());
        }
        return List.copyOf(types);
    }

    /** Uses ordinary task messages; intentionally omits enhancement instructions and response_format. */
    private static Map<String, Object> requestBody(Job job) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", job.selection.model());
        body.put("messages", List.of(Map.of("role", "system", "content", job.system),
                Map.of("role", "user", "content", job.user)));
        body.put("stream", false);
        if ("kimi-k3".equalsIgnoreCase(job.selection.model())) {
            body.put("max_completion_tokens", job.maxTokens);
        } else {
            body.put("temperature", 0.2D);
            body.put("max_tokens", job.maxTokens);
        }
        if (directDeepSeek(job)) {
            // This exact option is already supported by this project's direct DeepSeek route adapter.
            body.put("thinking", Map.of("type", "disabled"));
        }
        // GLM receives no undocumented thinking option; preserve its ordinary route protocol.
        return body;
    }

    private static boolean directDeepSeek(Job job) {
        return ("deepseek".equalsIgnoreCase(job.selection.route().key())
                || "deepseek".equalsIgnoreCase(job.selection.route().providerName()))
                && ("deepseek-flash".equalsIgnoreCase(job.selection.model())
                || "deepseek-v4-pro".equalsIgnoreCase(job.selection.model()));
    }

    /** Persists only actual business output and allowlisted, safe response metadata. */
    private static void mapCompletion(Map<String, Object> record, JsonNode body,
                                      OpenAiCompatibleProperties properties) {
        if (body == null || !body.path("choices").isArray() || body.path("choices").isEmpty()) {
            record.put("status", "FAILED"); record.put("failureCategory", "EMPTY_RESPONSE"); return;
        }
        JsonNode first = body.path("choices").get(0);
        JsonNode content = first.path("message").path("content");
        String output = content.isTextual() ? content.textValue() : "";
        record.put("actualModel", safeMetadata(body.path("model").asText("")));
        record.put("finishReason", safeMetadata(first.path("finish_reason").asText("")));
        record.put("completionContentChars", output.length());
        // Reasoning text is never retained; only allowlisted token totals and character counts are persisted.
        JsonNode reasoningContent = first.path("message").path("reasoning_content");
        if (!reasoningContent.isTextual()) { reasoningContent = first.path("message").path("reasoning"); }
        record.put("reasoningContentChars", reasoningContent.isTextual() ? reasoningContent.textValue().length() : null);
        JsonNode reasoningTokens = body.path("usage").path("completion_tokens_details").path("reasoning_tokens");
        String reasoningTokensSource = "usage.completion_tokens_details.reasoning_tokens";
        if (!reasoningTokens.isIntegralNumber()) {
            reasoningTokens = body.path("usage").path("reasoning_tokens");
            reasoningTokensSource = "usage.reasoning_tokens";
        }
        boolean hasReasoningTokens = reasoningTokens.isIntegralNumber()
                && reasoningTokens.canConvertToLong() && reasoningTokens.longValue() >= 0;
        record.put("reasoningTokens", hasReasoningTokens ? reasoningTokens.longValue() : null);
        record.put("reasoningTokensSource", hasReasoningTokens ? reasoningTokensSource : null);
        Map<String, Object> usage = new LinkedHashMap<>();
        for (String key : List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
            JsonNode item = body.path("usage").path(key);
            usage.put(key, item.isIntegralNumber() && item.canConvertToLong() && item.longValue() >= 0
                    ? item.longValue() : null);
        }
        record.put("usage", usage);
        if (output.isBlank()) {
            record.put("status", "FAILED"); record.put("failureCategory", "EMPTY_TASK_OUTPUT"); return;
        }
        if (output.length() > 200_000 || SENSITIVE.containsCredential(output)
                || includesBoundCredential(properties, output)) {
            record.put("status", "FAILED"); record.put("failureCategory", "SENSITIVE_OR_OVERSIZED_OUTPUT_REJECTED"); return;
        }
        // 有正文但因长度、内容限制或未知结束原因中断，不应记作完整业务交付。
        record.put("status", "stop".equals(first.path("finish_reason").asText()) ? "SUCCESS" : "PARTIAL");
        record.put("output", output);
        record.put("outputChars", output.length());
        record.put("outputSha256", sha256(output));
    }

    private static boolean usable(OpenAiCompatibleRoute route) {
        return route != null && route.apiKey() != null && !route.apiKey().isBlank()
                && !route.apiKey().contains("${") && route.endpoint() != null && route.endpoint().isAbsolute();
    }

    private static boolean includesBoundCredential(OpenAiCompatibleProperties properties, String text) {
        return properties.getConfiguredRoutes().stream().map(OpenAiCompatibleRoute::apiKey)
                .filter(key -> key != null && key.length() >= 8).anyMatch(text::contains);
    }

    private static String requiredText(JsonNode node, int max, String code) {
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > max) {
            throw new SafeFailure(code);
        }
        return node.textValue();
    }

    /** Rejects absolute/symlink escapes and protected input names; inputs must stay in this helper folder. */
    private static Path safeInput(String input) throws IOException {
        Path path = Path.of(input).toAbsolutePath().normalize();
        Path actual = path.toRealPath();
        Path actualRoot = EVALUATION_ROOT.toRealPath();
        if (!actualRoot.startsWith(REPO.toRealPath()) || !actual.startsWith(actualRoot)
                || !actual.getFileName().toString().endsWith(".json")) {
            throw new SafeFailure("JOBS_PATH_OUTSIDE_OWNED_DIRECTORY");
        }
        for (Path part : actual) {
            String value = part.toString().toLowerCase(java.util.Locale.ROOT);
            if (value.equals(".env") || value.endsWith(".pem") || value.endsWith(".key")) {
                throw new SafeFailure("PROTECTED_INPUT_PATH");
            }
        }
        return actual;
    }

    private static String safeMetadata(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:/-]{0,160}") ? value : "UNSAFE_OR_UNKNOWN_METADATA";
    }

    private static String statusCategory(int status) {
        if (status == 401 || status == 403) { return "AUTHENTICATION_REJECTED"; }
        if (status == 429) { return "RATE_LIMIT"; }
        if (status == 408 || status == 504) { return "UPSTREAM_TIMEOUT"; }
        return status >= 500 ? "UPSTREAM_ERROR" : "REQUEST_REJECTED";
    }

    private static boolean hasTimeoutCause(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 10; depth++, current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new SafeFailure("SHA256_UNAVAILABLE"); }
    }

    private static void writeNew(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void fail(String code) {
        LOGGER.error("event=evaluation.stopped reason={}", code);
        System.exit(2);
    }

    private record Job(String id, String caseId, String arm, String modelId, String system, String user,
                       String promptHash, String materialsHash, int maxTokens,
                       OpenAiCompatibleProperties.ModelSelection selection) { }
    private record Completion(int status, JsonNode body) { }
    private static final class SafeFailure extends RuntimeException {
        private final String code;
        private SafeFailure(String code) { this.code = code; }
    }
}
