import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.PromptOptimizerApplication;
import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.enhancement.domain.PromptDocument;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.domain.OptimizationPlan;
import com.promptoptimizer.enhancement.service.impl.NaturalEnhancementService;
import com.promptoptimizer.history.service.OptimizationHistoryService;
import com.promptoptimizer.identity.service.CurrentActor;
import com.promptoptimizer.provider.infrastructure.concurrency.ModelConcurrencyLimiter;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * 本批次专用的回环评测入口：真实登录、服务、历史与上游；每次网络尝试先占预算并保留原件。
 * 仅在独立临时类路径启动，不进入产品编译或生产路由；不捕获认证头、凭据或隐藏推理内容。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class NaturalFidelityModelControlEvaluationServer {
    static final Path ROOT = Path.of(System.getProperty("natural.eval.root", "tmp/natural-fidelity-model-control-20261022")).toAbsolutePath();
    static final Path EVIDENCE = Path.of("docs/testing/evidence/natural-fidelity-model-control-2026-10-22").toAbsolutePath();
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static String protocolName = "protocol.json";
    static String manifestName = "source-manifest.json";

    /** 启动单独的本机进程；不执行Flyway，不创建任何账号、租户或工作区。 */
    public static void main(String[] args) throws Exception {
        Files.createDirectories(ROOT.resolve("traces"));
        new SpringApplication(PromptOptimizerApplication.class, EvaluationConfiguration.class).run(
                "--server.address=127.0.0.1", "--server.port=9003", "--spring.flyway.enabled=false",
                "--spring.session.redis.namespace=prompt-optimizer:natural-fidelity-model-control-20261022",
                "--app.security.bootstrap-admin.enabled=false", "--app.security.bootstrap-user.password=",
                "--app.security.registration.enabled=false", "--spring.main.banner-mode=off");
    }

    /** 附加入口只注册于本批次进程；真实生成仍经过产品安全配置与上游并发拦截器。 */
    @Configuration(proxyBeanMethods = false)
    static class EvaluationConfiguration {
        @Bean
        EvaluationController evaluationController(NaturalEnhancementService natural, OptimizationHistoryService history,
                CurrentActor actor, ModelConcurrencyLimiter limiter,
                @org.springframework.beans.factory.annotation.Qualifier("openAiCompatibleRestClient") RestClient client,
                OpenAiCompatibleProperties properties, PlatformModelCatalog catalog) {
            return new EvaluationController(natural, history, actor, limiter, client, properties, catalog);
        }

        /** 传输层计数包括协议修复、失败、执行与评审，业务HTTP请求数不能代替实际调用数。 */
        @Bean
        static BeanPostProcessor actualCallGuard() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("openAiCompatibleRestClient")) return bean;
                    return ((RestClient) bean).mutate().requestInterceptor((request, body, execution) -> {
                        JsonNode protocol = JSON.readTree(EVIDENCE.resolve(protocolName).toFile());
                        JsonNode job = JSON.readTree(ROOT.resolve("active-job.json").toFile());
                        if (protocol.has("allowedJobs")) {
                            boolean allowed = false;
                            for (JsonNode id : protocol.path("allowedJobs")) if (id.asText().equals(job.path("id").asText())) allowed = true;
                            if (!allowed) throw new IOException("Job outside frozen correction scope");
                        }
                        if (!request.getURI().toString().equals(protocol.path("endpoint").asText())) throw new IOException("Endpoint outside frozen batch");
                        JsonNode payload = JSON.readTree(body);
                        if (!payload.path("model").asText().equals(protocol.path("upstreamModel").asText())
                                || payload.path("temperature").asDouble() != 0.2
                                || !payload.path("thinking").path("type").asText().equals("disabled")
                                || payload.path("stream").asBoolean()) throw new IOException("Model parameters outside frozen batch");
                        int maximum = job.path("phase").asText().equals("REVIEW") ? 8192
                                : job.path("phase").asText().equals("EXECUTION") ? 16384 : 8192;
                        if (payload.path("max_tokens").asInt() != maximum) throw new IOException("Token limit outside frozen batch");
                        verifyFrozenSources();
                        String phase = job.path("phase").asText();
                        if (Set.of("GENERATION", "PLAN").contains(phase)) {
                            JsonNode sample = sample(job.path("caseId").asText());
                            String user = payload.path("messages").path(1).path("content").asText();
                            int start = user.indexOf('{');
                            if (start < 0 || !JSON.readTree(user.substring(start)).path("rawPrompt").asText().equals(sample.path("rawPrompt").asText())
                                    || !JSON.readTree(user.substring(start)).path("strategyVersion").asText().equals(job.path("version").asText()))
                                throw new IOException("Generation input outside frozen cases");
                        } else if (!payload.equals(job.path("approvedModelRequest"))) {
                            throw new IOException("Execution or review differs from the frozen job");
                        }
                        Path reservation = reserve(job, protocol);
                        ObjectNode trace = JSON.createObjectNode();
                        trace.put("batch", protocol.path("batch").asText());
                        trace.put("jobId", job.path("id").asText());
                        trace.put("phase", phase);
                        trace.put("caseId", job.path("caseId").asText());
                        trace.set("request", payload);
                        if (phase.equals("GENERATION")) {
                            String text = payload.path("messages").path(1).path("content").asText();
                            JsonNode taskInput = JSON.readTree(text.substring(text.indexOf('{')));
                            trace.put("generationStage", taskInput.has("fidelityFindings") ? "PATCH" : taskInput.has("fidelityDraft") ? "DETECT"
                                    : taskInput.has("completionSuggestions") ? "REWRITE"
                                    : job.path("version").asText().equals("16") ? "PREPARATION" : "DIRECT");
                        }
                        Files.writeString(reservation, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(trace));
                        long start = System.nanoTime();
                        try {
                            ClientHttpResponse response = execution.execute(request, body);
                            byte[] bytes = response.getBody().readAllBytes();
                            JsonNode output = JSON.readTree(bytes);
                            // 原响应返回产品解析；公开证据删除隐藏推理字段，永不读取或保存认证头。
                            JsonNode captured = output.deepCopy();
                            for (JsonNode choice : captured.path("choices")) {
                                if (choice.path("message") instanceof ObjectNode message) message.remove(List.of("reasoning_content", "reasoning"));
                            }
                            trace.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
                            trace.put("httpStatus", response.getStatusCode().value());
                            trace.set("response", captured);
                            Files.writeString(reservation, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(trace));
                            return new ClientHttpResponse() {
                                public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                                public String getStatusText() throws IOException { return response.getStatusText(); }
                                public HttpHeaders getHeaders() { return response.getHeaders(); }
                                public InputStream getBody() { return new ByteArrayInputStream(bytes); }
                                public void close() { response.close(); }
                            };
                        } catch (IOException | RuntimeException failure) {
                            trace.put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
                            trace.put("transportFailure", failure.getClass().getSimpleName());
                            Files.writeString(reservation, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(trace));
                            throw failure;
                        }
                    }).build();
                }
            };
        }
    }

    /** 受真实身份及CSRF保护的临时对照入口；不能通过模型正文改写版本或调用任意URL。 */
    @RestController
    static class EvaluationController {
        private final NaturalEnhancementService natural;
        private final OptimizationHistoryService history;
        private final CurrentActor actor;
        private final ModelConcurrencyLimiter limiter;
        private final RestClient client;
        private final OpenAiCompatibleProperties properties;
        private final PlatformModelCatalog catalog;

        EvaluationController(NaturalEnhancementService natural, OptimizationHistoryService history, CurrentActor actor,
                ModelConcurrencyLimiter limiter, RestClient client, OpenAiCompatibleProperties properties, PlatformModelCatalog catalog) {
            this.natural = natural; this.history = history; this.actor = actor; this.limiter = limiter;
            this.client = client; this.properties = properties; this.catalog = catalog;
        }

        /** 冻结第四版与新版本使用相同资料，基线也保存实际历史结果；不新增用户可选择的产品版本字段。 */
        @PostMapping("/api/v2/evaluation/baseline")
        ApiResponse<PromptDocument> baseline(@Valid @RequestBody OptimizationRequest input, HttpServletRequest request) throws Exception {
            requireCanonical(request);
            try (var permit = limiter.acquireUser(actor.require().userId())) {
                PromptDocument result = natural.optimize(input, "4");
                UUID id = history.saveDocument(input, result);
                return ApiResponse.success(request.getHeader("X-Evaluation-Job"), result.withRecordId(id));
            }
        }

        /** 当前基线Plan显式使用冻结第四版，仍经过真实所有权、来源与问题校验。 */
        @PostMapping("/api/v2/evaluation/plan-baseline")
        ApiResponse<OptimizationPlan> baselinePlan(@Valid @RequestBody OptimizationPlanRequest input, HttpServletRequest request) throws Exception {
            requireCanonical(request);
            try (var permit = limiter.acquireUser(actor.require().userId())) {
                return ApiResponse.success(request.getHeader("X-Evaluation-Job"), natural.plan(input, "4"));
            }
        }

        /** 候选仅收敛第四版正文补全指导，默认策略在验收采用前不切换。 */
        @PostMapping("/api/v2/evaluation/candidate")
        ApiResponse<PromptDocument> candidate(@Valid @RequestBody OptimizationRequest input, HttpServletRequest request) throws Exception {
            requireCanonical(request);
            try (var permit = limiter.acquireUser(actor.require().userId())) {
                String version = JSON.readTree(ROOT.resolve("active-job.json").toFile()).path("version").asText();
                if (!Set.of("18").contains(version)) throw new IllegalArgumentException("Unknown candidate");
                PromptDocument result = natural.optimize(input, version);
                UUID id = history.saveDocument(input, result);
                return ApiResponse.success(request.getHeader("X-Evaluation-Job"), result.withRecordId(id));
            }
        }

        /** 候选Plan保持真实身份、来源和会话注册；不使用伪造的已回答计划。 */
        @PostMapping("/api/v2/evaluation/plan-candidate")
        ApiResponse<OptimizationPlan> candidatePlan(@Valid @RequestBody OptimizationPlanRequest input, HttpServletRequest request) throws Exception {
            requireCanonical(request);
            try (var permit = limiter.acquireUser(actor.require().userId())) {
                return ApiResponse.success(request.getHeader("X-Evaluation-Job"), natural.plan(input, "18"));
            }
        }

        /** 实际执行和辅助评审只接受已冻结的合成任务；密钥仅从已配置服务端路由读取。 */
        @PostMapping("/api/v2/evaluation/execute")
        ApiResponse<Map<String, Object>> execute(@RequestBody JsonNode body, HttpServletRequest request) throws Exception {
            requireCanonical(request);
            JsonNode job = JSON.readTree(ROOT.resolve("active-job.json").toFile());
            if (!Set.of("EXECUTION", "REVIEW").contains(job.path("phase").asText())
                    || !body.equals(job.path("approvedModelRequest"))) throw new IllegalArgumentException("Unexpected evaluation job");
            var model = catalog.resolve("deepseek:deepseek-v4-pro");
            var route = properties.getConfiguredRoutes().stream().filter(r -> r.key().equals(model.routeKey())).findFirst().orElseThrow();
            try (var permit = limiter.acquireUser(actor.require().userId())) {
                JsonNode result = client.post().uri(route.endpoint()).header(HttpHeaders.AUTHORIZATION, "Bearer " + route.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
                return ApiResponse.success(request.getHeader("X-Evaluation-Job"), Map.of(
                        "content", result.path("choices").path(0).path("message").path("content").asText(),
                        "finishReason", result.path("choices").path(0).path("finish_reason").asText(), "usage", result.path("usage")));
            }
        }

        /** 固定复用既有合成账号；请求标识不能替代真实认证，也不能授权跨身份操作。 */
        private void requireCanonical(HttpServletRequest request) throws Exception {
            if (!Set.of("127.0.0.1", "::1").contains(request.getRemoteAddr())) throw new IllegalArgumentException("Loopback required");
            JsonNode account = JSON.readTree(Path.of(System.getProperty("natural.eval.accountFile")).toFile());
            var identity = actor.require();
            if (!identity.userId().toString().equals(account.path("userId").asText())
                    || !identity.tenantId().toString().equals(account.path("tenantId").asText())
                    || !identity.workspaceId().toString().equals(account.path("workspaceId").asText()))
                throw new IllegalArgumentException("Canonical fixture required");
        }
    }

    /** 新批次独立预留，不转移旧闭批余额；未完成预留也占预算，禁止并发超限。 */
    static synchronized Path reserve(JsonNode job, JsonNode protocol) throws IOException {
        if (!protocol.path("status").asText().equals("FROZEN") || Files.exists(ROOT.resolve("closure.json")) || Files.exists(EVIDENCE.resolve("closure.json")))
            throw new IOException("Evaluation batch is closed");
        String phase = job.path("phase").asText();
        if (!job.path("id").asText().matches("[A-Z0-9_-]+") || !protocol.path("phaseLimits").has(phase)) throw new IOException("Invalid frozen job");
        List<Path> traces;
        try (var files = Files.list(ROOT.resolve("traces"))) { traces = files.filter(p -> p.toString().endsWith(".json")).toList(); }
        long count = 0;
        for (Path file : traces) {
            try {
                JsonNode trace = JSON.readTree(file.toFile());
                if (trace == null || trace.path("phase").asText().isBlank() || trace.path("phase").asText().equals(phase)) count++;
            }
            catch (IOException failure) { count++; }
        }
        if (traces.size() >= protocol.path("maximumNewActualCalls").asInt()
                || count >= protocol.path("phaseLimits").path(phase).asInt()) throw new IOException("Actual call budget exhausted");
        for (int i = 1; i <= protocol.path("maximumNewActualCalls").asInt(); i++) {
            Path target = ROOT.resolve("traces").resolve(String.format("%03d.json", i));
            try { return Files.createFile(target); } catch (FileAlreadyExistsException ignored) { }
        }
        throw new IOException("Actual call budget exhausted");
    }

    /** 冻结题目、自然生成源码及运行副本；后续编译或改文件不能静默改变正在评测的候选。 */
    static void verifyFrozenSources() throws IOException {
        JsonNode manifest = JSON.readTree(EVIDENCE.resolve(manifestName).toFile());
        for (JsonNode file : manifest.path("files")) {
            byte[] bytes = Files.readAllBytes(Path.of(file.path("path").asText()));
            try {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!hash.equals(file.path("sha256").asText())) throw new IOException("Frozen source drift");
            } catch (java.security.GeneralSecurityException failure) { throw new IOException("Hash unavailable", failure); }
        }
    }

    static JsonNode sample(String id) throws IOException {
        for (JsonNode sample : JSON.readTree(EVIDENCE.resolve("cases.json").toFile()).path("cases")) {
            if (sample.path("id").asText().equals(id)) return sample;
        }
        throw new IOException("Case outside frozen batch");
    }
}
