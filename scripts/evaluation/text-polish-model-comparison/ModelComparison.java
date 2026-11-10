package com.promptoptimizer.textpolish.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.PromptOptimizerApplication;
import com.promptoptimizer.common.logging.*;
import com.promptoptimizer.identity.mapper.UserAccountMapper;
import com.promptoptimizer.provider.infrastructure.openai.OpenAiCompatibleProperties;
import com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry;
import com.promptoptimizer.textpolish.domain.TextPolishModels.Input;
import org.springframework.boot.SpringApplication;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 在相同单次生成策略下比较模型，复用正式Provider并记录真实尝试。 */
public final class ModelComparison {
    /** 公开可核对参数，排除模型密钥与端点。 */
    private static Map<String, Object> describe(ModelEntry model) {
        return Map.of("modelId", model.publicId(), "displayName", model.displayName(),
                "routeKey", model.routeKey(), "upstreamModel", model.upstreamModel());
    }

    /** 原子替换账本，预扣记录落盘后才允许调用模型。 */
    private static void save(ObjectMapper json, Path target, Object value) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + ".writing");
        json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 核对冻结源码和样本，漂移时阻止新增调用。 */
    private static void verify(JsonNode protocol) throws Exception {
        var fields = protocol.path("sourceSha256").fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(entry.getKey()))));
            if (!actual.equals(entry.getValue().asText())) throw new IllegalStateException("Frozen source drift");
        }
    }

    /** inspect仅读取模型目录，pilot和expand只执行冻结阶段。 */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        String phase = args[1];
        ObjectMapper loader = new ObjectMapper();
        JsonNode protocol = null;
        if (!phase.equals("inspect")) {
            protocol = loader.readTree(root.resolve("protocol.json").toFile());
            verify(protocol);
            if (phase.equals("expand") && !loader.readTree(root.resolve("pilot-review.json").toFile()).path("pass").asBoolean())
                throw new IllegalStateException("Pilot acceptance required");
            if (loader.readTree(root.resolve("ledger.json").toFile()).path("closed").asBoolean())
                throw new IllegalStateException("Closed batch");
        }
        var application = new SpringApplication(PromptOptimizerApplication.class);
        try (var context = application.run("--server.port=0", "--server.address=127.0.0.1", "--spring.flyway.enabled=false")) {
            var json = context.getBean(ObjectMapper.class);
            var account = json.readTree(Path.of("tmp/local-acceptance/account.json").toFile());
            if (!account.path("kind").asText().equals("LOCAL_SYNTHETIC_FIXTURE")) throw new IllegalStateException("Canonical account required");
            UUID userId = UUID.fromString(account.path("userId").asText());
            UUID tenantId = UUID.fromString(account.path("tenantId").asText());
            var users = context.getBean(UserAccountMapper.class);
            var user = users.selectById(userId);
            if (user == null || !user.getTenantId().equals(tenantId)
                    || !users.selectDefaultWorkspaceId(userId, tenantId).toString().equals(account.path("workspaceId").asText()))
                throw new IllegalStateException("Canonical scope mismatch");
            var models = context.getBean(TextPolishModelService.class);
            var properties = context.getBean(OpenAiCompatibleProperties.class);
            List<Map<String, Object>> available = new ArrayList<>();
            for (var item : models.models()) available.add(describe(models.require(item.id())));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("defaultModel", describe(models.require(null)));
            metadata.put("availableModels", available);
            metadata.put("temperature", properties.getTemperature());
            metadata.put("maxTokens", Math.min(8000, properties.getMaxTokens()));
            metadata.put("jsonResponseFormat", properties.isJsonResponseFormatEnabled());
            metadata.put("canonicalScopeVerified", true);
            if (phase.equals("inspect")) { save(json, root.resolve("metadata.json"), metadata); return; }
            var frozen = json.readTree(root.resolve("metadata.json").toFile());
            if (!frozen.equals(json.valueToTree(metadata))) throw new IllegalStateException("Model configuration drift");
            var provider = context.getBean(OpenAiTextPolishProvider.class);
            provider.configureStrategy("text-polish-v3");
            var ledger = (ObjectNode) json.readTree(root.resolve("ledger.json").toFile());
            if (ledger.has("activeJob")) throw new IllegalStateException("Unsettled reservation");
            for (var job : protocol.path("jobs")) {
                if (!job.path("phase").asText().equals(phase)) continue;
                String id = job.path("id").asText();
                Path target = root.resolve(id + ".json");
                if (Files.exists(target)) continue;
                int used = ledger.path("actualCalls").asInt();
                if (used + 2 > protocol.path("maxActualCalls").asInt()) throw new IllegalStateException("Task budget exhausted");
                int phaseUsed = 0;
                for (var entry : ledger.path("entries")) if (entry.path("phase").asText().equals(phase)) phaseUsed += entry.path("calls").asInt();
                if (phaseUsed + 2 > protocol.path("phaseCaps").path(phase).asInt()) throw new IllegalStateException("Phase budget exhausted");
                Input input = json.treeToValue(job.path("input"), Input.class);
                new TextPolishFidelity().validateInput(input);
                ModelEntry selected = models.require(input.modelId());
                ledger.put("reserved", 2).put("activeJob", id);
                save(json, root.resolve("ledger.json"), ledger);
                List<ModelCallObservation> observations = new ArrayList<>();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("job", job); result.put("model", describe(selected));
                long started = System.nanoTime();
                try (var scope = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(tenantId, (tenant, call) -> observations.add(call)))) {
                    result.put("polishedText", provider.polish(input, selected));
                    result.put("status", "COMPLETED");
                } catch (RuntimeException failure) {
                    result.put("failureClass", failure.getClass().getSimpleName());
                    result.put("status", "FAILED");
                }
                result.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
                result.put("observations", observations);
                save(json, target, result);
                int charged = observations.isEmpty() ? 2 : observations.size();
                ledger.put("actualCalls", used + charged).put("reserved", 0);
                ledger.remove("activeJob");
                ledger.withArray("entries").addObject().put("id", id).put("phase", phase).put("calls", charged).put("status", String.valueOf(result.get("status")));
                save(json, root.resolve("ledger.json"), ledger);
                System.out.println("MODEL_COMPARISON " + id + " " + result.get("status") + " total=" + (used + charged));
                if (observations.isEmpty()) throw new IllegalStateException("Audit evidence missing, maximum reservation charged");
            }
        }
    }
}
