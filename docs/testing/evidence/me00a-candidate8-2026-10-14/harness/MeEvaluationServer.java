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
public class MeEvaluationServer {
    static final Path ROOT = Path.of("tmp/me00a-20261012").toAbsolutePath();
    static final ObjectMapper JSON = new ObjectMapper();
    static String version;

    /** 使用冻结 class 启动独立测试端口；保留原服务和数据库迁移状态。 */
    public static void main(String[] args) throws Exception {
        version = args[0];
        if (!version.equals("candidate8")) throw new IllegalArgumentException("Only frozen candidate8 allowed");
        approvedBatch(JSON.readTree(ROOT.resolve("protocol.json").toFile()));
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
                || batch.path("secondRoundAuthorized").asBoolean(true))
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
                String raw = JSON.readTree(content.substring(start)).path("rawPrompt").asText().strip();
                for (JsonNode c : cases) {
                    if (!allowed.contains(c.path("id").asText())) continue;
                    String original = c.path("rawPrompt").asText().strip();
                    if (raw.equals(original) || raw.startsWith(original + "\n\n本次测试实际确认信息")) return true;
                }
            } catch (IOException ignored) { /* 非输入 JSON 的系统指导不能授权请求。 */ }
        }
        return false;
    }

    @Configuration(proxyBeanMethods=false)
    static class TraceConfiguration {
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
                        Path trace = reserve();
                        var record = JSON.createObjectNode();
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

    /** 每次真实尝试预占一个槽位；阶段、当前组和总批次上限同时生效。 */
    static synchronized Path reserve() throws IOException {
        JsonNode protocol = JSON.readTree(ROOT.resolve("protocol.json").toFile());
        JsonNode batch = approvedBatch(protocol);
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
