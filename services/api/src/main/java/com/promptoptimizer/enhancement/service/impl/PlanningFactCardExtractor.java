package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 从任务相关的脱敏文件片段中提取可引用的明确事实，不对材料做语义补全。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanningFactCardExtractor {
    private static final int MAX_CARDS = 20;
    private static final int MAX_EVIDENCE_CHARACTERS = 220;
    private static final Pattern LABEL = Pattern.compile("^\\s*([^：:=]{2,24})\\s*[:：=]\\s*(.{1,})$");
    private static final Pattern EXPLICIT_RULE = Pattern.compile(
            "(必须|须由|应当|不得|不应|至少|不超过|超过.{0,24}(?:必须|须|应)|仅当|只有.{0,18}(?:才|方可)"
                    + "|(?:应|须)(?:把|将)[^。；;]{2,100}(?:分别说明|分别标注|明确区分|分开记录))"
    );
    private static final Pattern UNSAFE_INSTRUCTION = Pattern.compile(
            "(?i)(忽略.{0,12}指令|系统提示|开发者消息|提示词|模型指令|api.?key|password|token|\\.env|id_rsa|\\.pem|\\.key)"
    );
    private static final Pattern PROTECTED_PATH = Pattern.compile(
            "(?i)(?:^|[/\\\\])(?:\\.env(?:\\.[^/\\\\]+)?|id_rsa|id_ed25519|[^/\\\\]+\\.(?:pem|key))$"
    );
    private final SensitiveValueDetector sensitiveValueDetector = new SensitiveValueDetector();
    private final ExplicitRuleEvidenceExtractor explicitRuleExtractor = new ExplicitRuleEvidenceExtractor();

    /** 返回卡片与超出数量上限而未纳入计划的事实数。 */
    Extraction extract(ContextSnapshot context, String query) {
        if (context == null || query == null || query.isBlank()) return new Extraction(List.of(), 0);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<PlanningFactCard> cards = new ArrayList<>();
        PlanningEvidencePolicy evidencePolicy = new PlanningEvidencePolicy(query);
        int omitted = 0;
        var explicitRules = explicitRuleExtractor.extract(context, query, MAX_EVIDENCE_CHARACTERS);
        // 与最终增强共用明确规则的识别条件，并保持原 20 张卡片与遗漏计数契约。
        for (var evidence : explicitRules) {
            omitted += appendCard(cards, seen, evidence.file(), evidence.text(), PlanningFactCategory.BUSINESS_RULE);
        }
        for (FileSnippet file : PlanningDigestSelector.select(context.fileSnippets(), query, 30)) {
            if (file.path() == null || file.path().isBlank() || file.path().length() > 256
                    || file.path().matches(".*[\\r\\n].*") || PROTECTED_PATH.matcher(file.path()).find()
                    || sensitiveValueDetector.containsCredential(file.path()) || file.content() == null) continue;
            for (String raw : evidencePolicy.evidenceLines(file)) {
                String evidence = raw.trim();
                if (evidence.length() < 4 || evidence.length() > MAX_EVIDENCE_CHARACTERS
                        || !safeEvidence(evidence)) continue;
                PlanningFactCategory category = classify(evidence);
                if (category == null || !evidencePolicy.relevant(file, evidence, category)) continue;
                // 完整规则已经登记时，不再把它拆出的半句作为另一条事实挤占预算。
                if (explicitRules.stream().anyMatch(rule -> rule.file().path().equals(file.path())
                        && rule.text().contains(evidence))) continue;
                omitted += appendCard(cards, seen, file, evidence, category);
            }
        }
        return new Extraction(List.copyOf(cards), omitted);
    }

    /** 按来源和完整证据去重；保留比较符和不同取值，预算外事实仍计入覆盖提醒。 */
    private int appendCard(List<PlanningFactCard> cards, LinkedHashSet<String> seen, FileSnippet file,
                           String evidence, PlanningFactCategory category) {
        String key = file.path() + "\u0000" + Normalizer.normalize(evidence, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ").trim();
        if (!seen.add(key)) return 0;
        if (cards.size() == MAX_CARDS) return 1;
        cards.add(new PlanningFactCard("F" + String.format(Locale.ROOT, "%02d", cards.size() + 1),
                category, PlanningEvidencePolicy.origin(file.path(), file.language()), file.path(), evidence));
        return 0;
    }

    private PlanningFactCategory classify(String evidence) {
        var label = LABEL.matcher(evidence);
        if (label.find()) {
            String key = label.group(1).toLowerCase(Locale.ROOT);
            if (contains(key, "研究范围", "研究地区", "地区范围", "覆盖地区", "地区")) return PlanningFactCategory.REGION;
            if (contains(key, "目标读者", "目标受众", "面向学生", "受众")) return PlanningFactCategory.AUDIENCE;
            if (contains(key, "适用法域", "司法辖区", "法域")) return PlanningFactCategory.JURISDICTION;
            if (contains(key, "数据来源", "资料来源", "来源")) return PlanningFactCategory.DATA_SOURCE;
            // 输出格式是交付决定；须先于通用“格式”匹配，不能当作输入数据格式。
            if (contains(key, "输出格式", "交付格式", "输出内容")) return PlanningFactCategory.OUTPUT_FORMAT;
            if (contains(key, "数据格式", "文件格式", "格式")) return PlanningFactCategory.DATA_FORMAT;
            if (contains(key, "疾病亚类", "病种分类", "疾病分类", "亚类")) return PlanningFactCategory.DISEASE_CATEGORY;
            if (contains(key, "人群划分", "年龄组", "城乡", "研究对象", "目标人群", "人群")) return PlanningFactCategory.POPULATION;
            if (contains(key, "分析工具", "统计软件", "编程语言", "工具")) return PlanningFactCategory.ANALYSIS_TOOL;
            if (contains(key, "分析方法", "研究方法", "分解方法", "方法学", "方法")) return PlanningFactCategory.ANALYSIS_METHOD;
            if (contains(key, "验收标准", "成功标准", "评价标准")) return PlanningFactCategory.ACCEPTANCE_CRITERIA;
            if (contains(key, "时间范围", "研究期间", "分析期间", "起止时间", "时间段")) return PlanningFactCategory.TIME_RANGE;
            if (contains(key, "阈值", "时限", "口径", "规则", "版本")) return PlanningFactCategory.BUSINESS_RULE;
        }
        // 明确的具名未知同样是资料状态，须传入直接增强；它不来自关键词缺失或专业默认推断。
        return EXPLICIT_RULE.matcher(evidence).find() || !UnresolvedDecisionContract.declaredPending(evidence).isEmpty()
                ? PlanningFactCategory.BUSINESS_RULE : null;
    }

    /**
     * 旧 Plan 卡片也必须经过当前用途与相关性校验，不能因已绑定就绕过筛选。
     * 二次检索未再次选中原文件时仍可使用已绑定摘录；不把“未再次召回”当成证据失效。
     */
    List<PlanningFactCard> filterBoundFacts(List<PlanningFactCard> facts, ContextSnapshot context, String query) {
        if (facts == null || facts.isEmpty()) return List.of();
        PlanningEvidencePolicy policy = new PlanningEvidencePolicy(query);
        List<PlanningFactCard> result = new ArrayList<>();
        for (PlanningFactCard card : facts) {
            if (card == null || card.category() == null || card.origin() == null
                    || card.sourcePath().isBlank() || card.sourcePath().length() > 256
                    || card.sourcePath().matches(".*[\\r\\n].*")
                    || PROTECTED_PATH.matcher(card.sourcePath()).find()
                    || sensitiveValueDetector.containsCredential(card.sourcePath())
                    || card.evidence().isBlank() || card.evidence().length() > MAX_EVIDENCE_CHARACTERS
                    || !safeEvidence(card.evidence())) continue;
            FileSnippet file = context.fileSnippets().stream()
                    .filter(value -> card.sourcePath().equals(value.path())).findFirst()
                    .orElseGet(() -> new FileSnippet(card.sourcePath(), "", card.evidence(), "", false));
            if (!policy.relevant(file, card.evidence(), card.category())) continue;
            // 当前片段确实包含该证据时，重新检查它是否落在 Markdown 示例小节内。
            if (file.content() != null && normalize(file.content()).contains(normalize(card.evidence()))
                    && policy.evidenceLines(file).stream()
                    .noneMatch(line -> normalize(line).contains(normalize(card.evidence())))
                    && !explicitRuleExtractor.containsSourceEvidence(file, query, card.evidence())) continue;
            result.add(new PlanningFactCard(card.id(), card.category(),
                    PlanningEvidencePolicy.origin(file.path(), file.language()), card.sourcePath(), card.evidence()));
        }
        return List.copyOf(result);
    }

    private boolean safeEvidence(String value) {
        return !UNSAFE_INSTRUCTION.matcher(value).find()
                && !sensitiveValueDetector.containsCredential(value);
    }

    private boolean contains(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
    }

    record Extraction(List<PlanningFactCard> cards, int omittedCount) {
        Extraction {
            cards = List.copyOf(cards);
        }
    }
}
