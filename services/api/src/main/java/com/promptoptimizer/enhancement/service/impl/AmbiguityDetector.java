package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.common.exception.InvalidOptimizationRequestException;
import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 为 Mock 与旧 Provider 提供保守的上下文感知候选问题。
 * 真正的跨领域语义判断由增强 Provider 在同一次调用中完成；此处不按关键词缺失补齐固定清单。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class AmbiguityDetector {

    /**
     * 返回需要在增强结果中明确标注的待确认项。
     */
    public List<String> detect(String rawPrompt) {
        return detect(rawPrompt, null, List.of());
    }

    /**
     * 只使用分析器已经过滤、脱敏的实际内容；依赖名称和目录名本身不能证明业务规则。
     */
    public List<String> detect(String rawPrompt, ContextSnapshot context, List<ConversationMessage> conversation) {
        if (rawPrompt == null || rawPrompt.isBlank() || rawPrompt.length() > 8_000) {
            throw new InvalidOptimizationRequestException("原始提示词不能为空且不能超过 8,000 个字符。");
        }
        String prompt = rawPrompt.trim().toLowerCase(Locale.ROOT);
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, rawPrompt);
        // 写作引用登录、排序或研究案例不等于要求实现这些功能；明确的附带开发目标仍参与判断。
        boolean softwareDetails = intent.deliveryProfile() == TaskDeliveryProfile.GENERAL
                || intent.engineeringConstraints()
                || intent.auxiliaryProfiles().contains(TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        boolean analysisDetails = intent.templateCode() == TemplateCode.RESEARCH_ANALYSIS
                || intent.deliveryProfile() == TaskDeliveryProfile.GENERAL
                || intent.auxiliaryProfiles().contains(TaskDeliveryProfile.DATA_ANALYSIS);
        List<String> findings = new ArrayList<>();
        String scope = context == null ? ""
                : context.technologyStack().stream().map(item -> item.name()).limit(3)
                        .collect(Collectors.joining("、"));
        String project = scope.isBlank() ? "当前任务" : "已识别的 " + scope + " 项目";

        // 文书中的风险分级、优先级排序已有业务对象和次序，不能套用编程排序算法的问题。
        boolean explicitBusinessRanking = Pattern.compile(
                "(?:高[、,，/]中[、,，/]低|由高到低|由低到高|从高到低|从低到高).{0,12}(?:风险|重要性|优先级|紧急程度).{0,12}排序|"
                        + "(?:风险|重要性|优先级|紧急程度).{0,12}(?:由高到低|由低到高|从高到低|从低到高).{0,12}排序")
                .matcher(prompt).find();
        if ((softwareDetails || analysisDetails) && containsAny(prompt, "排序", "sort") && !explicitBusinessRanking) {
            String evidence = evidence(prompt, context, conversation, "排序", "sort");
            if (!containsAny(evidence, "整数", "数字", "字符串", "对象", "订单", "记录", "integer", "number", "string")) {
                findings.add(project + "需要对哪类数据排序（数字、文本或业务记录）？现有资料尚未明确排序对象。");
            }
            if (!containsAny(evidence, "升序", "降序", "从小到大", "从大到小", "ascending", "descending", ".sorted(", "order by")) {
                findings.add("本次排序按哪个字段、采用什么顺序？请确认比较规则，以便确定预期结果。");
            }
        }
        if (softwareDetails && containsAny(prompt, "登录", "login") && containsAny(prompt, "添加", "增加", "实现", "开发", "add", "implement")) {
            String evidence = evidence(prompt, context, conversation, "登录", "login", "认证", "auth", "security");
            // 只有明确要求或实现证据才说明认证方式；单独出现 JWT 依赖不足以证明登录已经采用 JWT。
            boolean explicit = Pattern.compile("(?:采用|使用|基于|沿用|use).{0,20}(?:jwt|session|oauth|现有|已有)")
                    .matcher(evidence).find();
            boolean implemented = containsAny(evidence, "jwts.builder(", "jwtdecoder", "getsession(", "httpsession", "oauth2login(");
            if (!explicit && !implemented) {
                findings.add(project + "的登录功能应采用哪种认证与会话方式？已提供的登录相关资料尚不足以确定现有机制。");
            }
        }
        if (analysisDetails && containsAny(prompt, "死亡率", "yll", "减寿", "arriaga")) {
            String evidence = evidence(prompt, context, conversation, "研究", "死亡", "yll", "arriaga", "地区", "数据");
            if (containsAny(prompt, "某地区", "某省", "某市")
                    && !Pattern.compile("(?:研究地区|研究范围|地区范围|覆盖地区|地区)\\s*[:：=]\\s*(?!某|待定|未知|未明确)[\\p{L}]{2,20}")
                    .matcher(evidence).find()) {
                findings.add("死亡率分析中的“某地区”具体指哪里？请明确研究覆盖的省、市或区域。");
            }
            if (containsAny(prompt, "yll", "减寿")
                    && !containsAny(evidence, "期望寿命表", "标准寿命表", "参考寿命", "life table")) {
                findings.add("本次 YLL／减寿分析采用哪份参考寿命表或标准寿命？该选择会影响减寿年数及不同人群的比较。");
            }
        }
        if (prompt.matches("(?:请|帮我|请帮我)?(?:优化一下|处理一下|改一下|搞一下)[。！!]?")) {
            findings.add("“" + rawPrompt.trim() + "”具体要改变哪个对象、达到什么效果？现有资料不能替代本次任务目标。");
        }
        return List.copyOf(findings);
    }

    /** 仅汇集与主题相关的资料和用户对话，并排除带否定或待定语义的句子。 */
    private String evidence(String prompt, ContextSnapshot context, List<ConversationMessage> conversation,
                            String... topic) {
        StringBuilder text = new StringBuilder(prompt);
        if (context != null) {
            text.append('\n').append(context.customDescription());
            context.fileSnippets().stream()
                    .filter(file -> containsAny((file.path() + "\n" + file.summary() + "\n" + file.content())
                            .toLowerCase(Locale.ROOT), topic))
                    .forEach(file -> text.append('\n').append(file.summary()).append('\n').append(file.content()));
        }
        if (conversation != null) {
            conversation.stream().filter(message -> "user".equals(message.role()))
                    .forEach(message -> text.append('\n').append(message.content()));
        }
        // 未决、否定和疑问句不是已知事实，不能用来消除候选问题。
        return Pattern.compile("[\\r\\n。；;，,]+").splitAsStream(text.toString().toLowerCase(Locale.ROOT))
                .filter(line -> !containsAny(line, "待定", "未明确", "不使用", "不采用", "不支持", "？", "?"))
                .collect(Collectors.joining("\n"));
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
