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
    private final String rawPrompt;
    /** 重用当前具名未决参数；在本次归并器内计算一次，不按每条提醒重新抽取。 */
    private final UnresolvedDecisionContract parameterContract;

    PendingReminderIdentity(ConfirmedDecisionSet decisions) {
        this(decisions, "");
    }

    PendingReminderIdentity(ConfirmedDecisionSet decisions, String rawPrompt) {
        this.decisions = decisions;
        this.rawPrompt = rawPrompt == null ? "" : rawPrompt;
        this.parameterContract = UnresolvedDecisionContract.from(this.rawPrompt, decisions, List.of());
    }

    /** 复用本次登记的独立参数，只有全部单项仍在清单里才精简纯概述。 */
    List<String> withoutCoveredSummaries(List<String> reminders) {
        return parameterContract.withoutCoveredSummaries(reminders);
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
        var parameterMatch = boundParameterMatch(heading, pending);
        if (parameterMatch.isPresent()) return parameterMatch.get();
        if (sameUnverifiedCoverage(heading, pending)) return true;
        if (sameBoundImputationPart(heading, pending)) return true;
        if (PendingDecisionSignature.same(heading, pending.question(), rawPrompt)) return true;
        // 未决子项可以省去原题的活动范围，但不能省掉对象或属性后借用另一个子项的身份。
        if (originals(pending).stream().anyMatch(original ->
                PendingDecisionSignature.primaryPartOfOriginal(pending.question(), original.question())
                        && PendingDecisionSignature.same(heading, original.question(), rawPrompt))) return true;
        String candidate = scopedSubject(heading, pending);
        String known = scopedSubject(pending.question(), pending);
        if (!candidate.isEmpty() && !known.isEmpty()) return candidate.equals(known);
        // 只有完整的观察对象与属性吻合才兼容提问语序，不按“观察窗口”一个主题删题。
        return questionSubject(heading).equals(questionSubject(pending.question()))
                && questionSubject(heading).length() >= 6;
    }

    /** 完整具名参数的否定匹配同样约束旧解释回退，不能让公共提醒在后续分支借用机构身份。 */
    java.util.Optional<Boolean> boundParameterMatch(String heading, ConfirmedPlanDecision pending) {
        return parameterContract.matchesBoundIndependentParameter(heading, pending.question());
    }

    /** 三项未决中的插补子项可以单独复述；只归并原题已具名的对象，不删除新的方法或人群。 */
    private boolean sameBoundImputationPart(String heading, ConfirmedPlanDecision pending) {
        if (!subject(pending.question(), pending).equals("是否插补、适用指标及方法")) return false;
        var matcher = Pattern.compile("^是否(对([^，,、。；;]{2,30})(?:使用|采用|进行)插补)"
                + "(?:尚未|仍未|未)(?:决定|确定)[。？?]?$").matcher(compact(heading));
        if (!matcher.matches()) return false;
        boolean bound = originals(pending).stream().map(original -> compact(original.question()))
                .anyMatch(question -> question.contains(matcher.group(1))
                        || question.contains(matcher.group(2) + "是否采用插补"));
        if (bound) return true;
        // 泛指题只能继承原需求唯一明示的插补对象；多个对象或模型新增对象均不借用同一确认。
        String raw = compact(rawPrompt);
        List<String> objects = Pattern.compile("对([^，,、。；;]{2,30})(?:使用|采用|进行)插补")
                .matcher(raw).results().map(match -> match.group(1)).distinct().toList();
        return objects.size() == 1 && objects.getFirst().equals(matcher.group(2))
                && raw.contains(matcher.group(1));
    }

    /** 同一医院、年份和覆盖属性须有绑定回答作证；未核实不是确认事实，也不能借别的年份合并。 */
    boolean sameUnverifiedCoverage(String heading, ConfirmedPlanDecision pending) {
        var candidate = coverage(heading);
        if (candidate.isEmpty()) return false;
        for (ConfirmedPlanDecision original : originals(pending)) {
            if (original.scope() != ConfirmedPlanDecision.Scope.UNRESOLVED) continue;
            String subtype = candidate.get().subtype();
            if (!subtype.isEmpty() && !original.question().contains(subtype)) continue;
            for (String clause : compact(original.answer()).split("[，,。；;]+")) {
                var known = coverage(clause);
                if (known.isPresent() && candidate.get().object().equals(known.get().object())
                        && (subtype.equals(known.get().subtype()) || subtype.isEmpty()
                        || known.get().subtype().isEmpty() && original.question().contains(subtype))) return true;
            }
        }
        return false;
    }

    /** 只识别完整的具名医院、年度覆盖状态；新增对象或条件不通过宽泛“覆盖度”主题归并。 */
    boolean namesUnverifiedCoverage(String text) {
        return coverage(text).isPresent();
    }

    /** 拆成三项的插补复述须与同题已登记的三个未决属性完全对应，剩余说明交由归并器保留。 */
    Optional<String> repeatedResearchStatement(String text, ConfirmedPlanDecision pending) {
        if (!subject(pending.question(), pending).equals("是否插补、适用指标及方法")) return Optional.empty();
        var matcher = Pattern.compile("^(是否[^，,。；;]{2,80}插补)(?:仍|尚)?未(?:决定|确定)[，,]"
                + "适用(?:哪些)?指标(?:和|及)方法(?:均|都)?(?:尚)?未(?:确定|决定)[；;。]?(.*)$")
                .matcher(compact(text));
        if (!matcher.matches()) return Optional.empty();
        // 业务对象仍逐字核对，例如缺失病例与新生儿病例不能相互替代。
        boolean sameQuestion = originals(pending).stream().anyMatch(original ->
                compact(original.question()).replaceAll("[？?。]+$", "").equals(matcher.group(1)));
        return sameQuestion ? Optional.of(matcher.group(2)) : Optional.empty();
    }

    /** 核实状态保留在字段签名之外；签名包含完整机构、年度与明示文档子类。 */
    private Optional<CoverageIdentity> coverage(String text) {
        var matcher = Pattern.compile("^([\\p{IsHan}A-Za-z0-9_]{1,25}?医院\\d{4}年)(?:的)?"
                + "(出院病案首页)?(?:的)?(?:数据)?覆盖度(?:尚未|仍未|未)(?:核实|确认|明确|确定)[。？?]?$")
                .matcher(compact(text));
        return matcher.matches() ? Optional.of(new CoverageIdentity(matcher.group(1),
                matcher.group(2) == null ? "" : matcher.group(2))) : Optional.empty();
    }

    /** 年份随机构名一起参与身份比较，文档子类只允许从同题显式范围继承。 */
    private record CoverageIdentity(String object, String subtype) { }

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
        subject = researchSubject(subject, pending);
        // 只是未决等级/锚点的语法差异；“一致性指标”“计分权重”等其他属性仍完整保留。
        subject = subject.replaceFirst("^(?:具体|评分)(?=等级(?:和|与|及)评分锚点)", "");
        return subject.replace("等级与评分锚点", "等级及评分锚点")
                .replace("等级和评分锚点", "等级及评分锚点")
                .replace("等级以及评分锚点", "等级及评分锚点");
    }

    /**
     * 科研子项只归一化已绑定题目中的完整属性语法；医院、指标种类和新增条件不删除。
     * 部分确认的完整性分母不能消除一致性分母，只移开同题已有肯定答案的展示前缀。
     */
    private String researchSubject(String subject, ConfirmedPlanDecision pending) {
        var partial = Pattern.compile("^(?:仅|只)确认完整性分母[，,](.+)$").matcher(subject);
        if (partial.matches() && originals(pending).stream().anyMatch(original ->
                PlanAnswerSemantics.confirmedPart(original.answer()).matches(
                        "(?s).*完整性指标分母(?:采用|包含|包括|为).+"))) subject = partial.group(1);
        // 用户在原需求中明确命名跨字段一致性，同题简写的一致性不能扩大为完整性或另一个具名指标。
        if (rawPrompt.contains("跨字段一致性指标")) {
            // 转折只连接前面的已知完整性分母；完整属性以外的对象、条件和取值仍参与比较。
            String denominator = subject.replaceFirst("^(?:但|不过|然而)(?=(?:跨字段(?:逻辑)?)?一致性指标的?分母)", "");
            if (denominator.matches("^(?:跨字段(?:逻辑)?)?一致性指标的?分母(?:是否采用相同口径)?$")) {
                subject = "跨字段一致性指标分母";
            }
        }
        String original = originals(pending).stream().map(value -> compact(value.question()))
                .collect(java.util.stream.Collectors.joining("\u0000"));
        var imputation = Pattern.compile("对([^，,、。；;]{2,30})(?:使用|采用|进行)插补").matcher(subject);
        if (imputation.find() && (original.contains(imputation.group())
                || original.contains(imputation.group(1) + "是否采用插补")
                || original.matches("(?s).*(?:对于|对)" + Pattern.quote(imputation.group(1))
                        + "[，,]是否(?:使用|采用|进行)插补.*")
                || rawPrompt.contains(imputation.group()))) {
            subject = subject.substring(0, imputation.start()) + "插补" + subject.substring(imputation.end());
        }
        // 只对应三项完整决策；只问方法、新增比例阈值或别的适用对象都不会落入此签名。
        return subject.replaceFirst("是否插补[、，,]适用(?:哪些)?指标(?:和|及)"
                + "(?:具体|采用何种)?方法(?:均|都)?$", "是否插补、适用指标及方法");
    }

    /**
     * 原题已明确询问指标或字段时，完整三项插补复写可把字段范围附到同一题，不丢新增字段。
     * 仅投影题干用于核对完整对象；新对象、方法取值、数字条件或题外字段不进入此分支。
     */
    Optional<String> imputationFieldExpansion(String text, ConfirmedPlanDecision pending) {
        var expansion = Pattern.compile("^是否对([^，,、。；;]{2,30})(?:使用|采用|进行)插补、"
                + "适用哪些指标或字段、采用何种方法均未(?:决定|确定)[；;](.*)$", Pattern.DOTALL).matcher(text.strip());
        if (!expansion.matches() || originals(pending).stream()
                .noneMatch(original -> original.question().contains("哪些指标或字段")
                        && Pattern.compile("(?:对|对于)" + Pattern.quote(expansion.group(1))
                        + "(?=是否|[，,]|使用|采用|进行)").matcher(original.question()).find())) return Optional.empty();
        String projected = "是否对" + expansion.group(1) + "采用插补、适用指标及方法均未决定";
        if (!matches(projected, pending)) return Optional.empty();
        return Optional.of("若采用插补，适用哪些指标或字段尚未决定。" + expansion.group(2));
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
