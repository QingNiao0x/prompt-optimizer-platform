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

/** 仅记录冻结合成任务；批准、路径白名单、阶段预算与上游端点保护继续生效。 */
public class Candidate30EvaluationServer {
    static final Path ROOT = Path.of("tmp/me00a-20261012").toAbsolutePath();
    static final ObjectMapper JSON = new ObjectMapper();
    static String version;
    public static void main(String[] args) throws Exception {
        version="candidate30-live";
        new SpringApplication(PromptOptimizerApplication.class, TraceConfiguration.class).run(
            "--server.address=127.0.0.1", "--server.port=9001", "--spring.flyway.enabled=false",
            "--spring.session.redis.namespace=prompt-optimizer:candidate30", "--spring.main.banner-mode=off");
    }
    static JsonNode approvedBatch(JsonNode protocol) throws IOException {
        JsonNode batch = protocol.path("activeBatch");
        if (!version.equals(batch.path("version").asText())
                || !"APPROVED_BY_USER".equals(batch.path("authorizationStatus").asText())
                || !batch.path("secondRoundAuthorized").asBoolean(false) || !"OPEN".equals(batch.path("status").asText()))
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

    /** 逐字输入、完整任务视图及固定开关均须匹配；修复提示本身不能授权新任务。 */
    static boolean inputAllowed(JsonNode input, JsonNode cases, JsonNode batch) {
        for(JsonNode msg:input.path("messages")) {
            if(!"user".equals(msg.path("role").asText())) continue;
            String content=msg.path("content").asText(); int start=content.indexOf('{'); if(start<0) continue;
            try {
                JsonNode payload=JSON.readTree(content.substring(start));
                String raw=payload.path("rawPrompt").asText().strip();
                boolean exact=false;
                for(JsonNode sample:cases) if(raw.equals(sample.path("rawPrompt").asText())) exact=true;
                if(!exact || !payload.path("conversationHistory").isEmpty()) continue;
                if(payload.has("template")) {
                    if(payload.path("options").path("includeExamples").asBoolean()) return false;
                    boolean view=false;
                    for(JsonNode expected:batch.path("expectedTaskViews")) if(payload.path("taskView").equals(expected)) view=true;
                    if(!view) return false;
                } else if(!payload.has("questionScope") || !payload.has("taskIntent") || !payload.has("knownDecisions")) continue;
                return true;
            }catch(IOException ignored) { }
        }
        return false;
    }
    static String inputPurpose(JsonNode input) throws IOException {
        for(JsonNode msg:input.path("messages")) {
            String content=msg.path("content").asText();int start=content.indexOf('{');if(!"user".equals(msg.path("role").asText())||start<0)continue;
            try {JsonNode payload=JSON.readTree(content.substring(start));if(payload.has("template"))return "PLATFORM_GENERATION";if(payload.has("questionScope"))return "PLANNING";}catch(IOException ignored){}
        }
        throw new IOException("Unknown frozen operation");
    }
    @Configuration(proxyBeanMethods=false)
    public static class TraceConfiguration {
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
