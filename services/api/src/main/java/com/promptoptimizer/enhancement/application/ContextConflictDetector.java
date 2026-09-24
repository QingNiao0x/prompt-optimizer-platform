package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.api.PlanAnswer;
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
        Map<String, Map<String, SourceValue>> byField = new LinkedHashMap<>();
        for (FileSnippet file : context.fileSnippets()) {
            if (file.content() == null || file.path() == null || file.path().length() > 256
                    || sensitiveValueDetector.containsCredential(file.path())
                    || PROTECTED_NAME.matcher(file.path()).find()) continue;
            var matches = FIELD.matcher(file.content());
            while (matches.find()) {
                String key = matches.group(1).trim();
                String value = matches.group(2).trim();
                if (!MATERIAL_FIELD.matcher(key).matches() || value.length() < 2
                        || sensitiveValueDetector.containsCredential(value)
                        || value.matches(".*(待定|未知|未明确|可能|例如|[？?]).*")) continue;
                byField.computeIfAbsent(key, unused -> new LinkedHashMap<>())
                        .putIfAbsent(value.toLowerCase(Locale.ROOT), new SourceValue(file.path(), value));
            }
        }
        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, Map<String, SourceValue>> entry : byField.entrySet()) {
            List<SourceValue> values = new ArrayList<>(entry.getValue().values());
            SourceValue first = null;
            SourceValue second = null;
            for (int left = 0; left < values.size() && second == null; left++) {
                for (int right = left + 1; right < values.size(); right++) {
                    if (!values.get(left).path().equals(values.get(right).path())) {
                        first = values.get(left);
                        second = values.get(right);
                        break;
                    }
                }
            }
            if (second == null) continue;
            boolean confirmed = answers.stream().anyMatch(answer -> answer.question().contains(entry.getKey())
                    && values.stream().anyMatch(value -> answer.answer().contains(value.value())));
            if (confirmed) continue;
            conflicts.add("资料对“" + entry.getKey() + "”存在不同取值："
                    + sourceLabel(first.path()) + "（" + first.value() + "）与 "
                    + sourceLabel(second.path()) + "（" + second.value() + "）。请确认本次采用哪一项。");
            if (conflicts.size() == 3) break;
        }
        return conflicts;
    }

    private String sourceLabel(String path) {
        return path.length() <= 80 ? path : "…" + path.substring(path.length() - 79);
    }

    private record SourceValue(String path, String value) { }
}
