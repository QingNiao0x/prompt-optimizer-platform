package com.promptoptimizer.textpolish.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.PromptOptimizerApplication;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallLogger;
import com.promptoptimizer.common.logging.ModelCallObservation;
import com.promptoptimizer.identity.mapper.UserAccountMapper;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.textpolish.domain.TextPolishModels.Input;
import com.promptoptimizer.textpolish.service.TextPolishSemanticReview.Outcome;
import com.promptoptimizer.textpolish.service.TextPolishSemanticReview.Request;
import org.springframework.boot.SpringApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 复用文章核验组件及平台路由，对固定合成稿做一次一题的独立模型核验。 */
public final class SemanticCalibration {
    private static final String MODEL = "deepseek:deepseek-flash";

    /** 先冻结十组现有夹具；核验请求和人工标定分开，后者只供评分。 */
    public static void main(String[] args) throws Exception {
        var root = Path.of(args[1]);
        var json = new ObjectMapper().findAndRegisterModules();
        var review = new TextPolishSemanticReview(new TextPolishFidelity(), json);
        if (args[0].equals("freeze") || args[0].equals("freeze-drafts")) {
            Files.createDirectories(root);
            if (Files.exists(root.resolve("cases.json")) || Files.exists(root.resolve("started")))
                throw new IllegalStateException("Calibration batch already exists");
            var cases = args[0].equals("freeze") ? calibration(review) : drafts(json, review, Path.of(args[2]));
            var protocol = root.resolve("cases.json");
            save(json, protocol, new Batch(hash(TextPolishSemanticReview.INSTRUCTIONS), List.copyOf(cases)));
            Files.writeString(root.resolve("cases.sha256"), hash(Files.readString(protocol)) + "\n", StandardOpenOption.CREATE_NEW);
            return;
        }
        if (!args[0].equals("review")) throw new IllegalArgumentException("Use freeze, freeze-drafts or review");
        var raw = Files.readString(root.resolve("cases.json"));
        if (!hash(raw).equals(Files.readString(root.resolve("cases.sha256")).strip()))
            throw new IllegalStateException("Calibration cases drift");
        var batch = json.readValue(raw, Batch.class);
        int count = batch.cases().size();
        if ((count != 8 && count != 10) || !hash(TextPolishSemanticReview.INSTRUCTIONS).equals(batch.instructionHash()))
            throw new IllegalStateException("Calibration protocol drift");
        var authorization = json.readTree(root.resolve("authorization.json").toFile());
        if (!"APPROVED".equals(authorization.path("status").asText()) || authorization.path("maxCalls").asInt() != count)
            throw new IllegalStateException("Explicit batch authorization required");
        if (Files.exists(root.resolve("started"))) throw new IllegalStateException("Calibration batch already started");
        try (var context = new SpringApplication(PromptOptimizerApplication.class).run("--server.port=0",
                "--server.address=127.0.0.1", "--spring.flyway.enabled=false",
                "--app.security.bootstrap-admin.enabled=false", "--app.security.bootstrap-user.password=",
                "--app.provider.concurrency.store-mode=MEMORY")) {
            var account = json.readTree(Path.of("tmp/local-acceptance/account.json").toFile());
            UUID tenant = UUID.fromString(account.path("tenantId").asText());
            UUID userId = UUID.fromString(account.path("userId").asText());
            var users = context.getBean(UserAccountMapper.class);
            var user = users.selectById(userId);
            if (!"LOCAL_SYNTHETIC_FIXTURE".equals(account.path("kind").asText()) || user == null
                    || !tenant.equals(user.getTenantId())
                    || !UUID.fromString(account.path("workspaceId").asText()).equals(users.selectDefaultWorkspaceId(userId, tenant)))
                throw new IllegalStateException("Canonical fixture scope mismatch");
            var model = context.getBean(TextPolishModelService.class).require(MODEL);
            var properties = context.getBean(OpenAiCompatibleProperties.class);
            var route = properties.getConfiguredRoutes().stream().filter(r -> r.key().equals(model.routeKey())).findFirst().orElseThrow();
            if (!"deepseek".equals(model.routeKey()) || !"deepseek-flash".equals(model.upstreamModel())
                    || !properties.isJsonResponseFormatEnabled() || properties.getTemperature() != 0.2 || properties.getMaxTokens() < 8000)
                throw new IllegalStateException("Frozen model or parameters differ");
            var client = context.getBean("openAiCompatibleRestClient", RestClient.class);
            Files.writeString(root.resolve("started"), count + "\n", StandardOpenOption.CREATE_NEW);
            var selected = new ArrayList<Map<String, Object>>();
            for (var item : batch.cases()) {
                // 每份预扣落盘后才发起单次供应商调用；运行中断不得无记录地重放。
                Files.writeString(root.resolve("attempts.log"), item.id() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                var observations = new ArrayList<ModelCallObservation>();
                var result = new LinkedHashMap<String, Object>();
                result.put("id", item.id());
                long started = System.nanoTime();
                try (var scope = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(tenant, (id, call) -> observations.add(call)));
                     var attempt = ModelCallLogger.beginAttempt()) {
                    var msg = List.of(Map.of("role", "system", "content", TextPolishSemanticReview.INSTRUCTIONS),
                            Map.of("role", "user", "content", json.writeValueAsString(item.request())));
                    var body = Map.of("model", model.upstreamModel(), "messages", msg, "stream", false,
                            "temperature", 0.2, "max_tokens", 8000, "response_format", Map.of("type", "json_object"),
                            "thinking", Map.of("type", "disabled"));
                    String payload = client.post().uri(route.endpoint()).header(HttpHeaders.AUTHORIZATION, "Bearer " + route.apiKey())
                            .contentType(MediaType.APPLICATION_JSON).body(body).exchange((request, response) -> {
                                var bytes = response.getBody().readNBytes(256 * 1024 + 1);
                                if (response.getStatusCode().isError() || bytes.length > 256 * 1024) throw new IOException("Invalid review response");
                                return new String(bytes, StandardCharsets.UTF_8);
                            });
                    var envelope = json.readTree(payload);
                    var choice = envelope.path("choices").path(0);
                    var content = "stop".equals(choice.path("finish_reason").asText()) && !choice.path("message").hasNonNull("refusal")
                            ? choice.path("message").path("content").textValue() : null;
                    if (content != null) new TextPolishFidelity().safe(content);
                    result.put("reviewResponse", content);
                    result.put("review", review.assess(item.request(), content));
                    var usage = envelope.path("usage");
                    ModelCallLogger.completed("text.polish", route.key(), model.publicId(), "PLATFORM_CATALOG",
                            false, 1, 1, elapsed(started), new ModelCallLogger.TokenUsage(number(usage, "prompt_tokens"),
                                    number(usage, "completion_tokens"), number(usage, "total_tokens")));
                } catch (Exception failure) {
                    result.put("failureClass", failure.getClass().getSimpleName());
                }
                result.put("elapsedMs", elapsed(started));
                result.put("observations", observations);
                save(json, root.resolve(item.id() + ".json"), result);
                selected.add(result);
            }
            save(json, root.resolve("ledger.json"), Map.of("actualCalls", selected.size(), "closed", true,
                    "routeKey", model.routeKey(), "model", model.upstreamModel(), "thinking", "disabled", "temperature", 0.2,
                    "maxTokens", 8000, "stream", false, "responseFormat", "json_object"));
        }
    }

    /** 提取既有离线校准夹具，发送时只投影 request。 */
    private static List<CalibrationCase> calibration(TextPolishSemanticReview review) {
        var cases = new ArrayList<CalibrationCase>();
        try (var arguments = TextPolishSemanticReviewTest.calibrationCases()) {
            arguments.forEach(row -> {
                Object[] values = row.get();
                cases.add(new CalibrationCase("case-%02d".formatted(cases.size() + 1), (String) values[0],
                        review.prepare(new Input((String) values[1], null, null, "", "", List.of(), MODEL),
                                (String) values[2]), (Outcome) values[4], values[3]));
            });
        }
        if (cases.size() != 10) throw new IllegalStateException("Calibration case count changed");
        return List.copyOf(cases);
    }

    /** 从已完成候选归档读取原始成稿，冻结同一成稿的影子核验输入。 */
    private static List<CalibrationCase> drafts(ObjectMapper json, TextPolishSemanticReview review, Path evidence) throws Exception {
        var corpus = json.readTree(evidence.resolve("samples.json").toFile());
        var samples = corpus.path("samples");
        if (!samples.isArray() || samples.size() != 8) throw new IllegalStateException("Draft case count changed");
        var cases = new ArrayList<CalibrationCase>();
        for (var sample : samples) {
            String id = sample.path("id").asText();
            if (!id.matches("[a-z][a-z-]{0,40}")) throw new IllegalStateException("Invalid draft case id");
            var output = json.readTree(evidence.resolve(id + "-candidate.json").toFile());
            if (!"COMPLETED".equals(output.path("status").asText()) || !sample.path("input").equals(output.path("job").path("input"))
                    || !corpus.path("comparison").path("candidate").equals(output.path("executionMetadata").path("strategyVersion")))
                throw new IllegalStateException("Frozen draft differs from candidate result");
            cases.add(new CalibrationCase("case-%02d".formatted(cases.size() + 1), id,
                    review.prepare(json.treeToValue(sample.path("input"), Input.class), output.path("polishedText").asText()),
                    null, null));
        }
        return List.copyOf(cases);
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }

    private static Long number(com.fasterxml.jackson.databind.JsonNode usage, String key) {
        return usage.path(key).isIntegralNumber() ? usage.path(key).longValue() : null;
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** 结果按步骤原子落盘，避免将中断留下的半份 JSON 当成已完成。 */
    private static void save(ObjectMapper json, Path target, Object value) throws IOException {
        var tmp = target.resolveSibling(target.getFileName() + ".writing");
        json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
    }

    record CalibrationCase(String id, String title, Request request, Outcome expected, Object answerCard) { }
    record Batch(String instructionHash, List<CalibrationCase> cases) { }
}
