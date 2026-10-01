package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 对已经解析的材料做保守的同名字段冲突检测，供 Plan 与最终结果显示。 */
final class ContextConflictDetector {
    private static final Pattern FIELD = Pattern.compile(
            "(?m)^\\s*([\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z0-9_.-]{1,29})\\s*[:：=]\\s*([^\\r\\n。；;]{1,100})"
    );
    private static final Pattern MATERIAL_FIELD = Pattern.compile(".*(阈值|时限|范围|口径|标准|规则|格式|版本).*");
    private static final Pattern PROTECTED_NAME = Pattern.compile("(?i)(?:^|[/\\\\])(?:\\.env(?:\\.[^/\\\\]+)?|id_rsa|[^/\\\\]+\\.(?:pem|key))$");
    private final SensitiveValueDetector sensitiveValueDetector = new SensitiveValueDetector();

    /** 只报告跨文件的同名字段不同取值；语义冲突仍交由模型和人工评测识别。 */
    List<String> detect(ContextSnapshot context, List<PlanAnswer> answers) {
        return detect(context, answers, "");
    }

    /** 同名字段冲突也遵守证据用途边界，避免已筛掉的测试样例经冲突通道重新进入 Plan。 */
    List<String> detect(ContextSnapshot context, List<PlanAnswer> answers, String query) {
        return detect(context, answers, query, List.of());
    }

    /** 首次已读规则未被再次召回时仍参与比较，不只比较二次检索内部的文件。 */
    List<String> detect(ContextSnapshot context, List<PlanAnswer> answers, String query,
                        List<PlanningFactCard> boundFacts) {
        return findings(context, ConfirmedDecisionSet.from(answers), query, boundFacts).stream()
                .map(Finding::message).toList();
    }

    /** 内部保留字段、双方取值和来源，使冲突与普通未决问题分别判定。 */
    List<Finding> findings(ContextSnapshot context, ConfirmedDecisionSet decisions, String query,
                           List<PlanningFactCard> boundFacts) {
        PlanningEvidencePolicy evidencePolicy = new PlanningEvidencePolicy(query);
        Map<String, Map<String, SourceValue>> byField = new LinkedHashMap<>();
        List<StagedFile> evidence = new ArrayList<>();
        context.fileSnippets().forEach(file -> evidence.add(new StagedFile(file, false)));
        boundFacts.forEach(card -> evidence.add(new StagedFile(new FileSnippet(
                card.sourcePath(), "", card.evidence(), "", false), true)));
        for (StagedFile staged : evidence) {
            FileSnippet file = staged.file();
            if (file.content() == null || file.path() == null || file.path().length() > 256
                    || sensitiveValueDetector.containsCredential(file.path())
                    || PROTECTED_NAME.matcher(file.path()).find() || !evidencePolicy.allows(file)) continue;
            var matches = FIELD.matcher(String.join("\n", evidencePolicy.evidenceLines(file)));
            while (matches.find()) {
                String key = matches.group(1).trim();
                String value = matches.group(2).trim();
                if (!MATERIAL_FIELD.matcher(key).matches() || value.length() < 2
                        || sensitiveValueDetector.containsCredential(value)
                        || value.matches(".*(待定|未知|未明确|可能|例如|[？?]).*")) continue;
                if (query != null && !query.isBlank()
                        && !evidencePolicy.relevant(file, key + "：" + value, PlanningFactCategory.BUSINESS_RULE)) continue;
                byField.computeIfAbsent(key, unused -> new LinkedHashMap<>())
                        .putIfAbsent(value.toLowerCase(Locale.ROOT), new SourceValue(file.path(), value,
                                scope(value), staged.fromPlan()));
            }
        }
        List<Finding> conflicts = new ArrayList<>();
        for (Map.Entry<String, Map<String, SourceValue>> entry : byField.entrySet()) {
            List<SourceValue> values = new ArrayList<>(entry.getValue().values());
            SourceValue first = null;
            SourceValue second = null;
            for (int left = 0; left < values.size() && second == null; left++) {
                for (int right = left + 1; right < values.size(); right++) {
                    if ((!values.get(left).path().equals(values.get(right).path())
                            || values.get(left).fromPlan() != values.get(right).fromPlan())
                            && !isCurrentToTarget(values.get(left), values.get(right))
                            && !decisions.resolvesConflict(entry.getKey(),
                                    List.of(values.get(left).value(), values.get(right).value()))) {
                        first = values.get(left);
                        second = values.get(right);
                        break;
                    }
                }
            }
            if (second == null) continue;
            String message = "资料对“" + entry.getKey() + "”存在不同取值："
                    + sourceLabel(first) + "（" + first.value() + "）与 "
                    + sourceLabel(second) + "（" + second.value() + "）。请确认本次采用哪一项。";
            conflicts.add(new Finding(entry.getKey(), first.path(), first.value(),
                    second.path(), second.value(), message));
            if (conflicts.size() == 3) break;
        }
        return conflicts;
    }

    private Scope scope(String value) {
        if (value.matches("^(?:目标|迁移后|计划改为|拟采用).*")) return Scope.TARGET;
        if (value.matches("^(?:当前|现有|目前|迁移前).*")) return Scope.CURRENT_STATE;
        return Scope.CHOICE;
    }

    private boolean isCurrentToTarget(SourceValue left, SourceValue right) {
        return left.scope() == Scope.CURRENT_STATE && right.scope() == Scope.TARGET
                || left.scope() == Scope.TARGET && right.scope() == Scope.CURRENT_STATE;
    }

    private String sourceLabel(SourceValue source) {
        String path = source.path();
        return (path.length() <= 80 ? path : "…" + path.substring(path.length() - 79))
                + (source.fromPlan() ? "［首次已读］" : "");
    }

    record Finding(String field, String firstPath, String firstValue,
                   String secondPath, String secondValue, String message) { }

    private record SourceValue(String path, String value, Scope scope, boolean fromPlan) { }

    private record StagedFile(FileSnippet file, boolean fromPlan) { }
}
