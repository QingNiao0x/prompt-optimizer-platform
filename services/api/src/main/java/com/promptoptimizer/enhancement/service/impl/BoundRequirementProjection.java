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
    private record Entry(Slot slot, ExecutionRequirement requirement, boolean pending) {
        Entry(Slot slot, ExecutionRequirement requirement) { this(slot, requirement, false); }
    }

    /**
     * 同一次组装的来源呈现记录。只有服务端完整展开的原句才登记；模型的近义改写不取得覆盖证明。
     * 后续协调步骤可能改动段落，消费记录前仍须确认原句留在原责任段的当前执行位置。
     */
    record Expansion(Set<ExecutionRequirement> delivered, List<Slot> ruleSlots, List<Slot> pendingSlots) {
        Expansion {
            delivered = Set.copyOf(delivered);
            ruleSlots = List.copyOf(ruleSlots);
            pendingSlots = List.copyOf(pendingSlots);
        }

        /** 条件、否定与例外作为完整来源保留，不按主题词或标识本身省去其他要求。 */
        Set<String> coveredRules(Map<PromptSectionType, PromptSection> sections) {
            return ruleSlots.stream().filter(slot -> stillPresent(sections.get(slot.destination()), slot))
                    .flatMap(slot -> java.util.stream.Stream.concat(java.util.stream.Stream.of(slot.statement()),
                            slot.destination() == PromptSectionType.OUTPUT
                                    ? ExplicitOutputRequirements.rulesOf(List.of(slot.statement())).stream()
                                    : java.util.stream.Stream.empty()))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        /** 已呈现未决状态只省去正文复写；API待决清单、问题身份和原始题答不变。 */
        Set<String> coveredPending(Map<PromptSectionType, PromptSection> sections) {
            return pendingSlots.stream().filter(slot -> stillPresent(sections.get(slot.destination()), slot))
                    .map(Slot::statement).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        /** 用原引用检查当前位置的外部作用域；来源句内部的条件不应被误当成引用外部条件。 */
        static boolean stillPresent(PromptSection section, Slot slot) {
            if (section == null) return false;
            int offset = section.content().indexOf(slot.statement());
            while (offset >= 0) {
                String substituted = section.content().substring(0, offset) + slot.reference()
                        + section.content().substring(offset + slot.statement().length());
                if (AdditionalRuleProjection.permitsSourceReference(
                        new PromptSection(section.type(), section.title(), substituted), slot.reference(), offset)) return true;
                offset = section.content().indexOf(slot.statement(), offset + slot.statement().length());
            }
            return false;
        }
    }

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
        return expandTracked(sections, task, constraints).delivered();
    }

    /** 原文规则、任务规则与Plan执行要求共用登记；无引用的旧响应仍保守核对完整表达。 */
    static Expansion expandTracked(Map<PromptSectionType, PromptSection> sections,
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
                    // 来源未丢失：失败来自模型安排的引用位置或格式，应进入已有协议修复而不伪装确认丢失。
                    // 仍先拒绝全部响应，不自动拆行或展开条件／否定中的引用，以免改变来源的适用范围。
                    throw new ProviderResponseValidationException(
                            ProviderResponseValidationException.Reason.REQUIREMENT_REFERENCE_INVALID,
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
        return new Expansion(delivered, entries.stream().filter(entry -> entry.requirement() == null && !entry.pending()
                && rendered.contains(entry.slot().reference())).map(Entry::slot).toList(),
                entries.stream().filter(entry -> entry.pending() && rendered.contains(entry.slot().reference()))
                        .map(Entry::slot).toList());
    }

    /** 引用绑定完整来源、对象和责任段；同值的不同题目继续使用不同标识。 */
    private static List<Entry> entries(EffectiveTaskView task, List<String> rules) {
        var entries = new ArrayList<Entry>();
        var outputs = ExplicitOutputRequirements.declarations(task.effectivePrompt());
        // 整块输出声明含字段、条件和格式；仍由同一引用机制保真，但不进入约束责任段。
        for (String output : outputs) entries.add(new Entry(new Slot(reference("user-output\n" + output), ExplicitOutputRequirements.bodyOf(output),
                PromptSectionType.OUTPUT), null));
        for (var requirement : task.executionRequirements()) {
            String identity = "answer\n" + requirement.questionId() + "\n" + requirement.question()
                    + "\n" + requirement.subject() + "\n" + requirement.destination() + "\n" + requirement.statement();
            entries.add(new Entry(new Slot(reference(identity), requirement.statement(), requirement.destination()), requirement));
        }
        for (String rule : rules.stream().distinct().toList()) {
            entries.add(new Entry(new Slot(reference("task-rule\n" + rule), rule, PromptSectionType.CONSTRAINTS), null));
        }
        // 原文明确规则沿用已确认状态协调后的现有提取器，不再让另一条追加路径独立猜测覆盖。
        // 相同完整规则可共用呈现；原始输入、题答及权限分组继续保留全部来源。
        var knownRules = new HashSet<>(rules);
        knownRules.addAll(ExplicitOutputRequirements.rulesOf(outputs));
        for (String rule : new RequirementFidelityGuard().explicitRules(task.effectivePrompt(), task.confirmedDecisions())) {
            if (knownRules.add(rule)) entries.add(new Entry(new Slot(reference("user-rule\n" + rule), rule,
                    PromptSectionType.CONSTRAINTS), null));
        }
        // 已绑定的未决条件使用归并器的同一完整表述；引用不能把UNRESOLVED升级成已选参数。
        if (!task.pendingDecisions().isEmpty()) {
            var decisions = ConfirmedDecisionSet.fromBoundDecisions(task.confirmedDecisions());
            var pending = new PlanAmbiguityMerger(decisions, task.originalPrompt())
                    .merge(List.of(), List.of(), List.of()).executionPrerequisites();
            for (String statement : pending) entries.add(new Entry(new Slot(reference("pending\n" + statement),
                    statement, PromptSectionType.CONSTRAINTS), null, true));
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
