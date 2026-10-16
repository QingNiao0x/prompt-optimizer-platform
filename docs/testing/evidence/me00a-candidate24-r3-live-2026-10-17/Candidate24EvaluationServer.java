import com.fasterxml.jackson.databind.*;
import com.promptoptimizer.PromptOptimizerApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 仅在隔离的回环评测进程记录已授权合成请求，不记录密钥、认证头或推理内容。 */
public class Candidate24EvaluationServer {
    static final Path ROOT = Path.of("tmp/me00a-20261012").toAbsolutePath();
    static final ObjectMapper JSON = new ObjectMapper();
    static String version;

    /** 使用冻结 class 启动独立测试端口；保留原服务和数据库迁移状态。 */
    public static void main(String[] args) throws Exception {
        version = args[0];
        if (!version.equals("candidate24-r3")) throw new IllegalArgumentException("Only frozen candidate24-r3 allowed");
        // Preflight can start the local server, but the interceptor still rejects every unapproved model request.
        if (args.length != 2 || !"--preflight".equals(args[1])) approvedBatch(JSON.readTree(ROOT.resolve("protocol.json").toFile()));
        Files.createDirectories(ROOT.resolve("traces"));
        new SpringApplication(PromptOptimizerApplication.class, TraceConfiguration.class).run(
            "--server.address=127.0.0.1", "--server.port=9001", "--spring.flyway.enabled=false",
            "--spring.session.redis.namespace=prompt-optimizer:me00a-20261012",
            "--spring.main.banner-mode=off");
    }

    /** 当前协议的批准状态、版本和轮次均需匹配，旧批次余额不能授权新候选。 */
    static JsonNode approvedBatch(JsonNode protocol) throws IOException {
        JsonNode batch = protocol.path("activeBatch");
        if (!version.equals(batch.path("version").asText())
                || !"APPROVED_BY_USER".equals(batch.path("authorizationStatus").asText())
                || batch.path("secondRoundAuthorized").asBoolean(true) || !"OPEN".equals(batch.path("status").asText()))
            throw new IOException("Batch not authorized for this frozen candidate and round");
        return batch;
    }

    /** 白名单文件只允许本评测目录内的 JSON 文件名，并逐字节验证已批准输入。 */
    static JsonNode frozenCases(JsonNode batch) throws IOException {
        String name = batch.path("casesFile").asText();
        if (!name.matches("[A-Za-z0-9_-]+\\.json")) throw new IOException("Invalid frozen case path");
        byte[] bytes = Files.readAllBytes(ROOT.resolve(name));
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!hash.equals(batch.path("casesSha256").asText())) throw new IOException("Frozen case hash mismatch");
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
        return JSON.readTree(bytes);
    }

    /** 仅接受批准题目的原文或按既有协议追加的实际题答，不扩展到其他任务。 */
    static boolean inputAllowed(JsonNode input, JsonNode cases, JsonNode batch) {
        Set<String> allowed = new HashSet<>();
        batch.path("cases").forEach(item -> allowed.add(item.asText()));
        for (JsonNode msg : input.path("messages")) {
            String content = msg.path("content").asText();
            int start = content.indexOf('{');
            if (start < 0) continue;
            try {
                JsonNode payload = JSON.readTree(content.substring(start));
                if (payload.has("template")) {
                    if (!Set.of("GENERAL", "RESEARCH_ANALYSIS", "FEATURE_DEVELOPMENT").contains(payload.path("template").path("code").asText())) continue;
                    // 本批固定全部题答投影，不能仅保留原题就偷偷补确认或改变未决状态。
                    if (batch.has("expectedTaskView") && (!payload.path("planConfirmed").asBoolean(false)
                            || !payload.path("taskView").equals(batch.path("expectedTaskView")))) return false;
                } else if (!payload.has("questionScope") || !payload.has("taskIntent") || !payload.has("knownDecisions")) continue;
                String raw = payload.path("rawPrompt").asText().strip();
                String rawHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                for (JsonNode hash : batch.path("allowedRawPromptSha256")) if (hash.asText().equals(rawHash)) return true;
                // 当前批次只接受原题和冻结的实际题答组合，不以原题前缀放行任意追加信息。
                if (batch.has("allowedRawPromptSha256")) continue;
                for (JsonNode c : cases) {
                    if (!allowed.contains(c.path("id").asText())) continue;
                    String original = c.path("rawPrompt").asText().strip();
                    if (raw.equals(original) || raw.startsWith(original + "\n\n本次测试实际确认信息")) return true;
                }
            } catch (IOException | java.security.NoSuchAlgorithmException ignored) { /* 非输入 JSON 的系统指导不能授权请求。 */ }
        }
        return false;
    }

    /** Planning and enhancement share the production client but have independent frozen call ceilings. */
    static String inputPurpose(JsonNode input) throws IOException {
        for (JsonNode message : input.path("messages")) {
            if (!"user".equals(message.path("role").asText())) continue;
            String content = message.path("content").asText();
            int start = content.indexOf('{');
            if (start < 0) continue;
            try {
                JsonNode payload = JSON.readTree(content.substring(start));
                if (payload.has("template")) return "PLATFORM_GENERATION";
                if (payload.has("questionScope") && payload.has("taskIntent") && payload.has("knownDecisions")) return "PLANNING";
            } catch (IOException ignored) { /* Repair notes are not a new task payload. */ }
        }
        throw new IOException("Unknown frozen operation");
    }

    @Configuration(proxyBeanMethods=false)
    static class TraceConfiguration {
        /**
         * 明示回放321原始问题，不新增自然Plan推理；正常服务仍校验、过滤、注册并绑定当前身份。
         * 仅替换隔离进程的规划Provider，真实增强Provider及其重试、敏感过滤和权限校验均不变。
         */
        @Bean @Primary com.promptoptimizer.provider.service.PromptPlanningProvider capturedQuestionReplay() {
            return request -> {
                try { return replayCapturedPlanning(request); }
                catch (IOException failure) { throw new IllegalStateException("Captured planning replay scope rejected", failure); }
            };
        }

        @Bean static BeanPostProcessor evaluationTrace() {
            return new BeanPostProcessor() {
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("openAiCompatibleRestClient")) return bean;
                    return ((RestClient) bean).mutate().requestInterceptor((request, body, execution) -> {
                        if (!"https".equals(request.getURI().getScheme()) || !"api.deepseek.com".equals(request.getURI().getHost()))
                            throw new IOException("Only authorized official DeepSeek endpoint allowed");
                        JsonNode input = JSON.readTree(body);
                        JsonNode batch = approvedBatch(JSON.readTree(ROOT.resolve("protocol.json").toFile()));
                        if (!input.path("model").asText().equals(batch.path("model").asText())
                                || !inputAllowed(input, frozenCases(batch), batch))
                            throw new IOException("Input or model outside frozen approved scope");
                        if (input.path("temperature").asDouble() != 0.2 || input.path("max_tokens").asInt() != 10000
                                || !"disabled".equals(input.path("thinking").path("type").asText()))
                            throw new IOException("Generation parameters differ from frozen source");
                        String purpose = inputPurpose(input);
                        Path trace = reserve(purpose);
                        var record = JSON.createObjectNode();
                        record.put("purpose", purpose);
                        record.put("version", version); record.put("phase", "candidateValidation");
                        record.put("requestId", org.slf4j.MDC.get("requestId"));
                        record.put("startedAt", java.time.Instant.now().toString()); record.set("request", input);
                        Files.writeString(trace, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record));
                        long started = System.nanoTime();
                        try {
                            ClientHttpResponse response = execution.execute(request, body);
                            byte[] bytes = response.getBody().readAllBytes();
                            record.put("httpStatus", response.getStatusCode().value());
                            try {
                                JsonNode output = JSON.readTree(bytes);
                                for (JsonNode choice : output.path("choices")) {
                                    if (choice.path("message") instanceof com.fasterxml.jackson.databind.node.ObjectNode message)
                                        message.remove("reasoning_content");
                                }
                                record.set("response", output);
                            } catch (IOException e) {
                                record.put("responseText", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                                record.put("failure", "Provider response was not valid JSON");
                            }
                            record.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
                            Files.writeString(trace, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record));
                            return new ClientHttpResponse() {
                                public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                                public String getStatusText() throws IOException { return response.getStatusText(); }
                                public HttpHeaders getHeaders() { return response.getHeaders(); }
                                public InputStream getBody() { return new ByteArrayInputStream(bytes); }
                                public void close() { response.close(); }
                            };
                        } catch (IOException | RuntimeException e) {
                            record.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
                            record.put("failure", e.getClass().getSimpleName());
                            Files.writeString(trace, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record));
                            throw e;
                        }
                    }).build();
                }
            };
        }
    }

    /** 只读已冻结的同题、空上下文和原始规划记录，拒绝路径扩张、资料串任务及未批准批次。 */
    static com.promptoptimizer.provider.domain.PlanningProviderResponse replayCapturedPlanning(
            com.promptoptimizer.provider.domain.PlanningProviderRequest request) throws IOException {
        JsonNode batch = approvedBatch(JSON.readTree(ROOT.resolve("protocol.json").toFile()));
        if (!batch.path("capturedPlanningReplay").asBoolean(false)
                || !request.contextDescription().isBlank() || !request.conversationHistory().isEmpty())
            throw new IOException("Captured replay only accepts the frozen empty context");
        boolean known = false;
        for (JsonNode item : frozenCases(batch)) {
            if ("Q05".equals(item.path("id").asText()) && item.path("rawPrompt").asText().equals(request.rawPrompt())) known = true;
        }
        if (!known) throw new IOException("Captured replay cannot authorize a new task");
        String name = batch.path("capturedPlanningTraceFile").asText();
        if (!name.matches("[0-9]{3}\\.json")) throw new IOException("Invalid captured trace path");
        byte[] bytes = Files.readAllBytes(ROOT.resolve("traces").resolve(name));
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!hash.equals(batch.path("capturedPlanningTraceSha256").asText())) throw new IOException("Captured trace drift");
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IOException(failure); }
        JsonNode trace = JSON.readTree(bytes);
        if (!"PLANNING".equals(trace.path("purpose").asText())
                || !"candidate23-r2".equals(trace.path("version").asText()) || trace.path("httpStatus").asInt() != 200)
            throw new IOException("Not the previously observed successful planning response");
        JsonNode payload = JSON.readTree(trace.path("response").path("choices").get(0).path("message").path("content").asText());
        return new com.promptoptimizer.provider.domain.PlanningProviderResponse(payload.path("summary").asText(),
                JSON.readerForListOf(com.promptoptimizer.enhancement.domain.PlanQuestion.class).readValue(payload.path("questions")),
                "CAPTURED_ACTUAL_321_REPLAY", "deepseek-flash", true);
    }

    /** 每次真实尝试预占一个槽位；阶段、当前组和总批次上限同时生效。 */
    static synchronized Path reserve(String purpose) throws IOException {
        JsonNode protocol = JSON.readTree(ROOT.resolve("protocol.json").toFile());
        JsonNode batch = approvedBatch(protocol);
        long generationCount = 0;
        try (var traces = Files.list(ROOT.resolve("traces"))) {
            for (Path saved : traces.filter(path -> path.toString().endsWith(".json")).toList()) {
                try {
                    JsonNode previous = JSON.readTree(saved.toFile());
                    if (version.equals(previous.path("version").asText())
                            && purpose.equals(previous.path("purpose").asText())) generationCount++;
                } catch (IOException ignored) { /* 空预占仍由总调用上限计数，不能补用。 */ }
            }
        }
        if (generationCount >= batch.path("purposeMaximums").path(purpose).asInt(0)) throw new IOException("Purpose group budget exhausted");
        int actualLimit = Math.min(protocol.path("maximumActualModelCalls").asInt(), Math.min(
            batch.path("callsBefore").asInt() + batch.path("maximumNewCalls").asInt(),
            batch.path("maximumActualCallsAfterBatch").asInt()));
        long phaseCount;
        try (var stream = Files.list(ROOT.resolve("traces"))) {
            phaseCount = stream.filter(p -> p.toString().endsWith(".json")).filter(p -> {
                try { return "candidateValidation".equals(JSON.readTree(p.toFile()).path("phase").asText()); }
                catch (IOException e) { return false; }
            }).count();
        }
        if (phaseCount >= protocol.path("phaseLimits").path("candidateValidation").asInt())
            throw new IOException("Phase request budget exhausted");
        for (int i = 1; i <= actualLimit; i++) {
            Path path = ROOT.resolve("traces").resolve(String.format("%03d.json", i));
            try { Files.createFile(path); return path; } catch (FileAlreadyExistsException e) { }
        }
        throw new IOException("Evaluation actual request budget exhausted");
    }
}
