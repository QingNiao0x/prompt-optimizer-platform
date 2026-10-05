package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 为已经绑定的未决子项生成完整对象签名，仅归一化语法，不以行业或事实类别判断同一决定。
 * 跨题合并必须有具名子项；继承对象和条件仍参与签名，不能让不同医院的相同属性相互覆盖。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PendingReminderIdentity {
    private static final Pattern OWNER = Pattern.compile("^(?:对于|关于)?([\\p{IsHan}A-Za-z0-9_]{1,20}?"
            + "(?:医院|机构|公司|部门|工作区|租户|研究组|问卷|评分表))");
    private final ConfirmedDecisionSet decisions;

    PendingReminderIdentity(ConfirmedDecisionSet decisions) {
        this.decisions = decisions;
    }

    /** 只为完整具名子项提供跨题键；裸未知与依赖原题的泛指子项继续保留 questionId。 */
    Optional<String> namedKey(ConfirmedPlanDecision pending) {
        String subject = subject(pending.question(), pending);
        if (subject.length() < 4 || subject.matches("^(?:其余|其他|该|这|它).*")) return Optional.empty();
        if (!PlanAnswerSemantics.namesPendingSubject(pending.question())) return Optional.empty();
        return Optional.of(owner(pending) + "\u0000" + subject);
    }

    /** 继承的明确对象需要同时展示；否则不同对象虽有不同键，用户仍会看到两份相同文案。 */
    String owner(ConfirmedPlanDecision pending) {
        String bound = boundOwner(pending);
        return bound.isEmpty() || compact(pending.question()).contains(bound) ? "" : bound;
    }

    /** 范围来自同一绑定原题；展示是否省略对象不改变内部所有权核对。 */
    private String boundOwner(ConfirmedPlanDecision pending) {
        List<String> owners = originals(pending).stream().map(original -> {
            var context = Pattern.compile("^(?:对于|关于)([^，,。；;]{2,120})[，,]").matcher(compact(original.question()));
            if (context.find()) return context.group(1);
            var matcher = OWNER.matcher(original.question());
            if (matcher.find()) return matcher.group(1);
            var propertyOwner = Pattern.compile("^(?:对于|关于)?([^，,。；;？?]{2,30}?)(?:的)?"
                    + "(?:评分尺度|评分表|观察窗口|审批标准|退款标准|统计口径)").matcher(original.question());
            return propertyOwner.find() && !propertyOwner.group(1)
                    .matches(".*(?:采用|使用|哪|如何|怎样|是否|希望|之间|应).*" ) ? propertyOwner.group(1) : "";
        // 问句中的已确认区间/选择过程不是对象名称；不能把它继承回来再次标成未决。
        }).filter(value -> !value.isEmpty() && !value.matches(
                ".*(?:是否|如何|怎样|哪|采用|使用|检查区间).*"))
                .distinct().toList();
        return owners.size() == 1 ? owners.getFirst() : "";
    }

    /** 未决断言必须保留全部属性和条件；完整已知前缀只有在已确认回答或同题范围中有出处时移开。 */
    boolean matches(String heading, ConfirmedPlanDecision pending) {
        if (heading.matches("(?s).*(?:另外|此外|另需|还需|同时还).*")) return false;
        if (!compatibleOwner(heading, pending)) return false;
        String candidate = scopedSubject(heading, pending);
        String known = scopedSubject(pending.question(), pending);
        if (!candidate.isEmpty() && !known.isEmpty()) return candidate.equals(known);
        // 只有完整的观察对象与属性吻合才兼容提问语序，不按“观察窗口”一个主题删题。
        return questionSubject(heading).equals(questionSubject(pending.question()))
                && questionSubject(heading).length() >= 6;
    }

    /**
     * 复合题须逐段完全覆盖已登记的具名属性，连接词之外不留任何对象、条件或取值残余。
     * 每一段仍用自身原题核对范围；不能因为两个属性同属一个行业便将整题丢弃。
     */
    List<ConfirmedPlanDecision> compositeMatches(String heading) {
        if (heading.matches("(?s).*(?:另外|此外|另需|还需|同时还).*")) return List.of();
        List<ConfirmedPlanDecision> pending = decisions.pendingDecisions();
        for (ConfirmedPlanDecision anchor : pending) {
            if (!compatibleOwner(heading, anchor)) continue;
            String candidate = scopedSubject(heading, anchor);
            if (candidate.isEmpty()) continue;
            var available = new java.util.LinkedHashMap<String, ConfirmedPlanDecision>();
            for (ConfirmedPlanDecision part : pending) {
                if (!compatibleOwner(heading, part) || !candidate.equals(scopedSubject(heading, part))) continue;
                String known = scopedSubject(part.question(), part);
                if (!known.isEmpty()) available.putIfAbsent(known, part);
            }
            List<ConfirmedPlanDecision> matched = coverSubjects(candidate, available, List.of());
            if (matched.size() > 1) return matched;
        }
        return List.of();
    }

    /** 完整属性最长优先；同一属性不能重复占位，新增条件和比较符无法被连接词消费。 */
    private List<ConfirmedPlanDecision> coverSubjects(String remaining,
            java.util.Map<String, ConfirmedPlanDecision> available, List<ConfirmedPlanDecision> matched) {
        if (remaining.isEmpty()) return matched;
        for (var entry : available.entrySet().stream()
                .sorted(java.util.Comparator.comparingInt((java.util.Map.Entry<String, ConfirmedPlanDecision> item)
                        -> item.getKey().length()).reversed()).toList()) {
            if (matched.contains(entry.getValue()) || !remaining.startsWith(entry.getKey())) continue;
            String tail = remaining.substring(entry.getKey().length());
            if (!tail.isEmpty() && !tail.matches("^(?:以及|和|与|及).+")) continue;
            tail = tail.replaceFirst("^(?:以及|和|与|及)", "");
            var next = new java.util.ArrayList<>(matched);
            next.add(entry.getValue());
            List<ConfirmedPlanDecision> result = coverSubjects(tail, available, List.copyOf(next));
            if (!result.isEmpty()) return result;
        }
        return List.of();
    }

    /** 显式的新机构不得借同类别的已知前缀进入另一机构；隐含范围继续参与完整主题比较。 */
    private boolean compatibleOwner(String text, ConfirmedPlanDecision pending) {
        var candidate = OWNER.matcher(compact(text));
        if (!candidate.find()) return true;
        String bound = boundOwner(pending);
        var direct = OWNER.matcher(compact(pending.question()));
        if (bound.isEmpty() && direct.find()) bound = direct.group(1);
        return bound.isEmpty() || bound.equals(candidate.group(1));
    }

    /**
     * 只消除原题逐字命名的范围前缀，并核对原题的事件—动作语序；不移开模型新增的对象。
     * 例如原题明确问“两名评审的分歧”，其回答的“分歧处理流程”可继承该范围。
     */
    private String scopedSubject(String text, ConfirmedPlanDecision pending) {
        String value = subject(text, pending);
        for (ConfirmedPlanDecision original : originals(pending)) {
            String question = compact(original.question());
            // 定义题已完整命名的范围可修饰同题子项；例如“两组公平对照”的给法。
            // 仅消费原题逐字范围，不借类别或其他题的对象，也不移开新条件和尾部未知。
            var definition = Pattern.compile("^([^，,。；;？?]{2,50}?)(?:具体)?(?:是指什么|指什么|是什么)[？?]$")
                    .matcher(question);
            if (definition.matches()) {
                String prefix = definition.group(1) + "的";
                if (value.startsWith(prefix)) value = value.substring(prefix.length());
            }
            int possessive = question.indexOf('的');
            if (possessive >= 2 && possessive <= 40) {
                String prefix = question.substring(0, possessive + 1);
                if (value.startsWith(prefix)) value = value.substring(prefix.length());
            }
            var event = Pattern.compile("^([^，,。；;？?]{2,40})出现([^，,。；;？?]{2,20})时如何([^，,。；;？?]{1,12})[，,？?]")
                    .matcher(question);
            if (event.find()) {
                String prefix = event.group(1) + "出现" + event.group(2) + "时的";
                if (value.startsWith(prefix + event.group(3))) value = event.group(2) + value.substring(prefix.length());
                String actor = event.group(1) + "的";
                if (value.startsWith(actor)) value = value.substring(actor.length());
            }
            // “交互”仅在原题已明确同一交互式计划范围时是轮次的重复限定；其他任务的轮次不改。
            if (question.startsWith("交互式计划确认的")) {
                value = value.replaceFirst("^最大交互轮次", "最大轮次");
            }
        }
        return value;
    }

    /** 只在原题明确写出的范围前缀上归一化，不将模型新加的对象或条件解释为同题。 */
    private String subject(String text, ConfirmedPlanDecision pending) {
        String normalized = compact(text);
        String subject = PlanAnswerSemantics.pendingSubject(normalized);
        if (subject.isEmpty()) return "";
        for (ConfirmedPlanDecision original : originals(pending)) {
            var context = Pattern.compile("^(?:对于|关于)([^，,。；;]{2,120})[，,]").matcher(compact(original.question()));
            if (context.find()) subject = subject.replaceFirst("^(?:对于|关于)"
                    + Pattern.quote(context.group(1)) + "[，,]", "");
        }
        var prefix = Pattern.compile("^([^，,]+)[，,](?:但)?(.+)$").matcher(subject);
        if (prefix.matches()) {
            String known = decisions.knownDecisions().stream()
                    .filter(value -> java.util.Objects.equals(value.questionId(), pending.questionId()))
                    .map(value -> compact(value.answer()))
                    .collect(java.util.stream.Collectors.joining("\u0000"));
            if (prefix.group(1).contains("采用") && (known.contains(prefix.group(1))
                    || sameBoundAffirmativePrefix(prefix.group(1), pending))) subject = prefix.group(2);
        }
        // 只是未决等级/锚点的语法差异；“一致性指标”“计分权重”等其他属性仍完整保留。
        subject = subject.replaceFirst("^(?:具体|评分)(?=等级(?:和|与|及)评分锚点)", "");
        return subject.replace("等级与评分锚点", "等级及评分锚点")
                .replace("等级和评分锚点", "等级及评分锚点")
                .replace("等级以及评分锚点", "等级及评分锚点");
    }

    /**
     * 回答以另一未决项开头时，confirmedPart 不会提取后续肯定前缀；仅核对同题完整原子句。
     * “若采用/不采用/建议采用”等条件、否定或候选表达不作为已知前缀，不能借其他题的答案。
     */
    private boolean sameBoundAffirmativePrefix(String prefix, ConfirmedPlanDecision pending) {
        if (!prefix.contains("采用") || PlanAnswerSemantics.unresolved(prefix)
                || prefix.matches(".*(?:不采用|未采用|若|如果|假如|是否|建议|可能|或者|还是).*")) return false;
        return originals(pending).stream().flatMap(original -> java.util.Arrays.stream(
                compact(original.answer()).split("[。；;\\r\\n]+")))
                .anyMatch(clause -> clause.startsWith(prefix + "，") || clause.startsWith(prefix + ","));
    }

    /** 医院同一预约未到诊对象的已给定别名可换写；其他括注、数值和条件一概保留。 */
    private String questionSubject(String text) {
        return compact(text).replaceFirst("^对于", "")
                .replace("预约后未到诊（爽约）", "预约未到诊")
                .replace("预约后未到诊(爽约)", "预约未到诊")
                .replace("的判定，", "的").replace("的判定,", "的")
                .replaceFirst("(?:应如何|如何)(?:设定|定义|确定)[？?]?$", "")
                .replaceAll("[。？?]+$", "");
    }

    private List<ConfirmedPlanDecision> originals(ConfirmedPlanDecision pending) {
        return decisions.decisions().stream().filter(original -> java.util.Objects.equals(
                original.questionId(), pending.questionId())).toList();
    }

    private String compact(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", "");
    }
}
