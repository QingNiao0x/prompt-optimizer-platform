import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.PromptOptimizerApplication;
import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.enhancement.domain.PromptDocument;
import com.promptoptimizer.enhancement.dto.OptimizationRequest;
import com.promptoptimizer.enhancement.dto.OptimizationPlanRequest;
import com.promptoptimizer.enhancement.domain.OptimizationPlan;
import com.promptoptimizer.enhancement.controller.DocumentOptimizationController;
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
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.client.RestClient;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.*;

/**
 * 本批次专用的回环评测入口：真实登录、服务、历史与上游；每次网络尝试先占预算并保留原件。
 * 仅在独立临时类路径启动，不进入产品编译或生产路由；不捕获认证头、凭据或隐藏推理内容。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class NaturalUsefulDeliveryEvaluationServer {
    static final Path ROOT = Path.of(System.getProperty("natural.eval.root", "tmp/natural-useful-delivery-2026-10-28")).toAbsolutePath();
    static final Path EVIDENCE = Path.of(System.getProperty("natural.eval.evidence")).toAbsolutePath();
    static final Path TASK_ROOT = ROOT.getParent();
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static String protocolName = "protocol.json";
    static String manifestName = "source-manifest.json";

    /** 启动单独的本机进程；不执行Flyway，不创建任何账号、租户或工作区。 */
    public static void main(String[] args) throws Exception {
        requireOpenBatch();
        Files.createDirectories(ROOT.resolve("traces"));
        new SpringApplication(PromptOptimizerApplication.class, EvaluationConfiguration.class).run(
                "--server.address=127.0.0.1", "--server.port=9014", "--spring.flyway.enabled=false",
                "--spring.session.redis.namespace=prompt-optimizer:natural-useful-delivery-2026-10-28:" + ROOT.getFileName(),
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

        /** 普通产品入口同样核对原始DTO；采用验证不依赖浏览器脚本单方保证输入未变化。 */
        @Bean
        DefaultEntryInputGuard defaultEntryInputGuard(EvaluationController controller) {
            return new DefaultEntryInputGuard(controller);
        }

        /** 传输层计数包括协议修复、失败、执行与评审，业务HTTP请求数不能代替实际调用数。 */
        @Bean
        static BeanPostProcessor actualCallGuard() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("openAiCompatibleRestClient")) return bean;
                    return ((RestClient) bean).mutate().requestInterceptor((request, body, execution) -> {
                        requireOpenBatch();
                        JsonNode protocol = JSON.readTree(EVIDENCE.resolve(protocolName).toFile());
                        JsonNode job = JSON.readTree(ROOT.resolve("active-job.json").toFile());
                        if (!Set.of("GENERATION", "PLAN", "EXECUTION", "REVIEW").contains(job.path("phase").asText())) throw new IOException("Phase outside single-pass batch");
                        if (protocol.has("allowedJobs")) {
                            boolean allowed = false;
                            for (JsonNode id : protocol.path("allowedJobs")) if (id.asText().equals(job.path("id").asText())) allowed = true;
                            if (!allowed) throw new IOException("Job outside frozen correction scope");
                        }
                        if (!request.getURI().toString().equals(protocol.path("endpoint").asText())) throw new IOException("Endpoint outside frozen batch");
                        JsonNode payload = JSON.readTree(body);
                        validateModelParameters(protocol, job, payload);
                        verifyFrozenSources();
                        String phase = job.path("phase").asText();
                        NaturalRegistry49CallGuard.Audit registry49Audit = null;
                        if (Set.of("GENERATION", "PLAN").contains(phase)) {
                            JsonNode sample = sample(job.path("caseId").asText());
                            String user = payload.path("messages").path(1).path("content").asText();
                            int start = user.indexOf('{');
                            if (start < 0) throw new IOException("Missing generation payload");
                            JsonNode input = JSON.readTree(user.substring(start));
                            String version = job.path("version").asText();
                            String payloadVersion = input.path("strategyVersion").asText();
                            boolean versionAllowed = payloadVersion.equals(version);
                            // 31允许冻结4写作协议；候选使用各自版本，明确清单不能由模型正文扩大。
                            for (JsonNode item : protocol.path("generationPayloadVersions").path(version)) {
                                versionAllowed |= item.asText().equals(payloadVersion);
                            }
                            if (!input.path("rawPrompt").asText().equals(sample.path("rawPrompt").asText())
                                    || !containsVersion(version) || !versionAllowed)
                                throw new IOException("Generation input outside frozen cases");
                            // 优化时没附材料不等于执行时缺资料；此实验要求两个阶段数据物理分开。
                            if (!input.findValues("executorMaterials").isEmpty() || !input.findValues("acceptance").isEmpty())
                                throw new IOException("Executor materials or scoring card leaked into enhancement");
                            String expectedContext = sample.path("contextDescription").asText("");
                            if (!input.path("context").path("customDescription").asText("").equals(expectedContext))
                                throw new IOException("Ordinary context changed outside frozen case");
                            for (JsonNode material : sample.path("executorMaterials")) {
                                String content = material.path("content").asText();
                                if (!content.isBlank() && (user.contains(content) || input.toString().contains(JSON.writeValueAsString(content))))
                                    throw new IOException("Executor-only material leaked into enhancement payload");
                            }
                            // 有限registry正文链核验本job真实准备；52同时按完整实际题答核验准备指导，不猜测答案含义。
                            if (phase.equals("GENERATION") && Set.of("49", "50", "51", "52").contains(version)) {
                                registry49Audit = NaturalRegistry49CallGuard.verify(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("54")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify54(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("55")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify55(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("56")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify56(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("57")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify57(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("58")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify58(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("59")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify59(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("60")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify60(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("61")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify61(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("62")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify62(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("63")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify63(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("64")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify64(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("65")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify65(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("66")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify66(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("67")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify67(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("68")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify68(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("69")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify69(JSON, ROOT, protocol, job, input, payload);
                            } else if (phase.equals("GENERATION") && version.equals("70")) {
                                registry49Audit = NaturalRegistry49CallGuard.verify70(JSON, ROOT, protocol, job, input, payload);
                            }
                        } else if (!payload.equals(job.path("approvedModelRequest"))) {
                            throw new IOException("Execution or review differs from the frozen job");
                        }
                        if (phase.equals("EXECUTION")) {
                            JsonNode sample = sample(job.path("caseId").asText());
                            // 冻结作品可用多条user消息分开正文和共同资料；完整payload已逐字校验，不限定材料必须在第一条。
                            StringBuilder executionUserMessages = new StringBuilder();
                            for (JsonNode message : payload.path("messages")) {
                                if (message.path("role").asText().equals("user"))
                                    executionUserMessages.append(message.path("content").asText()).append('\n');
                            }
                            String user = executionUserMessages.toString();
                            for (JsonNode material : sample.path("executorMaterials")) {
                                if (!user.contains(material.path("content").asText()))
                                    throw new IOException("Execution missing frozen executor material");
                            }
                        }
                        Path reservation = reserve(job, protocol);
                        ObjectNode trace = JSON.createObjectNode();
                        trace.put("batch", protocol.path("batch").asText());
                        trace.put("jobId", job.path("id").asText());
                        trace.put("phase", phase);
                        trace.put("caseId", job.path("caseId").asText());
                        if (protocol.has("experimentProfile")) {
                            trace.put("experimentProfile", protocol.path("experimentProfile").asText());
                            trace.put("purpose", job.path("purpose").asText());
                            trace.put("thinkingArm", job.path("thinkingArm").asText());
                        }
                        trace.set("request", payload);
                        if (registry49Audit != null) trace.set(Set.of("61", "62", "63", "64", "65", "66", "67", "68", "69", "70").contains(job.path("version").asText()) ? "bodyFirst" + job.path("version").asText() : Set.of("54", "55", "56", "57", "58", "59", "60").contains(job.path("version").asText())
                                ? "singlePass" + job.path("version").asText() : "registry49", JSON.valueToTree(registry49Audit));
                        if (phase.equals("GENERATION")) {
                            // 实际冻结DTO决定调用路径；作业名只用于定位，不能替代真实Plan绑定状态。
                            JsonNode confirmation = job.path("approvedEnhancementInput").path("planConfirmation");
                            trace.put("generationStage", confirmation.isObject()
                                    && !confirmation.path("planId").asText().isBlank() ? "PLAN_CONFIRMED" : "DIRECT");
                        }
                        Files.writeString(reservation, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(trace));
                        long start = System.nanoTime();
                        try {
                            ClientHttpResponse response = execution.execute(request, body);
                            trace.put("httpStatus", response.getStatusCode().value());
                            byte[] bytes = response.getBody().readAllBytes();
                            // 非JSON失败也保留HTTP及字节指纹；不公开可能含隐藏推理或网关敏感内容的损坏响应。
                            trace.put("responseBytes", bytes.length);
                            try { trace.put("responseSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
                            catch (java.security.GeneralSecurityException failure) { throw new IOException("Hash unavailable", failure); }
                            JsonNode output = JSON.readTree(bytes);
                            if (output == null) throw new IOException("Empty upstream response");
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

    /** 仅附加于临时进程的普通V2入口，不改变产品Controller或登录协议。 */
    @ControllerAdvice(assignableTypes = DocumentOptimizationController.class)
    static final class DefaultEntryInputGuard extends RequestBodyAdviceAdapter {
        private final EvaluationController controller;

        DefaultEntryInputGuard(EvaluationController controller) { this.controller = controller; }

        @Override
        public boolean supports(MethodParameter parameter, java.lang.reflect.Type targetType,
                Class<? extends HttpMessageConverter<?>> converterType) {
            return parameter.getContainingClass().equals(DocumentOptimizationController.class);
        }

        @Override
        public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                java.lang.reflect.Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
            try {
                JsonNode job = JSON.readTree(ROOT.resolve("active-job.json").toFile());
                if (!job.path("entrypoint").asText().equals("DEFAULT"))
                    throw new IOException("Ordinary default entry was not frozen for this job");
                var attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
                controller.requireCanonical(attributes.getRequest());
                controller.verifyEnhancementInput(body);
                return body;
            } catch (Exception failure) {
                throw new IllegalArgumentException("Ordinary evaluation input differs from the frozen job", failure);
            }
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

        /** 未来Pro阶段仅只读核对已发布模型及现有路由，不创建目录、修改默认或调用上游。 */
        @GetMapping("/api/v2/evaluation/model-selection")
        ApiResponse<Map<String, Object>> modelSelection(HttpServletRequest request) throws Exception {
            requireCanonical(request);
            return ApiResponse.success(request.getHeader("X-Evaluation-Job"), nativeModelSelection());
        }

        /** 正式生成前再次解析当前目录，避免展示名称或脚本私有路由代替实际catalog.resolve。 */
        private Map<String, Object> nativeModelSelection() throws IOException {
            JsonNode protocol = JSON.readTree(EVIDENCE.resolve(protocolName).toFile());
            JsonNode job = JSON.readTree(ROOT.resolve("active-job.json").toFile());
            validateOfficialModelProfile(protocol, job);
            var model = catalog.resolve(protocol.path("modelId").asText());
            var route = properties.getConfiguredRoutes().stream().filter(item -> item.key().equals(model.routeKey())).findFirst()
                    .orElseThrow(() -> new IOException("Configured native model route is unavailable"));
            if (!model.publicId().equals("deepseek:deepseek-v4-pro") || !model.upstreamModel().equals("deepseek-v4-pro")
                    || !route.key().equals("deepseek") || !route.endpoint().toString().equals("https://api.deepseek.com/chat/completions"))
                throw new IOException("Published model differs from the frozen official native Pro route");
            return Map.of("modelId", model.publicId(), "upstreamModel", model.upstreamModel(), "routeKey", route.key(),
                    "endpoint", route.endpoint().toString(), "defaultModel", model.defaultModel(),
                    "catalogDefaultModelIds", catalog.available().stream().filter(PlatformModelCatalog.ModelEntry::defaultModel)
                            .map(PlatformModelCatalog.ModelEntry::publicId).sorted().toList());
        }

        /** 31基线与候选都从冻结作业读版本，增强只接收普通上下文，执行资料另行传递。 */
        @PostMapping("/api/v2/evaluation/baseline")
        ApiResponse<PromptDocument> baseline(@Valid @RequestBody OptimizationRequest input, HttpServletRequest request) throws Exception {
            requireCanonical(request);
            verifyEnhancementInput(input);
            try (var permit = limiter.acquireUser(actor.require().userId())) {
                String version = JSON.readTree(ROOT.resolve("active-job.json").toFile()).path("version").asText();
                if (!containsVersion(version)) throw new IllegalArgumentException("Unknown baseline version");
                PromptDocument result = natural.optimize(input, version);
                UUID id = history.saveDocument(input, result);
                return ApiResponse.success(request.getHeader("X-Evaluation-Job"), result.withRecordId(id));
            }
        }

        /** Plan按冻结作业比较基线与候选，仍经过真实所有权、来源与问题校验。 */
        @PostMapping("/api/v2/evaluation/plan-baseline")
        ApiResponse<OptimizationPlan> baselinePlan(@Valid @RequestBody OptimizationPlanRequest input, HttpServletRequest request) throws Exception {
            requireCanonical(request);
            verifyEnhancementInput(input);
            try (var permit = limiter.acquireUser(actor.require().userId())) {
                String version = JSON.readTree(ROOT.resolve("active-job.json").toFile()).path("version").asText();
                if (!containsVersion(version)) throw new IllegalArgumentException("Unknown Plan version");
                return ApiResponse.success(request.getHeader("X-Evaluation-Job"), natural.plan(input, version));
            }
        }

        /** 实质补全候选复用既有自然服务，默认策略由产品配置负责，不在临时入口切换。 */
        @PostMapping("/api/v2/evaluation/candidate")
        ApiResponse<PromptDocument> candidate(@Valid @RequestBody OptimizationRequest input, HttpServletRequest request) throws Exception {
            requireCanonical(request);
            verifyEnhancementInput(input);
            try (var permit = limiter.acquireUser(actor.require().userId())) {
                String version = JSON.readTree(ROOT.resolve("active-job.json").toFile()).path("version").asText();
                if (!containsVersion(version)) throw new IllegalArgumentException("Unknown candidate");
                PromptDocument result = natural.optimize(input, version);
                UUID id = history.saveDocument(input, result);
                return ApiResponse.success(request.getHeader("X-Evaluation-Job"), result.withRecordId(id));
            }
        }

        /** 实际作品执行只接受已冻结的合成任务；密钥仅从已配置服务端路由读取。 */
        @PostMapping("/api/v2/evaluation/execute")
        ApiResponse<Map<String, Object>> execute(@RequestBody JsonNode body, HttpServletRequest request) throws Exception {
            requireCanonical(request);
            JsonNode job = JSON.readTree(ROOT.resolve("active-job.json").toFile());
            if (!Set.of("EXECUTION", "REVIEW").contains(job.path("phase").asText())
                    || !body.equals(job.path("approvedModelRequest"))) throw new IllegalArgumentException("Unexpected evaluation job");
            JsonNode executionProtocol = JSON.readTree(EVIDENCE.resolve(protocolName).toFile());
            if (executionProtocol.path("modelSelectionProfile").path("name").asText().equals("NATIVE_PRO52_PREPARE_MECHANISM_V1")) nativeModelSelection();
            var model = catalog.resolve(executionProtocol.path("modelId").asText());
            var route = properties.getConfiguredRoutes().stream().filter(r -> r.key().equals(model.routeKey())).findFirst().orElseThrow();
            try (var permit = limiter.acquireUser(actor.require().userId())) {
                JsonNode result = client.post().uri(route.endpoint()).header(HttpHeaders.AUTHORIZATION, "Bearer " + route.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
                return ApiResponse.success(request.getHeader("X-Evaluation-Job"), Map.of(
                        "content", result.path("choices").path(0).path("message").path("content").asText(),
                        "finishReason", result.path("choices").path(0).path("finish_reason").asText(), "usage", result.path("usage")));
            }
        }

        /** 真实浏览器检索结果先冻结，再逐字核对DTO，避免以脚本简化材料替代真实入口。
         * Plan与直接增强各自使用原DTO规范化，不放宽来源或实际题答归属保护。 */
        private void verifyEnhancementInput(Object input) throws IOException {
            JsonNode job = JSON.readTree(ROOT.resolve("active-job.json").toFile());
            JsonNode expected = job.path("approvedEnhancementInput");
            if (expected.isMissingNode() || expected.isNull()) throw new IOException("Frozen actual browser input required");
            Object normalized = JSON.treeToValue(expected, input.getClass());
            if (!JSON.valueToTree(input).equals(JSON.valueToTree(normalized)))
                throw new IOException("Actual request differs from frozen browser capture");
            if (JSON.readTree(EVIDENCE.resolve(protocolName).toFile()).has("modelSelectionProfile")) nativeModelSelection();
        }

        /** 固定复用既有合成账号；请求标识不能替代真实认证，也不能授权跨身份操作。 */
        private void requireCanonical(HttpServletRequest request) throws Exception {
            if (!Set.of("127.0.0.1", "::1").contains(request.getRemoteAddr())) throw new IllegalArgumentException("Loopback required");
            requireOpenBatch();
            JsonNode active = JSON.readTree(ROOT.resolve("active-job.json").toFile());
            if (!active.path("id").asText().equals(request.getHeader("X-Evaluation-Job")))
                throw new IllegalArgumentException("Active frozen job header required");
            JsonNode account = JSON.readTree(Path.of(System.getProperty("natural.eval.accountFile")).toFile());
            var identity = actor.require();
            if (!identity.userId().toString().equals(account.path("userId").asText())
                    || !identity.tenantId().toString().equals(account.path("tenantId").asText())
                    || !identity.workspaceId().toString().equals(account.path("workspaceId").asText()))
                throw new IllegalArgumentException("Canonical fixture required");
        }
    }

    /** 启动和每次模型调用均拒绝已关闭任务或阶段，不能通过重启恢复闭批余额。 */
    static void requireOpenBatch() throws IOException {
        JsonNode task = JSON.readTree(EVIDENCE.getParent().resolve("task-protocol.json").toFile());
        JsonNode protocol = JSON.readTree(EVIDENCE.resolve(protocolName).toFile());
        if (!task.path("status").asText().equals("FROZEN") || !protocol.path("status").asText().equals("FROZEN")
                || Files.exists(TASK_ROOT.resolve("closure.json")) || Files.exists(ROOT.resolve("closure.json"))
                || Files.exists(EVIDENCE.resolve("closure.json")) || Files.exists(EVIDENCE.getParent().resolve("closure.json")))
            throw new IOException("Evaluation batch is closed or not frozen");
        if (protocol.path("taskMaximumActualCalls").asInt() != task.path("selectedMaximumActualCalls").asInt()
                || protocol.path("maximumNewActualCalls").asInt() < 0
                || protocol.path("maximumNewActualCalls").asInt() == 0 && !protocol.path("captureOnly").asBoolean())
            throw new IOException("Invalid frozen task budget");
        for (JsonNode prerequisite : protocol.path("prerequisiteStages")) {
            String stage = prerequisite.asText();
            if (!stage.matches("[a-z0-9-]+")) throw new IOException("Unsafe prerequisite stage");
            Path prerequisiteRoot = EVIDENCE.getParent().resolve(stage);
            JsonNode gate = JSON.readTree(prerequisiteRoot.resolve("regression-gate.json").toFile());
            JsonNode closure = JSON.readTree(prerequisiteRoot.resolve("closure.json").toFile());
            if (!gate.path("status").asText().equals("PASS")
                    || !protocol.path("candidateVersions").toString().contains("\"" + gate.path("candidateVersion").asText() + "\"")
                    || !gate.has("unresolvedSevereRegressions")
                    || gate.path("unresolvedSevereRegressions").asInt() != 0
                    || !closure.path("status").asText().equals("CLOSED"))
                throw new IOException("Stage locked until prerequisite regression passes");
        }
    }

    /**
     * 旧阶段仍只接受关闭思考的冻结参数；新profile只放行独立writer重放，不改变准备、Plan或下游请求。
     * 每条实际请求还要逐字对照其48首稿源快照，只有事先批准的总cap与思考参数可以不同。
     */
    static void validateModelParameters(JsonNode protocol, JsonNode job, JsonNode payload) throws IOException {
        if (!payload.path("model").asText().equals(protocol.path("upstreamModel").asText())
                || !payload.path("temperature").isNumber()
                || payload.path("temperature").asDouble() != protocol.path("parameters").path("temperature").asDouble()
                || !payload.path("stream").isBoolean() || payload.path("stream").asBoolean())
            throw new IOException("Model parameters outside frozen batch");
        if (protocol.has("modelSelectionProfile")) {
            validateOfficialModelProfile(protocol, job);
            if (protocol.path("modelSelectionProfile").path("name").asText().equals("NATIVE_PRO52_PREPARE_MECHANISM_V1"))
                validateNativePro52Request(protocol, job, payload);
            Set<String> fields = Set.of("model", "messages", "temperature", "max_tokens", "thinking", "stream", "response_format");
            for (var names = payload.fieldNames(); names.hasNext();) if (!fields.contains(names.next()))
                throw new IOException("Native model comparison does not permit parameter overrides");
            if (payload.size() != fields.size() || !payload.path("response_format").equals(JSON.valueToTree(Map.of("type", "json_object"))))
                throw new IOException("Native model comparison requires the unchanged JSON request contract");
        }
        if (protocol.has("experimentProfile")) {
            JsonNode profile = protocol.path("writerThinkingProfile");
            String arm = job.path("thinkingArm").asText();
            if (!protocol.path("experimentProfile").asText().equals("WRITER_THINKING_ABLATION_V1")
                    || !profile.path("name").asText().equals("WRITER_THINKING_ABLATION_V1")
                    || protocol.path("maximumNewActualCalls").asInt() != 6
                    || protocol.path("phaseLimits").path("EXECUTION").asInt() != 6
                    || protocol.path("phaseLimits").path("GENERATION").asInt() != 0
                    || protocol.path("phaseLimits").path("PLAN").asInt() != 0
                    || protocol.path("phaseLimits").path("REVIEW").asInt() != 0
                    || profile.path("maximumActualCalls").asInt() != 6
                    || profile.path("writerMaxTokens").asInt() != 32768
                    || profile.path("readTimeoutSeconds").asInt() != 300
                    || profile.path("runnerTimeoutMs").asInt() != 360000
                    || profile.path("protocolRepairsAllowed").asBoolean(true)
                    || profile.path("qualityRetriesAllowed").asBoolean(true)
                    || !job.path("phase").asText().equals("EXECUTION")
                    || !job.path("version").asText().equals("48")
                    || !job.path("purpose").asText().equals("WRITER_THINKING_ABLATION")
                    || !Set.of("OFF", "ON").contains(arm))
                throw new IOException("Writer thinking profile outside its approved six-call scope");
            JsonNode source = null;
            for (JsonNode candidate : profile.path("writerSources")) {
                if (candidate.path("caseId").asText().equals(job.path("caseId").asText())) source = candidate;
            }
            if (source == null || !source.path("snapshot").asText().equals("writer-sources/" + job.path("caseId").asText() + ".json"))
                throw new IOException("Frozen original writer request is missing");
            byte[] bytes = Files.readAllBytes(EVIDENCE.resolve(source.path("snapshot").asText()));
            try {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!hash.equals(source.path("sha256").asText())) throw new IOException("Frozen writer source drift");
            } catch (java.security.GeneralSecurityException failure) { throw new IOException("Hash unavailable", failure); }
            JsonNode original = JSON.readTree(bytes);
            if (!(original instanceof ObjectNode expected) || expected.path("max_tokens").asInt() != 8192
                    || !expected.path("thinking").equals(JSON.valueToTree(Map.of("type", "disabled")))
                    || expected.has("reasoning_effort")
                    || !expected.path("response_format").equals(JSON.valueToTree(Map.of("type", "json_object"))))
                throw new IOException("Source is not the original disabled JSON first-writer request");
            expected.put("max_tokens", 32768);
            if (arm.equals("ON")) {
                expected.set("thinking", JSON.valueToTree(Map.of("type", "enabled")));
                expected.put("reasoning_effort", "high");
            }
            if (!payload.equals(expected) || !payload.equals(job.path("approvedModelRequest")))
                throw new IOException("Writer thinking request changed outside the approved parameter difference");
            return;
        }
        if (Set.of("55", "56", "57", "58", "59", "60", "61", "62", "63", "64", "65", "66", "67", "68", "69", "70").contains(job.path("version").asText()) && job.path("phase").asText().equals("GENERATION")) {
            boolean bodyFirst = Set.of("59", "61", "62", "63", "64", "65", "66", "67", "68", "69", "70").contains(job.path("version").asText());
            JsonNode expected = JSON.valueToTree(Map.of("name", bodyFirst ? "BODY_FIRST_REASONING_V1" : "SINGLE_PASS_REASONING_V1", "version", job.path("version").asText(),
                    "generationMaxTokens", 32768, "thinking", "enabled", "reasoningEffort", "high",
                    "readTimeoutSeconds", 180, "runnerTimeoutMs", Set.of("62", "63", "64", "65", "66", "67", "68", "69", "70").contains(job.path("version").asText()) ? 1140000 : bodyFirst ? 780000 : 420000, "planMaxTokens", 8192, "planThinking", "disabled"));
            if (!protocol.path("naturalInferenceProfile").equals(expected)
                    || !payload.path("thinking").equals(JSON.valueToTree(Map.of("type", "enabled")))
                    || !payload.path("reasoning_effort").asText().equals("high") || payload.path("max_tokens").asInt() != 32768
                    || !payload.path("response_format").equals(JSON.valueToTree(Map.of("type", "json_object"))))
                throw new IOException("Single-pass reasoning parameters differ from the frozen55 profile");
            return;
        }
        if (!payload.path("thinking").equals(JSON.valueToTree(Map.of("type", "disabled"))) || payload.has("reasoning_effort"))
            throw new IOException("Legacy stages must retain disabled thinking without effort overrides");
        String tokenParameter = job.path("phase").asText().equals("EXECUTION") ? "executionMaxTokens"
                : job.path("phase").asText().equals("REVIEW") ? "reviewMaxTokens" : "generationMaxTokens";
        if (!payload.path("max_tokens").isIntegralNumber()
                || payload.path("max_tokens").asInt() != protocol.path("parameters").path(tokenParameter).asInt())
            throw new IOException("Token limit outside frozen batch");
    }

    /** Only the one approved native-Pro/50 comparison may select a different catalog model; legacy profiles stay unchanged. */
    static void validateOfficialModelProfile(JsonNode protocol, JsonNode job) throws IOException {
        if (protocol.path("modelSelectionProfile").path("name").asText().equals("NATIVE_PRO52_PREPARE_MECHANISM_V1")) {
            validateNativePro52Profile(protocol, job); return;
        }
        JsonNode profile = protocol.path("modelSelectionProfile");
        Set<String> keys = Set.of("name", "modelId", "upstreamModel", "routeKey", "endpoint", "baselineStage",
                "baselineJobsSha256", "baselineProtocolSha256", "maximumActualCalls");
        for (var names = profile.fieldNames(); names.hasNext();) if (!keys.contains(names.next()))
            throw new IOException("Unknown native model profile field");
        if (!profile.isObject() || profile.size() != keys.size() || protocol.has("experimentProfile")
                || !profile.path("name").asText().equals("OFFICIAL_MODEL_SELECTION_ABLATION_V1")
                || !profile.path("modelId").asText().equals("deepseek:deepseek-v4-pro")
                || !profile.path("upstreamModel").asText().equals("deepseek-v4-pro")
                || !profile.path("routeKey").asText().equals("deepseek")
                || !profile.path("endpoint").asText().equals("https://api.deepseek.com/chat/completions")
                || !protocol.path("modelId").equals(profile.path("modelId"))
                || !protocol.path("upstreamModel").equals(profile.path("upstreamModel"))
                || !protocol.path("endpoint").equals(profile.path("endpoint"))
                || !profile.path("baselineStage").asText().equals("registry50-development")
                || !profile.path("baselineJobsSha256").asText().matches("[a-f0-9]{64}")
                || !profile.path("baselineProtocolSha256").asText().matches("[a-f0-9]{64}")
                || profile.path("maximumActualCalls").asInt() != 12 || protocol.path("maximumNewActualCalls").asInt() != 12
                || protocol.path("phaseLimits").path("GENERATION").asInt() != 12
                || protocol.path("phaseLimits").path("PLAN").asInt() != 0
                || protocol.path("phaseLimits").path("EXECUTION").asInt() != 0 || protocol.path("phaseLimits").path("REVIEW").asInt() != 0
                || !job.path("phase").asText().equals("GENERATION") || !job.path("version").asText().equals("50")
                || !Set.of("Q01", "U02", "U03").contains(job.path("caseId").asText())
                || !job.path("id").asText().equals(job.path("caseId").asText() + "_V50_PRO_GEN")
                || protocol.path("parameters").path("generationMaxTokens").asInt() != 8192
                || protocol.path("parameters").path("temperature").asDouble() != 0.2
                || !protocol.path("parameters").path("thinking").asText().equals("disabled")
                || protocol.path("parameters").path("stream").asBoolean(true))
            throw new IOException("Native model-selection profile outside the approved fixed single-variable scope");
    }

    /** Only this independent two-request native model mechanism; historical inner modelId remains source metadata. */
    static void validateNativePro52Profile(JsonNode protocol, JsonNode job) throws IOException {
        JsonNode p = protocol.path("modelSelectionProfile");
        Set<String> keys = Set.of("name","modelId","upstreamModel","routeKey","endpoint","baselineStage","baselineJobsSha256","baselineProtocolSha256","maximumActualCalls");
        for (var names=p.fieldNames();names.hasNext();) if(!keys.contains(names.next())) throw new IOException("Unknown native Pro52 profile field");
        if(!p.isObject()||p.size()!=keys.size()||protocol.has("experimentProfile")
                ||!p.path("modelId").asText().equals("deepseek:deepseek-v4-pro")||!p.path("upstreamModel").asText().equals("deepseek-v4-pro")
                ||!p.path("routeKey").asText().equals("deepseek")||!p.path("endpoint").asText().equals("https://api.deepseek.com/chat/completions")
                ||!protocol.path("modelId").equals(p.path("modelId"))||!protocol.path("upstreamModel").equals(p.path("upstreamModel"))||!protocol.path("endpoint").equals(p.path("endpoint"))
                ||!p.path("baselineStage").asText().equals("registry52-plan-completion")
                ||!p.path("baselineJobsSha256").asText().matches("[a-f0-9]{64}")||!p.path("baselineProtocolSha256").asText().matches("[a-f0-9]{64}")
                ||p.path("maximumActualCalls").asInt()!=2||protocol.path("maximumNewActualCalls").asInt()!=2
                ||protocol.path("phaseLimits").path("EXECUTION").asInt()!=2||protocol.path("phaseLimits").path("GENERATION").asInt()!=0
                ||protocol.path("phaseLimits").path("PLAN").asInt()!=0||protocol.path("phaseLimits").path("REVIEW").asInt()!=0
                ||!job.path("phase").asText().equals("EXECUTION")||!job.path("version").asText().equals("52")
                ||!Set.of("P02_CONFIRMED","P02_UNKNOWN").contains(job.path("caseId").asText())
                ||!job.path("id").asText().equals(job.path("caseId").asText()+"_V52_PRO_PREPARE")
                ||protocol.path("parameters").path("executionMaxTokens").asInt()!=8192
                ||protocol.path("parameters").path("temperature").asDouble()!=.2||!protocol.path("parameters").path("thinking").asText().equals("disabled")
                ||protocol.path("parameters").path("stream").asBoolean(true)) throw new IOException("Native Pro52 profile outside finite mechanism scope");
    }

    /** Preserve the complete original52 request except its top-level model; one upstream attempt per frozen job. */
    static void validateNativePro52Request(JsonNode protocol, JsonNode job, JsonNode payload) throws IOException {
        String number=job.path("caseId").asText().equals("P02_CONFIRMED")?"001":"003";
        JsonNode original=JSON.readTree(EVIDENCE.getParent().resolve("registry52-plan-completion/traces/"+number+".json").toFile()).path("request");
        ObjectNode reverted=payload.deepCopy(); reverted.put("model","deepseek-flash");
        if(!reverted.equals(original)) throw new IOException("Native Pro52 request changed more than upstream model");
        if(Files.isDirectory(ROOT.resolve("traces"))) try(var paths=Files.list(ROOT.resolve("traces"))) {
            for(Path path:paths.filter(item->item.toString().endsWith(".json")).toList())
                if(JSON.readTree(path.toFile()).path("jobId").asText().equals(job.path("id").asText())) throw new IOException("Native Pro52 job already attempted");
        }
    }

    /** 新批次独立预留，不转移旧闭批余额；未完成预留也占预算，禁止并发超限。 */
    static synchronized Path reserve(JsonNode job, JsonNode protocol) throws IOException {
        requireOpenBatch();
        JsonNode task = JSON.readTree(EVIDENCE.getParent().resolve("task-protocol.json").toFile());
        if (protocol.path("taskMaximumActualCalls").asInt() != task.path("selectedMaximumActualCalls").asInt())
            throw new IOException("Stage maximum differs from task authorization");
        if (!protocol.path("status").asText().equals("FROZEN") || Files.exists(ROOT.resolve("closure.json"))
                || Files.exists(EVIDENCE.resolve("closure.json")) || Files.exists(EVIDENCE.getParent().resolve("closure.json")))
            throw new IOException("Evaluation batch is closed");
        try (var budgetChannel = FileChannel.open(TASK_ROOT.resolve("actual-call-budget.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var budgetLock = budgetChannel.lock()) {
        String phase = job.path("phase").asText();
        if (!job.path("id").asText().matches("[A-Z0-9_-]+") || !protocol.path("phaseLimits").has(phase)) throw new IOException("Invalid frozen job");
        List<Path> traces;
        try (var files = Files.list(ROOT.resolve("traces"))) { traces = files.filter(p -> p.toString().endsWith(".json")).toList(); }
        long count = 0;
        boolean writerAttempted = false;
        boolean writerUnresolvedReservation = false;
        for (Path file : traces) {
            try {
                JsonNode trace = JSON.readTree(file.toFile());
                if (trace == null || trace.path("phase").asText().isBlank() || trace.path("phase").asText().equals(phase)) count++;
                if (trace == null || trace.path("jobId").asText().isBlank()) writerUnresolvedReservation = true;
                else if (trace.path("jobId").asText().equals(job.path("id").asText())) writerAttempted = true;
            }
            catch (IOException failure) { count++; writerUnresolvedReservation = true; }
        }
        if (protocol.has("experimentProfile") && (writerAttempted || writerUnresolvedReservation))
            throw new IOException("Single-pass writer job already attempted or an unresolved reservation requires stopping");
        if (traces.size() >= protocol.path("maximumNewActualCalls").asInt()
                || taskActualCalls() >= protocol.path("taskMaximumActualCalls").asInt()
                || count >= protocol.path("phaseLimits").path(phase).asInt()) throw new IOException("Actual call budget exhausted");
        for (int i = 1; i <= protocol.path("maximumNewActualCalls").asInt(); i++) {
            Path target = ROOT.resolve("traces").resolve(String.format("%03d.json", i));
            try { return Files.createFile(target); } catch (FileAlreadyExistsException ignored) { }
        }
        throw new IOException("Actual call budget exhausted");
        }
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

    /** 本任务全部阶段的网络预留累计，旧批次余额不转用。 */
    static long taskActualCalls() throws IOException {
        long count = 0;
        try (var stages = Files.list(TASK_ROOT)) {
            for (Path stage : stages.filter(Files::isDirectory).toList()) {
                Path directory = stage.resolve("traces");
                if (!Files.isDirectory(directory)) continue;
                try (var traces = Files.list(directory)) {
                    count += traces.filter(p -> p.toString().endsWith(".json")).count();
                }
            }
        }
        // 本轮用户独立授权500次，从0计数；旧182/200闭批仅作历史，不转入或恢复余额。
        JsonNode task = JSON.readTree(EVIDENCE.getParent().resolve("task-protocol.json").toFile());
        if (task.path("selectedMaximumActualCalls").asInt() != 500 || task.path("previousActualCalls").asInt() != 0)
            throw new IOException("Current task authorization changed");
        return count;
    }

    /** 版本只接受本阶段已冻结清单，不读取请求正文来扩大实验范围。 */
    static boolean containsVersion(String version) throws IOException {
        for (JsonNode item : JSON.readTree(EVIDENCE.resolve(protocolName).toFile()).path("versions")) {
            if (item.asText().equals(version)) return true;
        }
        return false;
    }

    /** 只从已冻结的合成题目中查找输入，不能由客户端追加材料或新题。 */
    static JsonNode sample(String id) throws IOException {
        for (JsonNode sample : JSON.readTree(EVIDENCE.resolve("cases.json").toFile()).path("cases")) {
            if (sample.path("id").asText().equals(id)) return sample;
        }
        throw new IOException("Case outside frozen batch");
    }
}
