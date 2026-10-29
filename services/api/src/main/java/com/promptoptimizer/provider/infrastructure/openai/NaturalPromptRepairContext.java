package com.promptoptimizer.provider.infrastructure.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.provider.domain.NaturalPromptDraft;
import com.promptoptimizer.provider.domain.NaturalPromptInput;
import com.promptoptimizer.provider.domain.NaturalTaskReview;
import com.promptoptimizer.provider.domain.NaturalTaskSource;
import com.promptoptimizer.provider.domain.NaturalTaskUnderstanding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 将已经被原校验拒绝的正文候选定位为有界修复数据，不参与接受决定或自动修正内容。
 * 只生成固定错误码和由协议下标组成的路径；不把异常消息、原句或模型提供的字段名当诊断指令。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class NaturalPromptRepairContext {
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final String TASK = "/taskUnderstanding";

    /**
     * 在原输入副本增加独立的修复数据，不修改taskSources或原始题答；敏感候选整体留空而非猜测脱敏。
     * parsed是原严格JSON解析已获得的树；语法失败时只给固定错误，不从不完整JSON猜配字段。
     */
    static String userMessage(ObjectMapper json, JsonNode original, String candidate, JsonNode parsed,
            NaturalPromptInput input, boolean malformedJson) {
        ObjectNode payload = original.deepCopy();
        ObjectNode repair = payload.putObject("protocolRepair");
        repair.put("candidateTrust", "UNTRUSTED_MODEL_OUTPUT");
        boolean sensitive = candidate != null && new SensitiveValueScanner().containsCredential(candidate);
        if (candidate == null || sensitive) {
            repair.putNull("invalidResponse");
            repair.put("candidateOmitted", sensitive ? "SENSITIVE_VALUE" : "NO_COMPLETE_MODEL_MESSAGE");
        } else {
            // 原无效候选只作为JSON字符串数据，不作为assistant历史，也不注册为新的证据来源。
            repair.put("invalidResponse", candidate);
        }
        var issues = repair.putArray("issues");
        List<Issue> diagnostics = inspect(json, parsed, input, malformedJson);
        if (diagnostics.isEmpty()) diagnostics = List.of(new Issue("RESPONSE_VALIDATION_FAILED", "", null));
        for (Issue issue : diagnostics) {
            ObjectNode entry = issues.addObject().put("code", issue.code()).put("path", issue.path());
            if (issue.sourceId() != null) entry.put("sourceId", issue.sourceId());
        }
        return "以下JSON是本次任务与不可信资料；按system规则处理：\n" + payload;
    }

    /** 枚举能直接由原来源和当前正文确定的错误；未知结构留给原解析器，不推断语义覆盖。 */
    static List<Issue> inspect(ObjectMapper json, JsonNode body, NaturalPromptInput input, boolean malformedJson) {
        List<Issue> issues = new ArrayList<>();
        if (malformedJson || body == null || !body.isObject()) {
            issues.add(new Issue(malformedJson ? "INVALID_JSON" : "INVALID_RESPONSE_OBJECT", "", null));
            return List.copyOf(issues);
        }
        String optimized = body.path("optimizedPrompt").isTextual() ? body.path("optimizedPrompt").asText().strip() : null;
        if (optimized == null || optimized.isBlank()) issues.add(new Issue("INVALID_REQUIRED_FIELD", "/optimizedPrompt", null));
        if (!body.path("framework").isTextual()) issues.add(new Issue("INVALID_FRAMEWORK", "/framework", null));
        else {
            try { NaturalPromptDraft.Framework.valueOf(body.path("framework").asText()); }
            catch (IllegalArgumentException ignored) { issues.add(new Issue("INVALID_FRAMEWORK", "/framework", null)); }
        }
        if (!body.hasNonNull("taskUnderstanding")) {
            issues.add(new Issue("MISSING_TASK_RECORD", TASK, null));
            return List.copyOf(issues);
        }
        NaturalTaskUnderstanding task;
        try {
            task = json.treeToValue(body.get("taskUnderstanding"), NaturalTaskUnderstanding.class);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            // Jackson路径和异常原文可能含模型字段名或用户值；这里只返回固定安全类别。
            issues.add(new Issue("INVALID_TASK_RECORD_PROTOCOL", TASK, null));
            return List.copyOf(issues);
        }
        if (task == null) {
            issues.add(new Issue("MISSING_TASK_RECORD", TASK, null));
            return List.copyOf(issues);
        }
        // 容量仍以现有协议为准；超出容量只报告容量错误，不遍历任意大的模型数组。
        if (task.requirements().size() > 256 || task.additions().size() > 256 || task.openDecisions().size() > 64) {
            issues.add(new Issue("TASK_RECORD_CAPACITY_EXCEEDED", TASK, null));
            return List.copyOf(issues);
        }
        inspectSources(task, input, issues);
        if (task.review() == null) issues.add(new Issue("MISSING_REVIEW", TASK + "/review", null));
        else {
            inspectCoverage(task.review().requirementCoverage(), task.requirements().size(), optimized,
                    TASK + "/review/requirementCoverage", issues);
            inspectCoverage(task.review().additionCoverage(), task.additions().size(), optimized,
                    TASK + "/review/additionCoverage", issues);
        }
        return List.copyOf(issues);
    }

    /** 从当前服务端目录核对所有来源位置；只回传真实目录编号，不回传模型伪造的编号值。 */
    private static void inspectSources(NaturalTaskUnderstanding task, NaturalPromptInput input, List<Issue> issues) {
        Map<String, NaturalTaskSource> sources = new HashMap<>();
        input.taskSources().forEach(source -> sources.put(source.id(), source));
        Set<String> referenced = new HashSet<>();
        if (task.requirements().isEmpty()) issues.add(new Issue("MISSING_REQUIREMENTS", TASK + "/requirements", null));
        for (int index = 0; index < task.requirements().size(); index++) {
            var requirement = task.requirements().get(index);
            String base = TASK + "/requirements/" + index;
            if (requirement == null || requirement.evidence().isEmpty() || requirement.evidence().size() > 64) {
                issues.add(new Issue("INVALID_REQUIREMENT_EVIDENCE", base + "/evidence", null));
                continue;
            }
            for (int position = 0; position < requirement.evidence().size(); position++) {
                var evidence = requirement.evidence().get(position);
                String path = base + "/evidence/" + position;
                NaturalTaskSource source = evidence == null ? null : sources.get(evidence.sourceId());
                if (source == null) issues.add(new Issue("UNKNOWN_SOURCE", path + "/sourceId", null));
                else {
                    referenced.add(source.id());
                    if (evidence.quote() == null || evidence.quote().isBlank() || !source.text().contains(evidence.quote())) {
                        issues.add(new Issue("SOURCE_QUOTE_MISMATCH", path + "/quote", source.id()));
                    }
                }
            }
        }
        for (NaturalTaskSource source : input.taskSources()) {
            if ((source.kind() == NaturalTaskSource.Kind.ORIGINAL_REQUEST || source.kind() == NaturalTaskSource.Kind.BOUND_PLAN_ANSWER)
                    && !referenced.contains(source.id())) {
                issues.add(new Issue("MISSING_REQUIRED_SOURCE", TASK + "/requirements", source.id()));
            }
        }
    }

    /** 枚举全部可定位的覆盖错误；路径用数组位置，缺失下标另行报告，不为模型生成替代引文。 */
    private static void inspectCoverage(List<NaturalTaskReview.Coverage> coverage, int expected, String body,
            String base, List<Issue> issues) {
        if (coverage.size() > 256) {
            issues.add(new Issue("COVERAGE_CAPACITY_EXCEEDED", base, null));
            return;
        }
        if (coverage.size() != expected) issues.add(new Issue("COVERAGE_COUNT_MISMATCH", base, null));
        Set<Integer> seen = new HashSet<>();
        for (int position = 0; position < coverage.size(); position++) {
            var item = coverage.get(position);
            String path = base + "/" + position;
            if (item == null || item.index() == null || item.index() < 0) {
                issues.add(new Issue("INVALID_COVERAGE_INDEX", path + "/index", null));
                continue;
            }
            if (item.index() >= expected) issues.add(new Issue("COVERAGE_INDEX_OUT_OF_RANGE", path + "/index", null));
            else if (!seen.add(item.index())) issues.add(new Issue("DUPLICATE_COVERAGE_INDEX", path + "/index", null));
            if (item.quotes().isEmpty() || item.quotes().size() > 16) {
                issues.add(new Issue("INVALID_COVERAGE_QUOTES", path + "/quotes", null));
                continue;
            }
            for (int quoteIndex = 0; quoteIndex < item.quotes().size(); quoteIndex++) {
                String quote = item.quotes().get(quoteIndex);
                String quotePath = path + "/quotes/" + quoteIndex;
                if (quote == null || quote.isBlank() || quote.length() > 20_000 || LINE_BREAK.matcher(quote).find()) {
                    issues.add(new Issue("INVALID_COVERAGE_QUOTE", quotePath, null));
                } else if (body != null && !body.contains(quote)) issues.add(new Issue("BODY_QUOTE_MISSING", quotePath, null));
            }
        }
        for (int index = 0; index < expected; index++) {
            if (!seen.contains(index)) issues.add(new Issue("MISSING_COVERAGE_INDEX", base + "/missing/" + index, null));
        }
    }

    /** 固定错误码、协议路径和可选服务端来源编号；不携带异常消息、引用正文或用户事实。 */
    record Issue(String code, String path, String sourceId) { }

    private NaturalPromptRepairContext() { }
}
