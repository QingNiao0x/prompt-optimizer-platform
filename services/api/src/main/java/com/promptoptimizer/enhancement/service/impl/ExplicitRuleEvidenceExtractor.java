package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.domain.FileSnippet;
import com.promptoptimizer.enhancement.domain.PlanningFactOrigin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 保留用户明确要求引用的规则代号及同句业务边界，不从代码常量推算规则含义。
 * 只读取当前安全上下文内的正式文档与用户资料；资料中的指令不具有平台权限。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class ExplicitRuleEvidenceExtractor {
    private static final String RULE_LABEL = "规则(?:代号|标识符?|编号)";
    private static final Pattern RULE_LABEL_PATTERN = Pattern.compile(RULE_LABEL);
    private static final Pattern RETENTION_REQUEST = Pattern.compile(
            "(?:保留|沿用|写明|列出|引用)[^。；;\\r\\n]{0,80}" + RULE_LABEL
                    + "|" + RULE_LABEL + "[^。；;\\r\\n]{0,80}(?:保留|沿用|写明|列出|引用)");
    private static final Pattern RULE = Pattern.compile(
            "(?:^|^[-*]\\s*)" + RULE_LABEL + "\\s*[:：=]?\\s*[`\"“]?"
                    + "([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)[`\"”]?\\s*"
                    + "(?:(?:表示|代表|对应|含义(?:为|是)?)\\s*[:：]?|[:：=])\\s*(.{4,})$");
    private static final Pattern RULE_AFTER_MEANING = Pattern.compile(
            "^(.{4,}?)\\s*[；;]\\s*" + RULE_LABEL + "\\s*(?:为|是|[:：=])?\\s*[`\"“]?"
                    + "([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)[`\"”]?(?:\\s*[；;]\\s*(.+))?$");
    private static final Pattern BOUNDARY = Pattern.compile(
            "最多|至少|不超过|不得超过|不早于|不晚于|上限|下限|限额|时限|仅限|只允许|必须|不得");
    private static final Pattern RELATIVE_TIME = Pattern.compile(
            "(?:前|后)\\s*(?:[0-9]{1,6}|[一二三四五六七八九十百千万两]{1,8})\\s*(?:秒|分钟|小时|天|日|周|月)");
    private static final Pattern BOUNDED_ACTION = Pattern.compile(
            "开放|开启|开始|截止|结束|停止|关闭|受理|提交|登记|签到|预约");
    private static final Pattern ACTION_BEFORE_TIME = Pattern.compile("开放|开启|截止|停止|关闭|受理|提交|登记|签到|预约");
    private static final Pattern ILLUSTRATIVE_CONTEXT = Pattern.compile(
            "(?i)(?:^|[：:，,（(])\\s*(?:例如|比如|示例|样例|举例|假设|假定|假如|设想|example|sample|suppose|assuming)");
    private static final Pattern UNSAFE = Pattern.compile(
            "(?i)(忽略.{0,12}指令|系统提示|开发者消息|提示词|模型指令|api.?key|password|token|\\.env|id_rsa|\\.pem|\\.key"
                    + "|ignore\\s+(?:all\\s+)?(?:previous|prior|above|system|developer)\\s+(?:instructions?|messages?|rules?)"
                    + "|system\\s+prompt|developer\\s+message)");
    private static final Pattern RETENTION_SUFFIX = Pattern.compile(
            "^(?:优化|最终)?提示词(?:中)?(?:必须|应当|需要|应)?保留[^。；;]+$");
    private final SensitiveValueDetector sensitiveValueDetector = new SensitiveValueDetector();

    /**
     * 只返回原文中同时写明代号与规则的完整子句，由调用方沿用既有事实数量预算。
     * 相同代号的不同资料表述分别保留，不推断数字、合并边界或自行裁决冲突。
     */
    List<Evidence> extract(ContextSnapshot context, String query, int maxCharacters) {
        if (context == null || query == null) return List.of();
        String affirmativeQuery = query.replaceAll(
                "(?i)(?:不得|不要|禁止|不能|避免|不应|无需|不需要|do not|don't)[^。；;！!？?\\n]*", " ");
        if (!RETENTION_REQUEST.matcher(affirmativeQuery).find()) return List.of();
        // “保留规则代号”本身不能让所有带“规则”的文件变相关；用其余任务目标核对业务正文。
        String taskQuery = RETENTION_REQUEST.matcher(affirmativeQuery).replaceAll(" ")
                .replaceAll(RULE_LABEL, " ");
        PlanningEvidencePolicy taskPolicy = new PlanningEvidencePolicy(taskQuery);
        PlanningEvidencePolicy sourcePolicy = new PlanningEvidencePolicy(query);
        List<Evidence> result = new ArrayList<>();
        for (FileSnippet file : PlanningDigestSelector.select(context.fileSnippets(), query,
                context.fileSnippets().size())) {
            if (!documentSource(file) || !sourcePolicy.allows(file)) continue;
            for (String line : sourceEvidenceLines(file, sourcePolicy)) {
                String value = line.trim();
                if (value.length() > maxCharacters || unsafe(value)) continue;
                String businessContent = businessContent(value);
                if (businessContent == null || !explicitBoundary(businessContent)
                        || !taskPolicy.relevantBusinessContent(businessContent)) continue;
                result.add(new Evidence(file, value));
            }
        }
        return List.copyOf(result);
    }

    /** 再次检索时核对整句规则，避免分号拆句使已绑定的完整限制条件被误判为不在原文中。 */
    boolean containsSourceEvidence(FileSnippet file, String query, String evidence) {
        String businessContent = businessContent(evidence);
        if (!documentSource(file) || businessContent == null || !explicitBoundary(businessContent)
                || unsafe(evidence)) return false;
        PlanningEvidencePolicy policy = new PlanningEvidencePolicy(query);
        return policy.allows(file) && sourceEvidenceLines(file, policy).contains(evidence);
    }

    /**
     * 支持“代号表示边界”和“边界；规则代号为 X”两种同句引用，全文仍按原样保存。
     * 不跨句寻找数字；同句出现多个代号标签时不猜测哪个含义属于哪个代号。
     */
    private String businessContent(String evidence) {
        if (evidence == null || ILLUSTRATIVE_CONTEXT.matcher(evidence).find()
                || RULE_LABEL_PATTERN.matcher(evidence).results().count() != 1) return null;
        var forward = RULE.matcher(evidence);
        if (forward.matches()) return forward.group(2);
        var reverse = RULE_AFTER_MEANING.matcher(evidence);
        if (!reverse.matches()) return null;
        return reverse.group(1) + (reverse.group(3) == null ? "" : "；" + reverse.group(3));
    }

    /** 相对时间与明确动作共同构成时限规则；时间必须在业务原文中出现，不能从代号推算。 */
    private boolean explicitBoundary(String value) {
        if (BOUNDARY.matcher(value).find()) return true;
        var time = RELATIVE_TIME.matcher(value);
        while (time.find()) {
            // “活动开始”仅是时间参照；不能拿其中的“开始”伪造一个没有写明的业务动作。
            if (BOUNDED_ACTION.matcher(value.substring(time.end())).find()
                    || ACTION_BEFORE_TIME.matcher(value.substring(0, time.start())).find()) return true;
        }
        return false;
    }

    /**
     * 分号后的适用范围仍属于规则证据，不能因预算或拆句丢掉。
     * 仅移除句尾单独的“提示词保留”指令子句；其他可疑指令使整句不具备提取资格。
     */
    private List<String> sourceEvidenceLines(FileSnippet file, PlanningEvidencePolicy policy) {
        return policy.contextText(file).lines().flatMap(line -> Arrays.stream(line.split("。")))
                .map(String::trim).map(value -> {
                    int delimiter = Math.max(value.lastIndexOf('；'), value.lastIndexOf(';'));
                    if (delimiter >= 0 && RETENTION_SUFFIX.matcher(value.substring(delimiter + 1).trim()).matches()) {
                        return value.substring(0, delimiter).trim();
                    }
                    return value;
                }).filter(value -> !value.isBlank()).toList();
    }

    /** 专用保留路径不从源码、示例、测试夹具或报告中提升业务事实。 */
    private boolean documentSource(FileSnippet file) {
        if (file == null || file.path() == null || file.path().isBlank() || file.path().length() > 256
                || file.path().matches(".*[\\r\\n].*") || file.content() == null || unsafe(file.path())) return false;
        // 二次检索未命中时只剩绑定路径；片段后缀不能令原文档被误识别为未知材料。
        String sourcePath = file.path().replaceFirst("(?i)#chunk-\\d+$", "");
        PlanningFactOrigin origin = PlanningEvidencePolicy.origin(sourcePath, file.language());
        return origin == PlanningFactOrigin.PROJECT_DOCUMENT || origin == PlanningFactOrigin.USER_MATERIAL;
    }

    private boolean unsafe(String value) {
        return UNSAFE.matcher(value).find() || sensitiveValueDetector.containsCredential(value);
    }

    /** 来源与逐字证据一起传递，禁止只凭标识符拼接推断出的规则。 */
    record Evidence(FileSnippet file, String text) { }
}
