package com.promptoptimizer.provider.infrastructure.concurrency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 显式选择已归档的合成 smoke 后，离线重放内容与接口断言；不启动 API、不调用付费模型、不改原报告。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@EnabledIfSystemProperty(named = "realModelConcurrency.archive", matches = ".+")
class RealModelConcurrencyArchiveReplayTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void replaysSyntheticSmokeWithoutChangingTheOriginalEvidence() throws Exception {
        Path selected = Path.of(System.getProperty("realModelConcurrency.archive"));
        assertThat(selected.isAbsolute()).as("归档目录必须显式使用绝对路径").isTrue();
        Path archive = selected.toRealPath();
        Path reportPath = directChild(archive, "report.json");
        byte[] originalReport = Files.readAllBytes(reportPath);
        JsonNode report = json.readTree(originalReport);
        assertThat(report.path("stage").asText()).as("本回放只复核原 16 请求 smoke，不代替容量重跑").isEqualTo("smoke");
        assertThat(report.path("syntheticExchangeBodiesArchived").asBoolean()).isTrue();
        JsonNode fixtures = json.readTree(Files.readString(directChild(archive, "synthetic-fixtures.json")));
        assertThat(fixtures.path("syntheticOnly").asBoolean()).isTrue();
        Path exchanges = archive.resolve("synthetic-exchanges").toRealPath();
        assertThat(exchanges.getParent()).isEqualTo(archive);

        List<Map<String, Object>> results = new ArrayList<>();
        boolean originalOperationalChecksPass = true;
        int planCount = 0;
        for (JsonNode stage : report.path("stages")) {
            String operation = stage.path("operation").asText();
            if (!Set.of("DIRECT", "PLAN", "CONFIRMED").contains(operation)) {
                originalOperationalChecksPass &= stage.path("passed").asBoolean();
                continue;
            }
            // 内容断言是本次重放对象；原时延、人数、历史落库与在途限制仍沿用原始测量，不能重写成通过。
            originalOperationalChecksPass &= stage.path("withinBrowserDeadline").asBoolean()
                    && stage.path("historyAccountsCorrect").asInt(-1) == stage.path("requestedConcurrency").asInt(-2)
                    && stage.path("upstreamPeak").asInt(Integer.MAX_VALUE) <= report.path("globalLimit").asInt();
            for (JsonNode request : stage.path("requests")) {
                Path exchangePath = exchangePath(archive, exchanges, request.path("syntheticExchange").asText());
                JsonNode exchange = json.readTree(Files.readString(exchangePath));
                assertThat(exchange.path("syntheticOnly").asBoolean()).isTrue();
                assertThat(exchange.path("operation").asText()).isEqualTo(operation);
                JsonNode response = exchange.path("responseBody");
                JsonNode data = response.path("data");
                boolean valid = exchange.path("status").asInt() == 200
                        && data.path("provider").has("mock") && !data.path("provider").path("mock").asBoolean();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("stage", stage.path("name").asText());
                result.put("operation", operation);
                result.put("sample", request.path("sample").asInt());
                result.put("requestId", response.path("requestId").asText());
                result.put("syntheticExchange", request.path("syntheticExchange").asText());
                result.put("originalValid", request.path("valid").asBoolean());
                if (operation.equals("PLAN")) {
                    planCount++;
                    valid &= !data.path("planId").asText().isBlank() && data.path("questions").isArray()
                            && data.path("questions").size() <= 8
                            && exchange.path("requestBody").path("planningContext").equals(data.path("planningContext"));
                } else {
                    String kind = exchange.path("contextKind").asText();
                    assertThat(Set.of("indexed-project", "uploaded-docx", "uploaded-markdown")).contains(kind);
                    String marker = fixtures.path(kind.equals("indexed-project") ? "project" : "document").path("marker").asText();
                    assertThat(Set.of("RESERVATION_WINDOW_8D", "WORKSHOP_CHECKIN_20M")).contains(marker);
                    String output = data.path("optimizedPrompt").asText();
                    var rule = RealModelConcurrencyAcceptanceTest.ruleEvidence(output, marker);
                    JsonNode snippets = data.path("contextReport").path("fileSnippets");
                    valid &= !output.isBlank() && hasRequiredSections(data.path("sections"))
                            && snippets.isArray() && !snippets.isEmpty() && snippets.toString().contains(marker)
                            && rule.presentationMarkerPresent() && rule.linkedBusinessRulePresent();
                    result.put("outputMarkerPresent", rule.literalMarkerPresent());
                    result.put("outputPresentationMarkerPresent", rule.presentationMarkerPresent());
                    result.put("outputLinkedBusinessRulePresent", rule.linkedBusinessRulePresent());
                    result.put("outputRuleConflict", rule.conflictingRulePresent());
                }
                result.put("valid", valid);
                results.add(result);
            }
        }
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("type", "OFFLINE_SYNTHETIC_SMOKE_ASSERTION_REPLAY");
        replay.put("replayedAt", Instant.now().toString());
        replay.put("runId", report.path("runId").asText());
        replay.put("originalReportSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(originalReport)));
        replay.put("originalReportPassed", report.path("passed").asBoolean());
        replay.put("originalOperationalChecksPass", originalOperationalChecksPass);
        replay.put("requests", results);
        replay.put("planRequests", planCount);
        replay.put("enhancementRequests", results.size() - planCount);
        boolean passed = originalOperationalChecksPass && planCount == 4 && results.size() == 16
                && results.stream().allMatch(result -> Boolean.TRUE.equals(result.get("valid")));
        replay.put("passed", passed);
        Path replayPath = archive.resolve("assertion-replay.json");
        if (Files.exists(replayPath)) assertThat(replayPath.toRealPath().getParent()).isEqualTo(archive);
        Files.writeString(replayPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(replay));
        assertThat(Files.readAllBytes(reportPath)).as("原报告内容必须完全保持").isEqualTo(originalReport);
        assertThat(passed).as("离线回放只验证原响应，不是重新发起或伪造成功请求；结果见 assertion-replay.json").isTrue();
    }

    /** 所有报告引用必须为合成响应目录下的单个 JSON 文件，禁止父目录跳转、绝对路径及符号链接逃逸。 */
    private Path exchangePath(Path archive, Path exchanges, String relative) throws IOException {
        if (!relative.matches("synthetic-exchanges/[A-Za-z0-9-]+\\.json")) {
            throw new IllegalArgumentException("归档响应必须引用 synthetic-exchanges 下的相对 JSON 文件");
        }
        Path resolved = archive.resolve(relative).toRealPath();
        if (!resolved.getParent().equals(exchanges)) throw new IllegalArgumentException("归档响应不能离开合成响应目录");
        return resolved;
    }

    /** 只读取归档根目录内的固定文件，避免固定文件被链接到目录外。 */
    private Path directChild(Path archive, String name) throws IOException {
        Path resolved = archive.resolve(name).toRealPath();
        if (!resolved.getParent().equals(archive)) throw new IllegalArgumentException("固定归档文件不能离开归档目录");
        return resolved;
    }

    /** 与在线 smoke 一致地检查四要素；离线回放不会因规则断言修正放宽接口结构。 */
    private boolean hasRequiredSections(JsonNode sections) {
        return Set.of("BACKGROUND", "TASK", "OUTPUT", "CONSTRAINTS").stream().allMatch(type -> {
            for (JsonNode section : sections) {
                if (type.equals(section.path("type").asText()) && !section.path("content").asText().isBlank()) return true;
            }
            return false;
        });
    }
}
