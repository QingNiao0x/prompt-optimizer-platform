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

/**
 * 仅记录冻结合成任务；批准、路径白名单、阶段预算与上游端点保护继续生效。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class Candidate42r3EvaluationServer {
    static final Path ROOT = Path.of("tmp/me00a-20261012").toAbsolutePath();
    static final ObjectMapper JSON = new ObjectMapper();
    static String version = "candidate42r3-source-ownership";
    public static void main(String[] args) throws Exception {
        version="candidate42r3-source-ownership";
        frozenCases(approvedBatch(JSON.readTree(ROOT.resolve("protocol.json").toFile())));
        new SpringApplication(PromptOptimizerApplication.class, TraceConfiguration.class).run(
            "--app.provider.openai-compatible.max-tokens=8192", "--server.address=127.0.0.1", "--server.port=9001", "--spring.flyway.enabled=false",
            "--spring.session.redis.namespace=prompt-optimizer:candidate42", "--spring.main.banner-mode=off");
    }
    static JsonNode approvedBatch(JsonNode protocol) throws IOException {
        JsonNode batch = protocol.path("activeBatch"), caps = batch.path("purposeMaximums");
        if (!"candidate42r3-source-ownership".equals(batch.path("version").asText())
                || !"candidate42r3".equals(batch.path("sourceCandidate").asText())
                || !"APPROVED_BY_USER".equals(batch.path("authorizationStatus").asText())
                || !"OPEN".equals(batch.path("status").asText()) || batch.path("qualityRetriesAllowed").asBoolean(true)
                || batch.path("callsBefore").asInt() != 461 || batch.path("candidateCallsBefore").asInt() != 391
                || batch.path("maximumNewCalls").asInt() != 6 || protocol.path("maximumActualModelCalls").asInt() != 500
                || protocol.path("phaseLimits").path("baseline").asInt() != 80
                || protocol.path("phaseLimits").path("candidateValidation").asInt() != 420
                || caps.path("PLANNING").asInt() != 0 || caps.path("PLATFORM_GENERATION").asInt() != 4
                || caps.path("EXECUTION").asInt() != 2 || caps.path("REVIEW").asInt() != 0)
            throw new IOException("Frozen authorization or allocation mismatch");
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

    static final Path LIVE = Path.of("tmp/me02-candidate42r3-20261019").toAbsolutePath();

    /** 只读取固定路径上的实际服务端问题与原生提交，不能由工具另补答案。 */
    static JsonNode actualConfirmation(String caseId) throws IOException {
        // 只有本批三题可读取各自的原生提交；禁止路径注入和跨题借用确认。
        if (!java.util.Set.of("LOG-SUMMARY", "RESERVED-REFUND").contains(caseId))
            throw new IOException("Unknown case");
        JsonNode plan = JSON.readTree(LIVE.resolve("flow/" + caseId + "/plan.private.json").toFile());
        JsonNode submitted = JSON.readTree(LIVE.resolve("flow/" + caseId + "/actual-confirmation.private.json").toFile());
        JsonNode reference = plan.path("body").path("data").path("planningContext");
        JsonNode submittedReference = submitted.path("planningContext");
        boolean sameReference = reference.isMissingNode() || reference.isNull()
                ? submittedReference.isMissingNode() || submittedReference.isNull() : reference.equals(submittedReference);
        if (plan.path("status").asInt() != 200 || !plan.path("body").path("data").path("planId").equals(submitted.path("planId"))
                || !sameReference)
            throw new IOException("Real bound Plan required");
        JsonNode questions = plan.path("body").path("data").path("questions");
        if (submitted.path("answers").size() != questions.size()) throw new IOException("Incomplete bound answers");
        for (int i = 0; i < questions.size(); i++) {
            JsonNode q = questions.get(i), answer = submitted.path("answers").get(i);
            if (!q.path("id").equals(answer.path("questionId")) || !q.path("question").equals(answer.path("question"))
                    || answer.path("answer").asText().isBlank() || answer.path("answer").asText().length() > 1500)
                throw new IOException("Question/answer binding drift");
        }
        return submitted;
    }

    /** 空资料、无对话、固定模式和实际原生确认全部相同才允许本批对应增强。 */
    static boolean requestAllowed(com.promptoptimizer.enhancement.dto.OptimizationRequest request, JsonNode cases) throws IOException {
        if (!request.context().files().isEmpty() || !request.context().customDescription().isBlank()
                || !request.conversationHistory().isEmpty() || !request.permissionPolicy().protectedPaths().isEmpty()
                || !request.permissionPolicy().requireConfirmationFor().isEmpty()) return false;
        for (JsonNode sample : cases) {
            if (!request.rawPrompt().equals(sample.path("rawPrompt").asText())) continue;
            boolean format = false;
            for (JsonNode allowed : sample.path("promptFormats"))
                format |= request.enhancement().promptFormat() != null && allowed.asText().equals(request.enhancement().promptFormat().name());
            if (!format || Boolean.TRUE.equals(request.enhancement().includeExamples()) != sample.path("includeExamples").asBoolean()) return false;
            if (!sample.path("planConfirmed").asBoolean()) return request.planConfirmation() == null;
            return request.planConfirmation() != null && request.planConfirmation().equals(JSON.convertValue(
                    actualConfirmation(sample.path("id").asText()), com.promptoptimizer.enhancement.dto.PlanConfirmation.class));
        }
        return false;
    }

    /** 离线夹具与真实输入共用同一校验；实际运行的答案只能来自已绑定提交。 */
    static boolean scopedInputAllowed(JsonNode input, JsonNode cases, java.util.List<com.promptoptimizer.enhancement.dto.PlanAnswer> answers) {
        for (JsonNode msg : input.path("messages")) {
            if (!"user".equals(msg.path("role").asText())) continue;
            String content = msg.path("content").asText(); int begin = content.indexOf('{'); if (begin < 0) continue;
            try {
                JsonNode payload = JSON.readTree(content.substring(begin));
                for (JsonNode sample : cases) {
                    if (!sample.path("rawPrompt").asText().equals(payload.path("rawPrompt").asText())
                            || !payload.path("conversationHistory").isEmpty()) continue;
                    if (payload.has("template")) {
                        String requestedTemplate = payload.path("options").path("templateCode").asText();
                        String inferred = new com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl()
                                .infer(sample.path("rawPrompt").asText()).name();
                        if (!requestedTemplate.equals("AUTO") && !requestedTemplate.equals(inferred)) return false;
                        if (!sample.path("enhancementAllowed").asBoolean() || payload.path("planConfirmed").asBoolean() != sample.path("planConfirmed").asBoolean()
                                || !containsFormat(sample, payload.path("options").path("promptFormat").asText())
                                || payload.path("options").path("includeExamples").asBoolean() != sample.path("includeExamples").asBoolean()
                                || payload.has("planAnswers") || payload.has("confirmedDecisions")) return false;
                        // 当前协议只通过taskView发送题答语义；完整视图必须由实际提交重新构建且逐项相等。
                        var view = com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory.create(
                                sample.path("rawPrompt").asText(), com.promptoptimizer.enhancement.domain.TemplateCode.valueOf(requestedTemplate),
                                answers, java.util.List.of(), sample.path("includeExamples").asBoolean());
                        var mode = com.promptoptimizer.provider.domain.PromptRewriteStrategy.forTask(view,
                                com.promptoptimizer.enhancement.domain.PromptFormat.valueOf(payload.path("options").path("promptFormat").asText())).mode();
                        JsonNode expectedReferences = expectedReferences(view);
                        boolean referencesMatch = payload.path("requirementReferences").equals(expectedReferences)
                                || expectedReferences.isEmpty() && !payload.has("requirementReferences");
                        return referencesMatch
                                && payload.path("taskView").equals(JSON.valueToTree(view.modelInput()))
                                && payload.path("rewriteStrategy").path("mode").asText().equals(mode);
                    }
                    var plan = com.promptoptimizer.enhancement.service.impl.EffectiveTaskViewFactory.create(
                            sample.path("rawPrompt").asText(), com.promptoptimizer.enhancement.domain.TemplateCode.AUTO,
                            java.util.List.of(), java.util.List.of(), false);
                    return payload.has("questionScope") && payload.path("knownDecisions").isEmpty()
                            && payload.path("contextDescription").asText("").isBlank()
                            && payload.path("planningContext").path("factCards").isEmpty()
                            && payload.path("taskView").equals(JSON.valueToTree(plan.modelInput()))
                            && payload.path("taskIntent").equals(JSON.valueToTree(plan.intent()));
                }
            } catch (IOException ignored) { }
        }
        return false;
    }

    /** 协议修复仍只能重发同一原题和实际题答，不授权新任务或冻结范围外的资料。 */
    static boolean inputAllowed(JsonNode input, JsonNode cases, JsonNode batch) throws IOException {
        String id = inputCaseId(input, cases); JsonNode sample = null;
        for (JsonNode row : cases) if (id.equals(row.path("id").asText())) sample = row;
        if (sample == null) return false;
        var answers = "PLATFORM_GENERATION".equals(inputPurpose(input)) && sample.path("planConfirmed").asBoolean()
                ? JSON.convertValue(actualConfirmation(id).path("answers"),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<com.promptoptimizer.enhancement.dto.PlanAnswer>>() {})
                : java.util.List.<com.promptoptimizer.enhancement.dto.PlanAnswer>of();
        if ("PLANNING".equals(inputPurpose(input)) && !sample.path("planConfirmed").asBoolean()) return false;
        return scopedInputAllowed(input, cases, answers);
    }
    static boolean containsFormat(JsonNode sample, String selected) {
        for (JsonNode value : sample.path("promptFormats")) if (value.asText().equals(selected)) return true;
        return false;
    }
    /** New request references are reconstructed from the exact bound view and current task rules. */
    static JsonNode expectedReferences(com.promptoptimizer.enhancement.domain.EffectiveTaskView view) {
        var context = new com.promptoptimizer.context.domain.ContextSnapshot("", java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), "EMPTY");
        var rules = new com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl().completeForTask(context,
                com.promptoptimizer.enhancement.dto.PermissionPolicyInput.empty(), true, view.intent());
        var template = new com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl()
                .resolve(view.intent().templateCode(), view.originalPrompt());
        var input = new com.promptoptimizer.provider.domain.EnhancementProviderRequest(view.originalPrompt(), context,
                template, java.util.List.of(), java.util.List.of(), true, rules.visibleConstraints(), java.util.List.of(),
                com.promptoptimizer.enhancement.dto.EnhancementOptions.defaults(), null, java.util.List.of(), view.confirmedDecisions(), view);
        return JSON.valueToTree(com.promptoptimizer.enhancement.service.impl.BoundRequirementProjection.forProvider(input));
    }

    /** 从冻结原题定位提交目录，不能用其他题的回答填充本题。 */
    static String inputCaseId(JsonNode input, JsonNode cases) throws IOException {
        for (JsonNode message : input.path("messages")) {
            if (!"user".equals(message.path("role").asText())) continue;
            String text = message.path("content").asText();
            int start = text.indexOf('{');
            if (start < 0) continue;
            JsonNode payload = JSON.readTree(text.substring(start));
            for (JsonNode sample : cases)
                if (sample.path("rawPrompt").equals(payload.path("rawPrompt"))) return sample.path("id").asText();
        }
        throw new IOException("No frozen task in input");
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
                    if (name.equals("defaultEnhancementOrchestrator")) {
                        var delegate = (com.promptoptimizer.enhancement.service.EnhancementOrchestrator) bean;
                        return (com.promptoptimizer.enhancement.service.EnhancementOrchestrator) request -> {
                            try {
                                var batch = approvedBatch(JSON.readTree(ROOT.resolve("protocol.json").toFile()));
                                if (!requestAllowed(request, frozenCases(batch)))
                                    throw new com.promptoptimizer.common.exception.InvalidOptimizationRequestException("请求不属于冻结的任务、示例与展示开关范围。");
                            } catch (IOException error) {
                                throw new com.promptoptimizer.common.exception.InvalidOptimizationRequestException("冻结评测范围未获授权或文件校验失败。");
                            }
                            return delegate.optimize(request);
                        };
                    }
                    if (!name.equals("openAiCompatibleRestClient")) return bean;
                    return ((RestClient) bean).mutate().requestInterceptor((request, body, execution) -> {
                        if (!"https".equals(request.getURI().getScheme()) || !"api.deepseek.com".equals(request.getURI().getHost())
                                || !"/chat/completions".equals(request.getURI().getPath()) || request.getURI().getQuery() != null
                                || request.getURI().getUserInfo() != null || request.getURI().getFragment() != null
                                || (request.getURI().getPort() != -1 && request.getURI().getPort() != 443))
                            throw new IOException("Only authorized official DeepSeek endpoint allowed");
                        JsonNode input = JSON.readTree(body);
                        JsonNode batch = approvedBatch(JSON.readTree(ROOT.resolve("protocol.json").toFile()));
                        // 拒绝前保留已绑定合成增强的序列化原件；不记录请求头或凭据，便于离线核对工具误判。
                        if ("PLATFORM_GENERATION".equals(inputPurpose(input))) {
                            Path capture = LIVE.resolve("serialization-captures"); Files.createDirectories(capture);
                            Files.writeString(capture.resolve(UUID.randomUUID() + ".json"),
                                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(input));
                        }
                        if (!input.path("model").asText().equals(batch.path("model").asText())
                                || !inputAllowed(input, frozenCases(batch), batch))
                            throw new IOException("Input or model outside frozen approved scope");
                        if (input.path("temperature").asDouble() != 0.2 || input.path("max_tokens").asInt() != 8192
                                || !"disabled".equals(input.path("thinking").path("type").asText()))
                            throw new IOException("Generation parameters differ from frozen source");
                        String purpose = inputPurpose(input);
                        Path trace = reserve(purpose);
                        var record = JSON.createObjectNode();
                        record.put("purpose", purpose); record.put("caseId", inputCaseId(input, frozenCases(batch)));
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
                } catch (IOException unresolved) {
                    // 未结算预占没有可确认的用途，不再分配新请求，防止失败成为分项免费余额。
                    throw new IOException("Unresolved evaluation trace; stop before another reservation", unresolved);
                }
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
                catch (IOException e) { return true; }
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
