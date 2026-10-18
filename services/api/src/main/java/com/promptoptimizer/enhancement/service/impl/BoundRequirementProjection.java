package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.EffectiveTaskView;
import com.promptoptimizer.enhancement.domain.EffectiveTaskView.ExecutionRequirement;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.policy.domain.ConstraintBundle;
import com.promptoptimizer.provider.domain.EnhancementProviderRequest;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 服务端来源要求的单次呈现：模型安排位置，原始确认／任务规则由本次请求展开。
 * 引用不是语义等价证明；仅接受责任段内独立当前行，不接受模型自填的取值、条件或权限。
 * 缺省引用沿用既有保真兜底，原题答、来源及固定权限规则均不删除。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class BoundRequirementProjection {
    private static final Pattern REFERENCE = Pattern.compile("\\{\\{REQ_[^}\\r\\n]{1,80}}}");

    /** 模型只用reference安排独立行；statement来自服务端有效来源，不由模型返回。 */
    public record Slot(String reference, String statement, PromptSectionType destination) { }
    private record Entry(Slot slot, ExecutionRequirement requirement) { }

    private BoundRequirementProjection() { }

    /** 旧扁平Provider请求复用既有分组识别；固定权限不开放为可省略的模型引用。 */
    public static List<Slot> forProvider(EnhancementProviderRequest request) {
        var task = EffectiveTaskViewFactory.forProvider(request);
        var rules = ConstraintBundle.fromLegacy(request.constraints(), task.intent().engineeringConstraints());
        return entries(task, rules.taskSpecific()).stream().map(Entry::slot).toList();
    }

    /**
     * 先核对全部引用，再原地展开；重复的同一来源引用仅呈现一次，不剪裁其他模型正文。
     * 返回已落实的原始执行要求供确认融合复用，避免再次依赖模型改写后的句法。
     */
    static Set<ExecutionRequirement> expand(Map<PromptSectionType, PromptSection> sections,
            EffectiveTaskView task, ConstraintBundle constraints) {
        var entries = entries(task, constraints.taskSpecific());
        var byReference = new java.util.LinkedHashMap<String, Entry>();
        entries.forEach(entry -> byReference.put(entry.slot().reference(), entry));
        for (var section : sections.values()) {
            var matcher = REFERENCE.matcher(section.content());
            while (matcher.find()) {
                String reference = matcher.group();
                var entry = byReference.get(reference);
                if (entry == null || entry.slot().destination() != section.type()
                        || !AdditionalRuleProjection.permitsSourceReference(section, reference, matcher.start())) {
                    throw new ProviderResponseValidationException(
                            ProviderResponseValidationException.Reason.CONFIRMED_DECISION_MISSING,
                            "sections." + section.type() + ".requirementReference");
                }
            }
        }
        Set<String> rendered = new HashSet<>();
        Set<ExecutionRequirement> delivered = new HashSet<>();
        sections.replaceAll((type, section) -> {
            var matcher = REFERENCE.matcher(section.content());
            var text = new StringBuilder();
            while (matcher.find()) {
                var entry = byReference.get(matcher.group());
                String replacement = rendered.add(matcher.group()) ? entry.slot().statement() : "";
                if (entry.requirement() != null) delivered.add(entry.requirement());
                matcher.appendReplacement(text, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(text);
            return new PromptSection(type, section.title(), text.toString());
        });
        return Set.copyOf(delivered);
    }

    /** 引用绑定完整来源、对象和责任段；同值的不同题目继续使用不同标识。 */
    private static List<Entry> entries(EffectiveTaskView task, List<String> rules) {
        var entries = new ArrayList<Entry>();
        for (var requirement : task.executionRequirements()) {
            String identity = "answer\n" + requirement.questionId() + "\n" + requirement.question()
                    + "\n" + requirement.subject() + "\n" + requirement.destination() + "\n" + requirement.statement();
            entries.add(new Entry(new Slot(reference(identity), requirement.statement(), requirement.destination()), requirement));
        }
        for (String rule : rules.stream().distinct().toList()) {
            entries.add(new Entry(new Slot(reference("task-rule\n" + rule), rule, PromptSectionType.CONSTRAINTS), null));
        }
        return List.copyOf(entries);
    }

    /** 标识只用于请求内定位，不输出原题或材料内容，也不充当鉴权或保真依据。 */
    private static String reference(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return "{{REQ_" + HexFormat.of().formatHex(digest, 0, 8) + "}}";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }
}
