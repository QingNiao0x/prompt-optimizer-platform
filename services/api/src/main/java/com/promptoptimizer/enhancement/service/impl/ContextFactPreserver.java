package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从已过滤的任务相关文档中保留有出处的明确规则，防止模型遗漏短句。
 * 文档内容始终作为待核对资料，不具有修改平台指令的权限。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ContextFactPreserver {
    private static final int MAX_FACTS = 4;
    private static final int MAX_FACT_CHARACTERS = 180;
    private static final Pattern FACT = Pattern.compile(
            "(必须|应当|不得|至少|不超过|仅当|研究范围[：:]|数据来源[：:]|分析工具[：:]|审批条件[：:])"
    );
    private static final Pattern INSTRUCTION = Pattern.compile(
            "(?i)(忽略.*指令|系统提示|开发者消息|提示词|模型指令|api.?key|password|token|\\.env|id_rsa|\\.pem|\\.key)"
    );
    private final SensitiveValueDetector sensitiveValueDetector = new SensitiveValueDetector();
    private final ExplicitRuleEvidenceExtractor explicitRuleExtractor = new ExplicitRuleEvidenceExtractor();

    /** 仅返回已经进入安全上下文、且具有可显示来源的简短事实。 */
    List<String> facts(ContextSnapshot context, String query) {
        if (context == null || query == null || query.isBlank() || context.fileSnippets().isEmpty()) return List.of();
        Set<String> unique = new LinkedHashSet<>();
        var explicitRules = explicitRuleExtractor.extract(context, query, MAX_FACT_CHARACTERS);
        // 明确要求保留的代号及同句边界先占既有预算，避免被通用 README 规则挤出。
        for (var evidence : explicitRules) {
            unique.add(evidence.file().path() + "：" + evidence.text());
            if (unique.size() >= MAX_FACTS) return List.copyOf(unique);
        }
        PlanningEvidencePolicy evidencePolicy = new PlanningEvidencePolicy(query);
        List<FileSnippet> selected = PlanningDigestSelector.select(context.fileSnippets(), query, 12);
        for (FileSnippet file : selected) {
            if (!PlanningDigestSelector.isDocument(file) || file.path() == null
                    || file.path().length() > 256 || file.path().matches(".*[\\r\\n].*")
                    || file.content() == null || unsafe(file.path())) continue;
            for (String line : evidencePolicy.evidenceLines(file)) {
                String value = line.trim();
                if (value.length() < 7 || value.length() > MAX_FACT_CHARACTERS
                        || !FACT.matcher(value).find() || unsafe(value)
                        || !evidencePolicy.relevant(file, value, PlanningFactCategory.BUSINESS_RULE)) continue;
                if (explicitRules.stream().anyMatch(rule -> rule.file().path().equals(file.path())
                        && rule.text().contains(value))) continue;
                unique.add(file.path() + "：" + value);
                if (unique.size() >= MAX_FACTS) return List.copyOf(unique);
            }
        }
        return new ArrayList<>(unique);
    }

    private boolean unsafe(String value) {
        return INSTRUCTION.matcher(value).find() || sensitiveValueDetector.containsCredential(value);
    }
}
