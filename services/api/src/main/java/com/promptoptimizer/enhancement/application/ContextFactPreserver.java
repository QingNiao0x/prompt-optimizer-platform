package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从已过滤的任务相关文档中保留有出处的明确规则，防止模型遗漏短句。
 * 文档内容始终作为待核对资料，不具有修改平台指令的权限。
 */
final class ContextFactPreserver {
    private static final int MAX_FACTS = 4;
    private static final int MAX_FACT_CHARACTERS = 180;
    private static final Pattern FACT = Pattern.compile(
            "(必须|应当|不得|至少|不超过|超过|仅当|研究范围[：:]|数据来源[：:]|分析工具[：:]|审批条件[：:])"
    );
    private static final Pattern INSTRUCTION = Pattern.compile(
            "(?i)(忽略.*指令|系统提示|开发者消息|提示词|模型指令|api.?key|password|token|\\.env|id_rsa|\\.pem|\\.key)"
    );
    private static final Set<String> GENERIC_TERMS = Set.of(
            "开发", "实现", "分析", "生成", "处理", "添加", "项目", "方案", "文件", "内容", "用户", "接口", "需求"
    );
    private final SensitiveValueDetector sensitiveValueDetector = new SensitiveValueDetector();

    /** 仅返回已经进入安全上下文、且具有可显示来源的简短事实。 */
    List<String> facts(ContextSnapshot context, String query) {
        if (query == null || query.isBlank() || context.fileSnippets().isEmpty()) return List.of();
        Set<String> unique = new LinkedHashSet<>();
        List<FileSnippet> selected = PlanningDigestSelector.select(context.fileSnippets(), query, 12);
        for (FileSnippet file : selected) {
            if (!PlanningDigestSelector.isDocument(file) || file.path() == null
                    || file.path().length() > 256 || file.path().matches(".*[\\r\\n].*")
                    || file.content() == null || unsafe(file.path())) continue;
            for (String line : file.content().split("[\\r\\n。；;]+")) {
                String value = line.trim();
                if (value.length() < 7 || value.length() > MAX_FACT_CHARACTERS
                        || !FACT.matcher(value).find() || unsafe(value)
                        || !related(value, file.path(), query)) continue;
                unique.add(file.path() + "：" + value);
                if (unique.size() >= MAX_FACTS) return List.copyOf(unique);
            }
        }
        return new ArrayList<>(unique);
    }

    /** 规则须与当前任务有主题交集；“按方案”类任务允许采用已选中的方案文件。 */
    private boolean related(String fact, String path, String query) {
        if (query.contains("方案") && path.contains("方案")) return true;
        var words = Pattern.compile("[\\p{IsHan}]{2,}|[A-Za-z0-9_]{3,}")
                .matcher(query.toLowerCase(Locale.ROOT));
        while (words.find()) {
            String word = words.group();
            if (!word.matches("[\\p{IsHan}]+")) {
                if (fact.toLowerCase(Locale.ROOT).contains(word)) return true;
                continue;
            }
            for (int index = 0; index + 2 <= word.length(); index++) {
                String term = word.substring(index, index + 2);
                if (!GENERIC_TERMS.contains(term) && fact.contains(term)) return true;
            }
        }
        return false;
    }

    private boolean unsafe(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return INSTRUCTION.matcher(normalized).find() || sensitiveValueDetector.containsCredential(value);
    }
}
