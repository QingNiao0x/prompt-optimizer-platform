package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactCategory;
import com.promptoptimizer.enhancement.domain.PlanningFactOrigin;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
            "(必须|须由|应当|不得|不应|至少|不超过|超过.{0,24}(?:必须|须|应)|仅当|只有.{0,18}(?:才|方可))"
    );
    private static final Pattern UNSAFE_INSTRUCTION = Pattern.compile(
            "(?i)(忽略.{0,12}指令|系统提示|开发者消息|提示词|模型指令|api.?key|password|token|\\.env|id_rsa|\\.pem|\\.key)"
    );
    private static final Pattern PROTECTED_PATH = Pattern.compile(
            "(?i)(?:^|[/\\\\])(?:\\.env(?:\\.[^/\\\\]+)?|id_rsa|id_ed25519|[^/\\\\]+\\.(?:pem|key))$"
    );
    private static final Set<String> GENERIC_TERMS = Set.of(
            "开发", "实现", "分析", "生成", "处理", "添加", "项目", "方案", "文件", "内容", "用户", "接口", "需求"
    );
    private final SensitiveValueDetector sensitiveValueDetector = new SensitiveValueDetector();

    /** 返回卡片与超出数量上限而未纳入计划的事实数。 */
    Extraction extract(ContextSnapshot context, String query) {
        if (context == null || query == null || query.isBlank()) return new Extraction(List.of(), 0);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<PlanningFactCard> cards = new ArrayList<>();
        int omitted = 0;
        for (FileSnippet file : PlanningDigestSelector.select(context.fileSnippets(), query, 30)) {
            if (file.path() == null || file.path().isBlank() || file.path().length() > 256
                    || file.path().matches(".*[\\r\\n].*") || PROTECTED_PATH.matcher(file.path()).find()
                    || sensitiveValueDetector.containsCredential(file.path()) || file.content() == null) continue;
            for (String raw : file.content().split("[\\r\\n。；;]+")) {
                String evidence = raw.trim();
                if (evidence.length() < 4 || evidence.length() > MAX_EVIDENCE_CHARACTERS
                        || !safeEvidence(evidence)) continue;
                PlanningFactCategory category = classify(evidence);
                if (category == null || !relevant(evidence, file.path(), query, category)) continue;
                String key = file.path() + "\u0000" + normalize(evidence);
                if (!seen.add(key)) continue;
                if (cards.size() == MAX_CARDS) {
                    omitted++;
                    continue;
                }
                cards.add(new PlanningFactCard("F" + String.format(Locale.ROOT, "%02d", cards.size() + 1),
                        category,
                        PlanningDigestSelector.isDocument(file)
                                ? PlanningFactOrigin.USER_MATERIAL
                                : PlanningFactOrigin.PROJECT_SOURCE,
                        file.path(), evidence));
            }
        }
        return new Extraction(List.copyOf(cards), omitted);
    }

    private PlanningFactCategory classify(String evidence) {
        var label = LABEL.matcher(evidence);
        if (label.find()) {
            String key = label.group(1).toLowerCase(Locale.ROOT);
            if (contains(key, "研究范围", "研究地区", "地区范围", "覆盖地区", "地区")) return PlanningFactCategory.REGION;
            if (contains(key, "目标读者", "目标受众", "面向学生", "受众")) return PlanningFactCategory.AUDIENCE;
            if (contains(key, "适用法域", "司法辖区", "法域")) return PlanningFactCategory.JURISDICTION;
            if (contains(key, "数据来源", "资料来源", "来源")) return PlanningFactCategory.DATA_SOURCE;
            if (contains(key, "数据格式", "文件格式", "格式")) return PlanningFactCategory.DATA_FORMAT;
            if (contains(key, "疾病亚类", "病种分类", "疾病分类", "亚类")) return PlanningFactCategory.DISEASE_CATEGORY;
            if (contains(key, "人群划分", "年龄组", "城乡", "研究对象", "目标人群", "人群")) return PlanningFactCategory.POPULATION;
            if (contains(key, "分析工具", "统计软件", "编程语言", "工具")) return PlanningFactCategory.ANALYSIS_TOOL;
            if (contains(key, "分析方法", "研究方法", "分解方法", "方法学", "方法")) return PlanningFactCategory.ANALYSIS_METHOD;
            if (contains(key, "输出格式", "交付格式", "输出内容")) return PlanningFactCategory.OUTPUT_FORMAT;
            if (contains(key, "验收标准", "成功标准", "评价标准")) return PlanningFactCategory.ACCEPTANCE_CRITERIA;
            if (contains(key, "时间范围", "研究期间", "分析期间", "起止时间", "时间段")) return PlanningFactCategory.TIME_RANGE;
        }
        return EXPLICIT_RULE.matcher(evidence).find() ? PlanningFactCategory.BUSINESS_RULE : null;
    }

    private boolean relevant(String evidence, String path, String query, PlanningFactCategory category) {
        String task = query.toLowerCase(Locale.ROOT);
        boolean researchTask = contains(task, "研究", "论文", "死亡率", "发病率", "yll", "arriaga", "流行病", "疾病");
        boolean analysisTask = researchTask || contains(task, "数据", "统计", "报表", "分析", "比较");
        if (researchTask && Set.of(PlanningFactCategory.REGION, PlanningFactCategory.DATA_SOURCE,
                PlanningFactCategory.DATA_FORMAT, PlanningFactCategory.DISEASE_CATEGORY,
                PlanningFactCategory.POPULATION, PlanningFactCategory.ANALYSIS_TOOL,
                PlanningFactCategory.ANALYSIS_METHOD, PlanningFactCategory.OUTPUT_FORMAT,
                PlanningFactCategory.ACCEPTANCE_CRITERIA, PlanningFactCategory.TIME_RANGE).contains(category)) {
            return true;
        }
        if (analysisTask && Set.of(PlanningFactCategory.DATA_SOURCE, PlanningFactCategory.DATA_FORMAT,
                PlanningFactCategory.ANALYSIS_TOOL, PlanningFactCategory.ANALYSIS_METHOD,
                PlanningFactCategory.OUTPUT_FORMAT, PlanningFactCategory.ACCEPTANCE_CRITERIA,
                PlanningFactCategory.TIME_RANGE).contains(category)) {
            return true;
        }
        Set<String> terms = new LinkedHashSet<>();
        var matcher = Pattern.compile("[\\p{IsHan}]{2,}|[A-Za-z0-9_]{3,}").matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.matches("[\\p{IsHan}]+")) {
                for (int index = 0; index + 2 <= token.length(); index++) {
                    String bigram = token.substring(index, index + 2);
                    if (!GENERIC_TERMS.contains(bigram)) terms.add(bigram);
                }
            } else {
                terms.add(token);
            }
        }
        String normalizedEvidence = (evidence + " " + path).toLowerCase(Locale.ROOT);
        return terms.stream().anyMatch(normalizedEvidence::contains);
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
