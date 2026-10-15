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
        if (!version.equals("candidate15")) throw new IllegalArgumentException("Only frozen candidate15 allowed");
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
                JsonNode payload = JSON.readTree(content.substring(start));
                if (!"RESEARCH_ANALYSIS".equals(payload.path("template").path("code").asText())) continue;
                String raw = payload.path("rawPrompt").asText().strip();
                String rawHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                for (JsonNode hash : batch.path("allowedRawPromptSha256")) if (hash.asText().equals(rawHash)) return true;
                // 当前批次只接受原题和冻结的实际四题答案组合，不以原题前缀放行任意追加信息。
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

    @Configuration(proxyBeanMethods=false)
    static class TraceConfiguration {
        @Bean ReplayPlanController replayPlan(com.promptoptimizer.enhancement.service.PlanningSessionService sessions,
                com.promptoptimizer.identity.service.CurrentActor actor) { return new ReplayPlanController(sessions, actor); }
        /** Captured-response replay: no provider network request, budget unit or copied answer is fabricated. */
        @Bean static BeanPostProcessor capturedResponseReplay() {
            return new BeanPostProcessor() {
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("openAiCompatibleRestClient")) return bean;
                    return ((RestClient) bean).mutate().requestInterceptor((request, body, ignoredNetworkExecution) -> {
                        JsonNode batch = approvedBatch(JSON.readTree(ROOT.resolve("protocol.json").toFile()));
                        JsonNode input = JSON.readTree(body);
                        if (!"OPEN".equals(batch.path("status").asText()) || !"candidate15".equals(version)
                                || !inputAllowed(input, frozenCases(batch), batch) || !"deepseek-flash".equals(input.path("model").asText())
                                || input.path("temperature").asDouble() != 0.2 || input.path("max_tokens").asInt() != 10000
                                || !"disabled".equals(input.path("thinking").path("type").asText())
                                || !"https".equals(request.getURI().getScheme()) || !"api.deepseek.com".equals(request.getURI().getHost()))
                            throw new IOException("Captured response replay outside frozen original scope");
                        byte[] traceBytes = Files.readAllBytes(ROOT.resolve("traces/240.json"));
                        try {
                            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(traceBytes))
                                    .equals(batch.path("capturedResponseReplay").path("traceSha256").asText()))
                                throw new IOException("Original provider trace hash drifted");
                        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
                        JsonNode original = JSON.readTree(traceBytes).path("response");
                        var transport = original.deepCopy();
                        // Replaying saved content does not consume or report the original token usage a second time.
                        ((com.fasterxml.jackson.databind.node.ObjectNode) transport).remove("usage");
                        byte[] bytes = JSON.writeValueAsBytes(transport);
                        var receipt = JSON.createObjectNode();receipt.put("mode", "CAPTURED_RESPONSE_REPLAY");
                        receipt.put("sourceAttempt", 240);receipt.put("newEnhancementModelRequests", 0);receipt.set("currentRequest", input);
                        receipt.put("originalContentUnchanged", true);receipt.put("usageRemovedToAvoidFalseNewTokenAccounting", true);
                        receipt.set("originalResponse", original);receipt.set("transportResponse", transport);
                        Path receiptPath = Path.of("tmp/me00a-candidate15-20261015/captured-response-replay.json");
                        Files.writeString(receiptPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(receipt), StandardOpenOption.CREATE_NEW);
                        return new ClientHttpResponse() {
                            public HttpStatusCode getStatusCode() { return HttpStatus.OK; }
                            public String getStatusText() { return "Captured response replay"; }
                            public HttpHeaders getHeaders() { var headers = new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);return headers; }
                            public InputStream getBody() { return new ByteArrayInputStream(bytes); }
                            public void close() { }
                        };
                    }).build();
                }
            };
        }
    }

    /** 仅在回环测试进程恢复已有真实四题；按当前认证身份注册，不绕过所有权、指纹、安全分析或 TTL。 */
    @org.springframework.web.bind.annotation.RestController
    static class ReplayPlanController {
        private final com.promptoptimizer.enhancement.service.PlanningSessionService sessions;
        private final com.promptoptimizer.identity.service.CurrentActor actor;
        ReplayPlanController(com.promptoptimizer.enhancement.service.PlanningSessionService sessions,
                com.promptoptimizer.identity.service.CurrentActor actor) { this.sessions = sessions; this.actor = actor; }

        @org.springframework.web.bind.annotation.PostMapping("/api/v1/local-evaluation/replay-plan")
        public Object replay() throws Exception {
            JsonNode batch = approvedBatch(JSON.readTree(ROOT.resolve("protocol.json").toFile()));
            var identity = actor.require();
            JsonNode account = JSON.readTree(Path.of("tmp/local-acceptance/account.json").toFile());
            if (!identity.userId().toString().equals(account.path("userId").asText())
                    || !identity.tenantId().toString().equals(account.path("tenantId").asText())
                    || !identity.workspaceId().toString().equals(account.path("workspaceId").asText()))
                throw new IOException("Canonical actor scope mismatch");
            JsonNode source = checkedFixture(batch, "planInputFile", "planInputSha256");
            JsonNode questionSource = checkedFixture(batch, "questionsFile", "questionsSha256");
            var questions = new ArrayList<com.promptoptimizer.enhancement.domain.PlanQuestion>();
            for (JsonNode question : questionSource) questions.add(JSON.treeToValue(question,
                    com.promptoptimizer.enhancement.domain.PlanQuestion.class));
            if (questions.size() != 4) throw new IOException("Only original four questions allowed");
            var context = JSON.treeToValue(source.path("context"), com.promptoptimizer.context.dto.ContextAnalysisRequest.class);
            String raw = source.path("rawPrompt").asText();
            var preparation = sessions.prepareContext(new com.promptoptimizer.context.dto.PlanningContextRequest(raw, context,
                    com.promptoptimizer.enhancement.dto.PermissionPolicyInput.empty()));
            var reference = new com.promptoptimizer.enhancement.dto.PlanningContextReference(preparation.contextId(), preparation.version());
            var resolved = sessions.resolveForPlan(reference, raw, context.customDescription());
            var registration = sessions.registerPlan(raw, context.customDescription(), List.of(), resolved, questions,
                    source.path("modelId").asText());
            return Map.of("planId", registration.planId(), "planningContext", registration.planningContext(),
                    "questionCount", questions.size(), "originalQuestionsRestored", true, "newPlanningModelCalls", 0);
        }

        /** 白名单文件名与批准哈希同时匹配；来源文件不由 HTTP 请求指定。 */
        private JsonNode checkedFixture(JsonNode batch, String nameKey, String hashKey) throws Exception {
            String name = batch.path(nameKey).asText();
            if (!name.matches("[A-Za-z0-9_-]+\\.json")) throw new IOException("Invalid source fixture filename");
            byte[] bytes = Files.readAllBytes(ROOT.resolve(name));
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(batch.path(hashKey).asText()))
                throw new IOException("Source fixture hash mismatch");
            return JSON.readTree(bytes);
        }
    }

}
