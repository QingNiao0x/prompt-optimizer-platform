package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope;
import com.promptoptimizer.provider.domain.AmbiguityReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 按本次绑定问题与冲突证据登记提醒，再合并模型补充；不改变索引、提问或确认答案。
 * 无法证明为同一提醒的文本继续保留，展示限额只在归并结束后应用。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PlanAmbiguityMerger {
    private static final int DISPLAY_LIMIT = 8;
    private static final Pattern CONFLICT = Pattern.compile(
            "资料对“([^”]+)”存在不同取值：(.+?)（([^）]+)）与 (.+?)（([^）]+)）");
    private static final Pattern EXAMPLE = Pattern.compile("[（(](?:如|例如|比如)[^（）()]*[）)]");
    private static final Pattern CONFIRMATION = words("该问题尚未确定", "存在不同取值", "冲突未解决",
            "尚未确认", "尚未确定", "尚未明确", "尚未提供", "未确认", "未确定", "未明确", "未提供",
            "未指定", "暂不确定", "未知", "冲突", "规定", "取值", "确认答案为", "答案为", "本次",
            "用户", "回答", "仍然", "仍", "需要", "必须", "已确认但", "已确认", "核对", "阈值", "需",
            "请", "确认", "明确", "采用", "哪一项", "为", "与");
    private static final Pattern QUESTION_GRAMMAR = words("该问题尚未确定", "尚未确认", "尚未明确", "尚未提供",
            "尚未确定", "未确认", "未明确", "未提供", "未指定", "未确定", "未知", "暂不确定",
            "从哪里获取", "具体", "主要", "本次", "这项", "需要", "哪些", "哪个", "什么", "是否",
            "请", "说明", "明确", "提供", "指定", "确认", "包括", "覆盖", "进行", "研究", "分析", "数据", "的", "是");
    private static final Pattern HEADER_GRAMMAR = words("应如何确定", "如何确定", "是否已经确定", "是否已确定",
            "应如何限定", "应如何处理", "如何处理", "应采用哪个口径", "采用哪个口径", "应采用哪种", "计算时", "计算",
            "是否需要", "是否使用", "尚未确定", "未确定", "已经确定", "是什么", "什么", "具体", "需要", "需", "？", "?");
    private static final Pattern PENDING_SUFFIX = Pattern.compile(
            "(?:仍然|目前|现在|仍|还)?(?:(?:尚未|暂未|未|没有|暂不)(?:确定|决定|明确|确认|选定|提供)|待(?:确认|确定|明确))[。？?]?$"
    );
    private static final Pattern REGISTRATION_QUESTION_SUFFIX = Pattern.compile("(?:应|应该)?如何预注册[？?]?$" );
    private static final Pattern SIMPLE_UNKNOWN = Pattern.compile(
            "^([^。；;：:？?]{4,120}?)(?:尚未明确|尚未确定|未明确|未确定|待确认|未知)$");
    private final ConfirmedDecisionSet decisions;
    private final PendingReminderIdentity pendingIdentity;
    /** 仅用于核对本次需求命名的对象，不输出或持久化原始提示词。 */
    private final String rawPrompt;

    PlanAmbiguityMerger(ConfirmedDecisionSet decisions) {
        this(decisions, "");
    }

    PlanAmbiguityMerger(ConfirmedDecisionSet decisions, String rawPrompt) {
        this.decisions = decisions;
        this.rawPrompt = rawPrompt == null ? "" : rawPrompt;
        this.pendingIdentity = new PendingReminderIdentity(decisions, rawPrompt);
    }

    /** 新冲突优先，其次为原始未决问题；外部引用不能单独触发删除。 */
    MergeResult merge(List<String> findings, List<String> serverFindings, List<AmbiguityReference> references) {
        Map<String, String> registered = new LinkedHashMap<>();
        List<ConflictIdentity> knownConflicts = new ArrayList<>();
        for (String text : serverFindings) {
            if (!text.startsWith("资料对“")) continue;
            var identity = conflict(text);
            identity.ifPresent(knownConflicts::add);
            registered.putIfAbsent(identity.map(ConflictIdentity::key).orElse("text:" + text), text);
        }
        for (ConfirmedPlanDecision decision : decisions.decisions()) {
            var identity = conflict(decision.question());
            if (decision.questionId() != null && decision.questionId().startsWith("context-conflict-")) {
                identity.ifPresent(knownConflicts::add);
            }
        }
        for (ConfirmedPlanDecision decision : decisions.pendingDecisions()) {
            var identity = conflict(decision.question());
            String key = identity.map(ConflictIdentity::key).orElse("question:"
                    + pendingKey(decision));
            String answerText = decision.answer().replaceFirst(
                    "(?i)^(?:暂不确定|尚未确定|待定|不知道|不清楚|unknown|tbd)[。.!！]\\s*", "");
            String explanation = answerText.isBlank() || answerText.matches("(?i)^(?:暂不确定|尚未确定|待定|不知道|不清楚|unknown|tbd)[。.!！]?$")
                    || answerText.equals(decision.question()) ? "" : " 用户说明：" + answerText;
            // 部分回答生成的具名未决子项本身就是答案开头，不把同一句再接一遍“用户说明”。
            String value = answerText.startsWith(decision.question()) ? answerText
                    : decision.question() + explanation;
            String owner = pendingIdentity.owner(decision);
            String scopedValue = (owner.isEmpty() ? "" : owner + "：") + value;
            if (registered.containsKey(key)) appendNovelExplanation(registered, key, scopedValue);
            else registered.put(key, "该问题尚未确定：" + scopedValue);
        }
        for (String finding : findings) {
            String text = normalizeBoundAnswerPresentation(finding);
            if (nonDecisionStatement(text)) continue;
            if (registered.containsValue(text)) continue;
            if (serverFindings.contains(text) && text.startsWith("资料对“")) continue;
            if (mergeOriginalAnswerReminder(text, registered)) continue;
            if (mergeExistingExplanation(text, registered)) continue;
            // 原题已问及的字段扩展仍需作为条件性说明保留，先于纯题干复写过滤处理。
            if (mergeBoundImputationFieldExpansion(text, registered)) continue;
            // 只有同一字段、双方来源和取值，且不增加业务条件，才能归入已有冲突。
            if (knownConflicts.stream().anyMatch(identity -> identity.isReminder(text)
                    && (registered.containsKey(identity.key()) || decisions.resolvesConflict(
                    identity.field(), List.of(identity.leftValue(), identity.rightValue()))))) continue;
            if (matchesBoundQuestion(text, references)) continue;
            if (mergeConflictExplanation(text, knownConflicts, registered)) continue;
            if (mergeNewConflictExplanation(text, knownConflicts, registered)) continue;
            if (mergeSplitResearchReminder(text, registered)) continue;
            if (containsNewPendingDetail(text)) {
                registered.putIfAbsent("text:" + text, stripDecisionMetadata(text));
                continue;
            }
            if (mergeCompositePendingIdentity(text, registered)) continue;
            if (mergeNamedPendingIdentity(text, registered)) continue;
            if (mergeExplicitPendingSubject(text, registered)) continue;
            if (mergeConditionalCandidateExplanation(text, registered)) continue;
            if (mergeUnresolvedExplanation(text, registered)) continue;
            registered.putIfAbsent("text:" + text, stripDecisionMetadata(text));
        }
        // 全半角、空白和句末问号不是新决定；比较符、完整条件及代码大小写仍须区分。
        Map<String, String> formatted = new LinkedHashMap<>();
        registered.values().forEach(value -> formatted.putIfAbsent(reminderKey(value), value));
        return MergeResult.from(List.copyOf(formatted.values()));
    }

    /** 仅合并相同完整主体的简单未知状态；疑问、数值条件和解释仍逐字参与比较。 */
    private String reminderKey(String value) {
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").replaceAll("[。？?!！]+$", "");
        var simple = SIMPLE_UNKNOWN.matcher(normalized);
        return simple.matches() ? "pending:" + simple.group(1) : "text:" + normalized;
    }

    /**
     * 复合改写只有逐段覆盖已登记的完整属性才合并，解释保留一次；同题的新属性不作旧题复述。
     * 具名子项各自保持未决状态，不能因模型把它们写成一句而只留下一个决定。
     */
    private boolean mergeCompositePendingIdentity(String text, Map<String, String> registered) {
        String cleaned = stripDecisionMetadata(text);
        var separator = Pattern.compile("[。；;：:？?\\r\\n]|(?<=未确定|未提供|未明确|不确定|未决定|待确认|待明确)[，,]")
                .matcher(cleaned);
        boolean separated = separator.find();
        String heading = separated ? cleaned.substring(0, separator.start()).strip() : cleaned;
        List<ConfirmedPlanDecision> matching = pendingIdentity.compositeMatches(heading);
        if (matching.size() < 2) return false;
        String detail = separated ? cleaned.substring(separator.end()).strip() : "";
        if (hasNewPendingPart(detail)) return false;
        String key = "question:" + pendingKey(matching.getFirst());
        if (matching.stream().anyMatch(part -> !registered.containsKey("question:" + pendingKey(part)))) return false;
        // 已匹配属性不等于整段题干没有新信息；已确认范围和括注必须随完整正文保留。
        appendNovelExplanation(registered, key, carriesScopeAnnotation(heading) ? cleaned : detail);
        return true;
    }

    /** 新对象、条件或取值须完整保留；只接受每个具名未决子句与已有完整属性的明确映射。 */
    private boolean hasNewPendingPart(String detail) {
        if (detail.matches("(?s).*(?:另外|此外|还需|还需要|另需|同时还).*(?:确认|决定|是否|选择).*")) return true;
        return PlanAnswerSemantics.pendingParts(detail).stream().anyMatch(part ->
                !PlanAnswerSemantics.namesPendingSubject(part) || decisions.pendingDecisions().stream()
                        .noneMatch(pending -> pendingIdentity.matches(pendingHeading(part), pending)));
    }

    /** 解释逗号仅在完整未决状态之后分开，数值、版本和条件自身的逗号不参与拆分。 */
    private String pendingHeading(String part) {
        var separator = Pattern.compile("[。；;？?\\r\\n]|(?<=未确定|未提供|未明确|未核实|不确定|未决定|待确认|待明确)[，,]")
                .matcher(part);
        return separator.find() ? part.substring(0, separator.start()).strip() : part.strip();
    }

    /** 已知题干后明确追加另一个未决属性时，不再落入旧的解释归并回退路径。 */
    private boolean containsNewPendingDetail(String text) {
        String cleaned = stripDecisionMetadata(text);
        var separator = Pattern.compile("[。；;：:？?\\r\\n]|(?<=未确定|未提供|未明确|未核实|不确定|未决定|待确认|待明确)[，,]")
                .matcher(cleaned);
        if (!separator.find()) return false;
        String heading = cleaned.substring(0, separator.start()).strip();
        if (decisions.pendingDecisions().stream().noneMatch(pending -> pendingIdentity.matches(heading, pending))) return false;
        String detail = cleaned.substring(separator.end()).strip();
        if (hasNewUnverifiedCoverage(detail)) return true;
        if (boundDecisionStateExplanation(detail)) return false;
        return PlanAnswerSemantics.unresolved(detail) && PlanAnswerSemantics.pendingParts(detail).stream()
                .filter(PlanAnswerSemantics::namesPendingSubject)
                .anyMatch(part -> decisions.pendingDecisions().stream()
                        .noneMatch(pending -> pendingIdentity.matches(part, pending)));
    }

    /** 已拆开的三个插补属性只有同一绑定原题能归并；新方法、机构或条件仍保留在提醒正文。 */
    private boolean mergeSplitResearchReminder(String text, Map<String, String> registered) {
        var matching = new LinkedHashMap<String, String>();
        for (ConfirmedPlanDecision pending : decisions.pendingDecisions()) {
            pendingIdentity.repeatedResearchStatement(stripDecisionMetadata(text), pending)
                    .ifPresent(detail -> matching.putIfAbsent("question:" + pendingKey(pending), detail));
        }
        if (matching.size() != 1) return false;
        var entry = matching.entrySet().iterator().next();
        if (hasNewPendingPart(entry.getValue()) || hasNewUnverifiedCoverage(entry.getValue())) return false;
        appendNovelExplanation(registered, entry.getKey(), entry.getValue());
        return true;
    }

    /** 原题明确的插补字段扩展保持条件性，合入唯一绑定项；原有三项说明仍完整保留。 */
    private boolean mergeBoundImputationFieldExpansion(String text, Map<String, String> registered) {
        var matching = new LinkedHashMap<String, String>();
        for (ConfirmedPlanDecision pending : decisions.pendingDecisions()) {
            pendingIdentity.imputationFieldExpansion(stripDecisionMetadata(text), pending)
                    .ifPresent(detail -> matching.putIfAbsent("question:" + pendingKey(pending), detail));
        }
        if (matching.size() != 1) return false;
        var entry = matching.entrySet().iterator().next();
        if (!registered.containsKey(entry.getKey())) return false;
        // 字段条件不得被删掉；其后与原答案逐字相同的说明只需保留一次。
        String detail = entry.getValue();
        int boundary = detail.indexOf('。');
        String extra = detail.substring(0, boundary + 1);
        String tail = detail.substring(boundary + 1);
        String known = registered.get(entry.getKey());
        if (!tail.isBlank() && !known.contains(tail)) extra += tail;
        appendNovelExplanation(registered, entry.getKey(), extra);
        return true;
    }

    /** 新医院或年份的未核实覆盖度属于独立信息，不能借前半句同题而被当作重复说明。 */
    private boolean hasNewUnverifiedCoverage(String detail) {
        return java.util.Arrays.stream(detail.split("[，,。；;]+"))
                .map(String::strip).filter(pendingIdentity::namesUnverifiedCoverage)
                .anyMatch(clause -> decisions.pendingDecisions().stream()
                        .noneMatch(pending -> pendingIdentity.sameUnverifiedCoverage(clause, pending)));
    }

    /**
     * 同一完整具名子项可来自多个绑定问题，只保留一条提醒及其全部解释。
     * 匹配完整对象/属性/条件后按内部键归组；新问题藏在解释中时整条保留，不凭模型引用删掉。
     */
    private boolean mergeNamedPendingIdentity(String text, Map<String, String> registered) {
        String cleaned = stripDecisionMetadata(text);
        if (cleaned.matches("(?s).*(?:另外|此外|另需|还需|同时还).*")) return false;
        var separator = Pattern.compile("[。；;：:？?\\r\\n]|(?<=未确定|未提供|未明确|未核实|不确定|未决定|待确认|待明确)[，,]")
                .matcher(cleaned);
        boolean separated = separator.find();
        String heading = separated ? cleaned.substring(0, separator.start()).strip() : cleaned;
        var matching = new LinkedHashMap<String, ConfirmedPlanDecision>();
        for (ConfirmedPlanDecision pending : decisions.pendingDecisions()) {
            if (conflict(pending.question()).isPresent() || !pendingIdentity.matches(heading, pending)) continue;
            matching.putIfAbsent("question:" + pendingKey(pending), pending);
        }
        if (matching.size() != 1) return false;
        String key = matching.keySet().iterator().next();
        String detail = separated ? cleaned.substring(separator.end()).strip() : "";
        if (hasNewUnverifiedCoverage(detail)) return false;
        if (!boundDecisionStateExplanation(detail) && PlanAnswerSemantics.unresolved(detail) && PlanAnswerSemantics.pendingParts(detail).stream()
                .filter(PlanAnswerSemantics::namesPendingSubject)
                .anyMatch(part -> decisions.pendingDecisions().stream()
                        .noneMatch(pending -> pendingIdentity.matches(part, pending)))) return false;
        ConfirmedPlanDecision bound = matching.values().iterator().next();
        appendNovelExplanation(registered, key, carriesScopeAnnotation(heading)
                && !pendingIdentity.sameUnverifiedCoverage(heading, bound) ? cleaned : detail);
        return true;
    }

    /** 同一已匹配决定的指代状态不是新属性，影响说明仍由归并后的原条目完整交付。 */
    private boolean boundDecisionStateExplanation(String detail) {
        // 题干已匹配后，“该选择”的状态指向同一决定；影响说明完整保留，不消费独立具名未知。
        return detail.matches("^(?:该|上述)(?:选择|决定)影响[^。；;？?]{2,80}[，,](?:当前|目前)?"
                + "(?:尚未|仍未)(?:决定|确定)[。]?$" );
    }

    /** 匹配后仍保留具名范围、取值和括注，不因属性相同删除解释中的已确认条件。 */
    private boolean carriesScopeAnnotation(String heading) {
        return heading.matches(".*(?:[（(）)？?<>≤≥]|\\d|已确认|已明确).*" );
    }

    /** 完整条件句与候选名词短语可归在同一未决项；模型的来源和全部解释仍随正文交付。 */
    private boolean mergeConditionalCandidateExplanation(String text, Map<String, String> registered) {
        String cleaned = stripDecisionMetadata(text);
        int boundary = cleaned.indexOf('。');
        String header = boundary < 0 ? cleaned : cleaned.substring(0, boundary);
        if (cleaned.matches("(?s).*(?:另外|此外|还需|还需要|另需|同时还).*(?:确认|决定|是否|选择).*")) return false;
        List<ConfirmedPlanDecision> matching = decisions.pendingDecisions().stream()
                .filter(decision -> ConditionalCandidateDecision.same(header, decision.question())).toList();
        if (matching.size() != 1) return false;
        String key = "question:" + pendingKey(matching.getFirst());
        String detail = boundary < 0 ? "" : cleaned.substring(boundary + 1).strip();
        // 不保留改写的第二份题干；与题干相连的来源括注和完整处理要求仍一并交付。
        int annotation = header.indexOf('（');
        if (annotation >= 0) detail = header.substring(annotation) + (detail.isBlank() ? "" : " " + detail);
        if (!detail.isBlank() && !registered.get(key).contains(detail)) registered.put(key, registered.get(key) + " 补充说明：" + detail);
        return true;
    }

    /** 完整未决对象与属性相同时保留模型的全部解释；新条件、新对象或跨题歧义继续独立展示。 */
    private boolean mergeExplicitPendingSubject(String text, Map<String, String> registered) {
        String cleaned = stripDecisionMetadata(text);
        var separator = Pattern.compile("(?<=未提供)[，,]|(?<=未明确)[，,]|(?<=未确定)[，,]|(?<=不确定)[，,]").matcher(cleaned);
        if (!separator.find()) return false;
        String heading = cleaned.substring(0, separator.start());
        String subject = PlanDecisionIdentity.exactTextKey(PlanAnswerSemantics.pendingSubject(heading));
        if (subject.length() < 4) return false;
        var matching = decisions.pendingDecisions().stream()
                .filter(decision -> conflict(decision.question()).isEmpty())
                .filter(decision -> subject.equals(PlanDecisionIdentity.exactTextKey(PlanAnswerSemantics.pendingSubject(decision.question()))))
                .toList();
        if (matching.size() != 1) return false;
        String detail = cleaned.substring(separator.end()).strip();
        if (detail.matches("(?s).*(?:另外|此外|还需|还需要|另需|同时还).*(?:确认|决定|是否|选择).*")) return false;
        String key = "question:" + pendingKey(matching.getFirst());
        if (!detail.isBlank() && !registered.get(key).contains(detail)) registered.put(key, registered.get(key) + " 补充说明：" + detail);
        return true;
    }

    /**
     * 旧模型可能完整复写“原题＋原回答”。完整原题及各回答子句均须核对绑定，
     * 每个未决子项已登记后才移开重复题干；未知新对象、条件或取值仍整条保留。
     */
    private boolean mergeOriginalAnswerReminder(String text, Map<String, String> registered) {
        for (ConfirmedPlanDecision original : decisions.decisions()) {
            if (original.scope() != Scope.UNRESOLVED || conflict(original.question()).isPresent()) continue;
            String cleaned = text.replaceFirst("^该问题尚未确定[：:]\\s*", "");
            int headerEnd = boundPrefixEnd(cleaned, original.question());
            if (headerEnd < 0) continue;
            String answer = cleaned.substring(headerEnd).strip().replaceFirst("^用户说明[：:]\\s*", "");
            List<ConfirmedPlanDecision> pending = decisions.pendingDecisions().stream()
                    .filter(part -> java.util.Objects.equals(part.questionId(), original.questionId())).toList();
            if (pending.isEmpty() || pending.stream().anyMatch(part ->
                    !registered.containsKey("question:" + pendingKey(part)))) continue;
            if (answer.isEmpty()) return pending.size() == 1;
            int answerEnd = boundPrefixEnd(answer, original.answer());
            if (answerEnd >= 0) {
                String detail = answer.substring(answerEnd).strip().replaceFirst("^补充说明[：:]\\s*", "");
                if (hasNewPendingPart(detail)) return false;
                appendNovelExplanation(registered, "question:" + pendingKey(pending.getFirst()), detail);
                return true;
            }
            // 同一原题只解决指代来源；每个实际未决子句还须匹配具名属性或有出处的状态替换。
            List<String> clauses = answerClauses(answer);
            var mapped = new LinkedHashMap<String, String>();
            boolean coveredPending = false;
            boolean valid = true;
            for (String clause : clauses) {
                if (!PlanAnswerSemantics.unresolved(clause)) {
                    if (!compactText(original.answer()).contains(compactText(clause))) {
                        // 非未决说明不会变成确认事实，但其正文须随原始未决前提完整交付。
                        String key = "question:" + pendingKey(pending.getFirst());
                        mapped.merge(key, clause, (left, right) -> left + right);
                    }
                    continue;
                }
                List<ConfirmedPlanDecision> matching = pending.stream()
                        .filter(part -> pendingIdentity.matches(pendingHeading(clause), part)
                                || sameBoundUnknownState(clause, part, original)).toList();
                if (matching.size() != 1) { valid = false; break; }
                coveredPending = true;
                String key = "question:" + pendingKey(matching.getFirst());
                mapped.merge(key, repeatedStateDetail(clause), (left, right) -> left + right);
            }
            if (!valid || !coveredPending) continue;
            mapped.forEach((key, detail) -> appendNovelExplanation(registered, key, detail));
            return true;
        }
        return false;
    }

    /** 明确原题中的一个属性可由“当前尚未决定”指代；须同时核对状态后的全部原说明。 */
    private boolean sameBoundUnknownState(String clause, ConfirmedPlanDecision pending, ConfirmedPlanDecision original) {
        String value = compactText(clause);
        var generic = Pattern.compile("^(?:当前|目前|现在|本次)?(?:尚未|仍未|未)(?:决定|确定)[，,](.+)$").matcher(value);
        if (!generic.matches()) return false;
        String subject = PlanAnswerSemantics.pendingSubject(pending.question());
        if (subject.length() < 2 || !compactText(original.question()).contains(compactText(subject))) return false;
        String knownTail = compactText(repeatedStateDetail(pending.answer()));
        return generic.group(1).equals(knownTail);
    }

    /** 仅移除已经逐字绑定的未决状态题干，其后的禁止默认、来源和候选说明仍保留。 */
    private String repeatedStateDetail(String clause) {
        var separator = Pattern.compile("(?<=未确定|未提供|未明确|不确定|未决定|待确认|待明确)[，,]").matcher(clause);
        return separator.find() ? clause.substring(separator.end()).strip() : "";
    }

    /** 句尾分隔不触及数值小数点或比较符，保留每个子句的原标点供正文使用。 */
    private List<String> answerClauses(String answer) {
        return Pattern.compile("[^。；;\\r\\n]+[。；;]?").matcher(answer).results()
                .map(match -> match.group().strip()).filter(value -> !value.isEmpty()).toList();
    }

    /**
     * 原题和原回答只允许空白布局变化；逐代码点扫描，避免长回答构造重复量词正则。
     * 返回实际前缀结束位置，大小写、数值、符号及条件不进行语义归一化。
     */
    private int boundPrefixEnd(String actual, String expected) {
        int cursor = 0;
        for (int offset = 0; offset < expected.length();) {
            int point = expected.codePointAt(offset);
            offset += Character.charCount(point);
            if (Character.isWhitespace(point)) continue;
            while (cursor < actual.length() && Character.isWhitespace(actual.codePointAt(cursor))) {
                cursor += Character.charCount(actual.codePointAt(cursor));
            }
            if (cursor >= actual.length() || actual.codePointAt(cursor) != point) return -1;
            cursor += Character.charCount(point);
        }
        return cursor;
    }

    private String compactText(String text) {
        return text.replaceAll("\\s+", "").replaceAll("[。；;]+$", "");
    }

    /** 支持已经归并过的提醒再次进入组装，完整题目和答案前缀必须逐字一致。 */
    private boolean mergeExistingExplanation(String text, Map<String, String> registered) {
        for (var entry : registered.entrySet()) {
            String prefix = entry.getValue() + " 补充说明：";
            if (text.startsWith(prefix)) {
                String extra = text.substring(prefix.length());
                if (extra.matches("(?s).*(?:另外|此外|还需|还需要|另需|同时还).*(?:确认|决定|是否|选择).*")) return false;
                if (mergeOriginalAnswerReminder(extra, registered)) return true;
                appendNovelExplanation(registered, entry.getKey(), extra);
                return true;
            }
        }
        return false;
    }

    /**
     * 兼容旧模型逐字复写的原题和完整原回答，只统一绑定回答开头的裸未知状态展示。
     * 原始确认数据保持不变；前缀后的新对象、条件和取值仍交给正常归并校验，不能顺带删除。
     */
    private String normalizeBoundAnswerPresentation(String text) {
        for (ConfirmedPlanDecision original : decisions.decisions()) {
            if (original.scope() != Scope.UNRESOLVED) continue;
            String answer = original.answer().replaceFirst(
                    "(?i)^(?:暂不确定|尚未确定|待定|不知道|不清楚|unknown|tbd)[。.!！]\\s*", "");
            if (answer.equals(original.answer())) continue;
            String prefix = "该问题尚未确定：" + original.question() + " 用户说明：";
            String bound = prefix + original.answer();
            if (text.startsWith(bound)) return prefix + answer + text.substring(bound.length());
        }
        return text;
    }

    /** 同一对已登记证据的复述附在原冲突下，其他新来源、取值或条件保持独立。 */
    private boolean mergeConflictExplanation(String text, List<ConflictIdentity> conflicts, Map<String, String> registered) {
        int boundary = text.indexOf('。');
        if (boundary < 0) return false;
        String header = text.substring(0, boundary);
        List<String> matching = conflicts.stream().filter(identity -> registered.containsKey(identity.key()))
                .filter(identity -> identity.repeatsEvidence(header)).map(ConflictIdentity::key).distinct().toList();
        if (matching.size() != 1) return false;
        String detail = text.substring(boundary + 1).strip();
        // 解释若另外发起一项决定（如退款订单适用范围），保留独立提醒，不能折入旧取值冲突。
        if (detail.matches("(?s).*(?:另外|此外|还需|还需要|另需|同时还).*(?:确认|决定|是否|选择).*")) return false;
        String key = matching.getFirst();
        if (!detail.isBlank() && !registered.get(key).contains(detail)) {
            registered.put(key, registered.get(key) + " 补充说明：" + detail);
        }
        return true;
    }

    /**
     * 新资料复述已确认值与新增值时，绑定来源、属性和完整数值对后附到同一冲突。
     * 仅处理纯保留说明；另一来源、金额、年份或独立选择不能借旧确认吞并。
     */
    private boolean mergeNewConflictExplanation(String text, List<ConflictIdentity> conflicts, Map<String, String> registered) {
        if (text.matches("(?s).*(?:另外|此外|另需|还需|同时还).*")) return false;
        String normalized = normalize(text);
        List<ConflictIdentity> matching = conflicts.stream().filter(identity -> registered.containsKey(identity.key()))
                .filter(identity -> decisions.selectedConflictValue(identity.field(), List.of(identity.leftValue(), identity.rightValue()))
                        .filter(selected -> identity.matchesSelectedNewSourceExplanation(normalized, selected)).isPresent()).toList();
        if (matching.size() != 1) return false;
        String key = matching.getFirst().key();
        if (!registered.get(key).contains(text)) registered.put(key, registered.get(key) + " 补充说明：" + text);
        return true;
    }

    /**
     * 同一未决问题的展开说明放入原项，新增条件全文保留；已解决问题不走此分支。
     * 必须唯一匹配解释的完整主题句，不使用相似度或模型自报 ID 推断对应关系。
     */
    private boolean mergeUnresolvedExplanation(String text, Map<String, String> registered) {
        String cleaned = stripDecisionMetadata(text);
        // 仅把明确未定题干后的逗号看作解释边界；数量、版本和题干自身的逗号仍属于原题。
        var separator = Pattern.compile("[。；;：:？?\\r\\n]|(?<=未确定|未提供|未明确|不确定|未决定|待确认|待明确)[，,]").matcher(cleaned);
        if (!separator.find()) return false;
        String header = cleaned.substring(0, separator.start()).strip();
        // 明确列出的候选项是同一未决选择的展开，完整保留它们，不把候选取值当作已确认事实。
        var alternatives = Pattern.compile("^(.{2,40}?)(?:应)?采用(.+?)还是(.+?)尚未确定$").matcher(header);
        String matchingHeader = alternatives.matches() ? alternatives.group(1) : header;
        List<ConfirmedPlanDecision> matches = decisions.pendingDecisions().stream()
                .filter(decision -> conflict(decision.question()).isEmpty())
                .filter(decision -> sameQuestionReminder(header, decision.question())
                        || questionHeader(matchingHeader).equals(questionHeader(decision.question()))
                        || matchesPartialAnswerExplanation(header, decision))
                .toList();
        if (matches.size() != 1 || questionHeader(header).length() < 4) return false;
        ConfirmedPlanDecision decision = matches.getFirst();
        String key = "question:" + pendingKey(decision);
        String explanation = cleaned.substring(separator.end()).strip();
        if (explanation.matches("(?s).*(?:另外|此外|还需|还需要|另需|同时还).*(?:确认|决定|是否|选择).*")) return false;
        String detail = alternatives.matches() || matchesPartialAnswerExplanation(header, decision) ? cleaned : explanation;
        // 只去掉已经匹配的重复题干，解释中任何新单位、版本或条件仍进入同一条完整执行前提。
        appendNovelExplanation(registered, key, detail);
        return true;
    }

    /** 仅在已唯一绑定的同一未决项内去除逐句复写；新对象、单位、数值、运算符和条件原样保留。 */
    private void appendNovelExplanation(Map<String, String> registered, String key, String detail) {
        if (detail.isBlank()) return;
        var pending = decisions.pendingDecisions().stream().filter(part -> key.equals("question:" + pendingKey(part)))
                .toList();
        // 同一具名子项可能来自两道题；只有全部子项的对象键一致时，才允许共同消除原题复写。
        boolean sameNamedItem = !pending.isEmpty() && pending.stream()
                .map(pendingIdentity::namedKey).allMatch(java.util.Optional::isPresent)
                && pending.stream().map(part -> pendingIdentity.namedKey(part).orElseThrow()).distinct().count() == 1;
        // 同一绑定项的“原问题＋原答案”只是复述；移除完整原题前缀后仍逐句保留新解释。
        if (pending.size() == 1 || sameNamedItem) {
            var boundQuestions = new ArrayList<String>(pending.stream().map(ConfirmedPlanDecision::question).toList());
            decisions.decisions().stream().filter(original -> pending.stream()
                            .anyMatch(part -> java.util.Objects.equals(part.questionId(), original.questionId())))
                    .map(ConfirmedPlanDecision::question).forEach(boundQuestions::add);
            for (String question : boundQuestions) {
                // 跨题子项的“question”也可能是带约束的陈述；不能把其中尚未登记的限制随题干删掉。
                if (pending.size() != 1 && !question.strip().matches("(?s).*[？?]$")) continue;
                if (!question.isBlank() && detail.startsWith(question.strip())) {
                    detail = detail.substring(question.strip().length()).strip();
                    break;
                }
            }
        }
        if (pending.size() == 1) {
            var repeatedQuestion = Pattern.compile("^[^？?]+[？?]").matcher(detail);
            if (repeatedQuestion.find() && presentationQuestion(repeatedQuestion.group())
                    .equals(presentationQuestion(pending.getFirst().question()))) {
                detail = detail.substring(repeatedQuestion.end()).strip();
            }
        }
        if (detail.isBlank()) return;
        // 跨题子项已经按完整具名键登记；说明去重仍须核对相同对象，不能只凭题 ID 相同。
        var separator = Pattern.compile("[。；;：:？?\\r\\n]|(?<=未确定|未提供|未明确|不确定|未决定|未核实|待确认|待明确)[，,]").matcher(detail);
        if (separator.find()) {
            String header = detail.substring(0, separator.start()).strip();
            boolean exactPendingHeader = pending.size() == 1 && PENDING_SUFFIX.matcher(header).find()
                    && questionHeader(header).equals(questionHeader(pending.getFirst().question()));
            // 复合、疑问、具名已确认条件及候选取值仍属于完整执行前提，不能只保留逗号后半句。
            boolean repeatedPartialState = pending.size() == 1 && PENDING_SUFFIX.matcher(header).find()
                    && !header.matches(".*(?:[（(）)？?<>≤≥]|\\d|是否|如何|哪个|哪种|什么|采用.+还是).*" )
                    && matchesPartialAnswerExplanation(header, pending.getFirst());
            // 条件题干即使所有词都有出处，也不能等同于当前状态；删去它会切断后续适用条件。
            boolean conditionalHeader = header.matches("^(?:若|如果|假如|假设|仅当|仅在|当(?!前)).+");
            if (!conditionalHeader && (exactPendingHeader || repeatedPartialState || (pending.size() == 1 || sameNamedItem)
                    && repeatedBoundState(header, pending, registered.get(key)) || (pending.size() == 1 || sameNamedItem)
                    && !header.matches(".*(?:[（(）)？?<>≤≥]|\\d|已确认|已明确).*" )
                    && (pending.stream().anyMatch(part -> pendingIdentity.matches(header, part))
                    || sameNamedItem && PENDING_SUFFIX.matcher(header).find()
                    && pending.stream().anyMatch(part -> matchesPartialAnswerExplanation(header, part)))
                    || pending.size() == 1 && PENDING_SUFFIX.matcher(header).find()
                    && !header.matches(".*(?:[（(）)？?<>≤≥]|\\d|是否|如何|哪个|哪种|什么|已确认|已明确|采用.+还是).*" )
                    && (sameQuestionReminder(header, pending.getFirst().question())
                    || questionHeader(header).equals(questionHeader(pending.getFirst().question()))
                    || matchesPartialAnswerExplanation(header, pending.getFirst())))) {
                detail = detail.substring(separator.end()).strip();
            }
        }
        if (routeBoundCoverageHandling(registered, key, detail)) return;
        if (pending.size() == 1 || sameNamedItem) {
            detail = withoutRepeatedBoundRequests(registered.get(key), detail, pending);
            detail = withoutBoundExampleRestatement(registered.get(key), detail, pending);
            detail = withoutRepeatedExplanationClauses(registered.get(key), detail);
        }
        if (detail.isBlank()) return;
        String known = java.text.Normalizer.normalize(registered.get(key), java.text.Normalizer.Form.NFKC)
                .replaceAll("\\s+", "");
        // 保存未删分句的原标点，不把“3.0”、>= 等值域内容拆成另一条规则。
        String novel = Pattern.compile("[^。；;\\r\\n]+[。；;]?").matcher(detail).results()
                .map(match -> match.group().strip()).filter(value -> !value.isBlank())
                .filter(value -> !known.contains(java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                        .replaceAll("\\s+", "").replaceAll("[。；;]+$", "")))
                .collect(java.util.stream.Collectors.joining());
        if (!novel.isBlank()) registered.put(key, registered.get(key) + " 补充说明：" + novel);
    }

    /** 覆盖状态和未核实后的比较处理是两个决定；同年指代只能回到已有同院同年处理项，新条件仍随处理项保留。 */
    private boolean routeBoundCoverageHandling(Map<String, String> registered, String sourceKey, String detail) {
        var state = Pattern.compile("^该年数据的(?:年度与医院)?比较处理方式(?:未定|尚未决定|未确定)[。；;]?\\s*(.*)$")
                .matcher(detail);
        if (!state.matches()) return false;
        List<String> scopes = Pattern.compile("(?:[A-Za-z]医院|[甲乙丙丁]院)(?:19|20)\\d{2}年")
                .matcher(registered.get(sourceKey)).results().map(java.util.regex.MatchResult::group).distinct().toList();
        if (scopes.size() != 1) return false;
        List<String> targets = decisions.pendingDecisions().stream()
                .filter(part -> part.answer().contains(scopes.getFirst() + "覆盖度未核实时")
                        && part.answer().contains("比较处理方式"))
                .map(part -> "question:" + pendingKey(part)).filter(key -> !key.equals(sourceKey) && registered.containsKey(key))
                .distinct().toList();
        if (targets.size() != 1) return false;
        // 状态已在独立处理项中完整登记；只将后续说明归到该项，不能附在覆盖度下形成第二份决定。
        appendNovelExplanation(registered, targets.getFirst(), state.group(1));
        return true;
    }

    /**
     * 已登记为未决的同一参数不再附写第二份“请确认”；这里只移除完整自指请求。
     * 机构、年份和参数通过原绑定事项核对，新增记录范围、数值或条件分支原样交付。
     */
    private String withoutRepeatedBoundRequests(String known, String detail, List<ConfirmedPlanDecision> pending) {
        // 条件下的确认请求依赖前半句；不能拆句后把执行条件留成孤立说明。
        if (detail.matches("(?s).*(?:若|如果|假如|仅当|仅在|否则).*")) return detail;
        return Pattern.compile("[^。；;\\r\\n]+[。；;]?").matcher(detail).results()
                .map(match -> match.group().strip())
                .filter(clause -> !repeatsBoundRequest(known, clause.replaceAll("[。；;]+$", ""), pending))
                .collect(java.util.stream.Collectors.joining());
    }

    /** 所需核实内容已在同一未决项中完整保留时，才消费相同核查请求，不泛化其他专业决定。 */
    private boolean repeatsBoundRequest(String known, String clause, List<ConfirmedPlanDecision> pending) {
        boolean consistency = pending.stream().allMatch(part -> part.question().contains("一致性"))
                && known.contains("分母") && known.contains("按各逻辑规则的适用记录核实");
        if (consistency && (clause.matches("(?:后续)?(?:须|需)按各逻辑规则的适用记录核实并确认口径")
                || clause.matches("(?:请|需)?确认各逻辑规则的适用记录及分母定义"))) return true;
        var request = Pattern.compile("^(?:请|需|须|需要)?确认(.+)$").matcher(clause);
        if (!request.matches()) return false;
        String target = request.group(1);
        if (target.equals("是否插补、适用指标和方法")
                && known.contains("是否插补、适用指标和方法均未决定")
                && pending.stream().allMatch(part -> part.question().contains("插补"))) return true;
        if (target.equals("如何确定该覆盖度") && pending.stream()
                .allMatch(part -> part.question().contains("覆盖度如何确定"))) return true;
        String subject = target.replaceFirst("状态$", "");
        if (!subject.matches("[^，,。；;：:？?]{2,80}(?:分母|阈值|观察窗口|覆盖度)")) return false;
        // 限定为完整参数名；核实方式、审批来源、额外条件或另一个年份不能借相同类别被删除。
        return pending.stream().anyMatch(part -> pendingIdentity.matches(subject + "尚未确定", part));
    }

    /** “该示例值”仅从同一道绑定回答的唯一明确示例解析，不能借另一题的数字消除新阈值。 */
    private String withoutBoundExampleRestatement(String known, String detail, List<ConfirmedPlanDecision> pending) {
        if (!known.contains("也不采用该示例值") || !known.contains("异常等待阈值")) return detail;
        List<String> ids = pending.stream().map(ConfirmedPlanDecision::questionId).distinct().toList();
        if (ids.size() != 1) return detail;
        List<String> examples = decisions.decisions().stream().filter(original -> java.util.Objects.equals(ids.getFirst(), original.questionId()))
                .flatMap(original -> Pattern.compile("不采用资料中(\\d+(?:分钟|小时))示例值").matcher(original.answer()).results())
                .map(match -> match.group(1)).distinct().toList();
        if (examples.size() != 1) return detail;
        return Pattern.compile("[^。；;\\r\\n]+[。；;]?").matcher(detail).results()
                .map(match -> match.group().strip())
                .filter(clause -> !clause.replaceAll("[。；;]+$", "").equals("不采用资料中" + examples.getFirst() + "示例值"))
                .collect(java.util.stream.Collectors.joining());
    }

    /** 指代未知状态只在已唯一绑定、原题确有该属性的同一未决项内精简，不消除后续新解释。 */
    private boolean repeatedBoundState(String header, List<ConfirmedPlanDecision> pending, String known) {
        var state = Pattern.compile("^该(分母|覆盖度)(?:尚未|仍未|未)(?:决定|确定|明确|核实)$").matcher(header);
        return state.matches() && known != null && known.contains(state.group(1))
                && pending.stream().anyMatch(part -> part.question().contains(state.group(1)));
    }

    /**
     * 唯一绑定同一未决项后，去掉已完整保留的独立说明；新影响说明继续附在原项。
     * 条件和方法分支整体保留，不裁掉其后的共同动作；比较完整分句，不用子串冒充另一规则。
     */
    private String withoutRepeatedExplanationClauses(String known, String detail) {
        if (detail.matches("(?s).*(?:若|如果|假如|仅当|仅在|否则|采用|使用|选择).*")) return detail;
        var existing = java.util.Arrays.stream(known.split("[，,。；;\\r\\n]+"))
                .map(value -> explanationClauseKey(value, known)).collect(java.util.stream.Collectors.toSet());
        StringBuilder retained = new StringBuilder();
        var clauses = Pattern.compile("[^，,。；;\\r\\n]+[，,。；;]?").matcher(detail);
        while (clauses.find()) {
            String clause = clauses.group();
            String identity = explanationClauseKey(clause, known);
            boolean dependent = identity.matches("^(?:其中|其|该|此|但).*" );
            if (repeatsBoundCalculationImpact(identity, known)) continue;
            if (identity.length() < 6 || dependent || !existing.contains(identity)) retained.append(clause);
        }
        String result = retained.toString().strip().replaceAll("[，,；;]+$", "");
        return result.isBlank() || result.endsWith("。") ? result : result + "。";
    }

    /** 仅对完整核查指令归一化语法；新的方法、机构、范围、条件或数值不在此移除。 */
    private String explanationClauseKey(String value, String known) {
        String result = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").replaceFirst("^(?:该问题尚未确定|用户说明|补充说明):", "")
                .replaceAll("[，,。；;]+$", "");
        if (result.matches("^(?:后续)?(?:须|需)按各逻辑规则的适用记录(?:分别)?核实$")) {
            return "按各逻辑规则的适用记录核实";
        }
        // 只在已唯一绑定的同一题内规范核查谓词；2023年、另一院或审批条件仍逐字留在键中。
        result = result.replace("需要院方后续核实", "需院方后续核实");
        if (result.matches("^.{2,40}的(?:比较)?观察窗口需院方后续核实$")) {
            return "需院方后续核实";
        }
        // “另一院”只能展开为原需求命名、且拥有独立窗口问题的另一院；丙院、新年份或额外条件不泛化。
        var peer = Pattern.compile("^不能用([甲乙丙丁]院)(?:的)?(?:观察)?窗口替代$").matcher(result);
        var owner = Pattern.compile("[甲乙丙丁]院").matcher(known);
        if (peer.matches() && owner.find() && !peer.group(1).equals(owner.group()) && known.contains("观察窗口")
                && rawPrompt.contains(peer.group(1)) && decisions.pendingDecisions().stream()
                .anyMatch(part -> part.question().contains(peer.group(1)) && part.question().contains("观察窗口"))) {
            return "不能用另一院的窗口替代";
        }
        return result;
    }

    /** 已说明“相关异常比例计算等待阈值”时，同院的无新增条件影响句不再复写；另一院或新条件保留。 */
    private boolean repeatsBoundCalculationImpact(String clause, String known) {
        var impact = Pattern.compile("^该未决条件(?:会)?影响([甲乙丙丁]院)(?:的)?异常比例(?:计算|(?:统计)?口径)$").matcher(clause);
        var owner = Pattern.compile("[甲乙丙丁]院").matcher(known);
        return impact.matches() && owner.find() && impact.group(1).equals(owner.group())
                && known.contains("异常等待阈值") && known.contains("相关异常比例的计算需等待阈值确认");
    }

    /** 仅用于同一绑定项内的逐题复写；移除引导语，完整对象、条件、数值和疑问内容不变。 */
    private String presentationQuestion(String question) {
        return java.text.Normalizer.normalize(question, java.text.Normalizer.Form.NFKC)
                .replaceFirst("^对于", "").replaceFirst("[,，]本次方案(?=应如何定义[？?]$)", "")
                .replaceAll("\\s+", "");
    }

    /**
     * 部分确定的回答可补充原题未写出的待定子项。只有题干中的全部实词可在原题和回答中定位时才归并，
     * 不使用相似度阈值；整条提醒仍保留，新的数值、对象或独立决定不会被删掉。
     */
    private boolean matchesPartialAnswerExplanation(String header, ConfirmedPlanDecision decision) {
        if (decision.answer().length() <= 8) return false;
        String heading = withoutExamples(header.replaceAll("[（(]用户回答[^）)]*[）)]", ""))
                .replace("及其业务含义", "及业务含义");
        // 同题整份回答可以同时有状态和列名；只能以当前未决子项作为属性依据，不能让两项互相背书。
        String pending = decision.question() + " " + decision.answer();
        if ((heading.contains("列名") || heading.contains("字段名")) && !pending.contains("列名") && !pending.contains("字段名")
                || heading.contains("状态") && !pending.contains("状态")) return false;
        if (!samePartialObject(heading, decision)) return false;
        String grammar = "除已确认的|已确认采用|已确定为|已明确为|保持不变|仍未确定|尚未决定|尚未确定|尚未提供|未确定|其余|其他|具体|应如何|如何|是否还需要|是否需要|需要|尚未明确|未明确|未提供|仍待确定|已确认|仅确认|本次|采用|的|中|外|与|和|及|但";
        String evidence = decision.question() + " " + decision.answer() + " " + decisions.decisions().stream()
                .filter(original -> java.util.Objects.equals(original.questionId(), decision.questionId()))
                .map(original -> original.question() + " " + original.answer()).collect(java.util.stream.Collectors.joining(" "));
        // “除已确认代码之外”只在每个代码有同题肯定映射时转换为“其余”，不能借已确认前缀吞掉新代码。
        heading = knownCodeComplement(heading, decision);
        String[] fragments = heading.toLowerCase(Locale.ROOT).replaceAll(grammar, " ")
                .split("[^\\p{IsHan}a-z0-9_.<>≤≥=]+|(?<=[\\p{IsHan}])(?=[a-z0-9])|(?<=[a-z0-9])(?=[\\p{IsHan}])");
        String known = normalize(evidence);
        // 状态代码保留原有分隔符；移除逗号会把 BOOKED、CANCELLED 拼成一个词，破坏完整词边界核对。
        String knownTokens = java.text.Normalizer.normalize(evidence, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        int semanticLength = 0;
        for (String fragment : fragments) {
            if (fragment.isBlank()) continue;
            semanticLength += fragment.length();
            if (!fragment.matches("[\\p{IsHan}]+")) {
                if (!Pattern.compile("(?<![a-z0-9_.<>≤≥=])" + Pattern.quote(fragment) + "(?![a-z0-9_.<>≤≥=])")
                        .matcher(knownTokens).find()) return false;
                continue;
            }
            // 完整短语可直接命中；重排的中文短语需每一段至少两个字且全部有出处。
            boolean[] covered = new boolean[fragment.length() + 1];
            covered[0] = true;
            for (int start = 0; start < fragment.length(); start++) {
                if (!covered[start]) continue;
                for (int end = start + 2; end <= fragment.length(); end++) {
                    if (known.contains(fragment.substring(start, end))) covered[end] = true;
                }
            }
            if (!known.contains(fragment) && !covered[fragment.length()]) return false;
        }
        return semanticLength >= 6;
    }

    /** 缺少主语的子项只继承原题与原回答共同命名的对象，不借参考机构或另一子项匹配。 */
    private boolean samePartialObject(String heading, ConfirmedPlanDecision pending) {
        Pattern named = Pattern.compile("^([\\p{IsHan}A-Za-z0-9_]{1,20}?(?:医院|机构|公司|部门|工作区|租户))");
        var candidate = named.matcher(heading);
        if (!candidate.find()) return true;
        var explicit = named.matcher(pending.question());
        if (explicit.find()) return candidate.group(1).equals(explicit.group(1));
        List<String> owners = decisions.decisions().stream()
                .filter(original -> java.util.Objects.equals(original.questionId(), pending.questionId()))
                .flatMap(original -> {
                    var question = named.matcher(original.question());
                    var answer = named.matcher(original.answer());
                    return question.find() && answer.find() && question.group(1).equals(answer.group(1))
                            ? java.util.stream.Stream.of(question.group(1)) : java.util.stream.Stream.empty();
                }).distinct().toList();
        return owners.size() == 1 && owners.getFirst().equals(candidate.group(1));
    }

    /** 保留完整对象和适用条件，仅将逐个有肯定映射依据的技术代码补集换成同题的“其余”。 */
    private String knownCodeComplement(String heading, ConfirmedPlanDecision decision) {
        String confirmed = decisions.decisions().stream()
                .filter(original -> java.util.Objects.equals(original.questionId(), decision.questionId()))
                .map(original -> PlanAnswerSemantics.confirmedPart(original.answer()))
                .collect(java.util.stream.Collectors.joining(" "));
        var complement = Pattern.compile("除\\s*([A-Z][A-Z0-9_]*(?:\\s*[、,，]\\s*[A-Z][A-Z0-9_]*)*)\\s*之外")
                .matcher(heading);
        return complement.replaceAll(match -> Arrays.stream(match.group(1).split("[、,，]"))
                .map(String::strip).allMatch(code -> Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(code)
                        + "\\s*(?:表示|对应|=)").matcher(confirmed).find())
                ? "其余" : java.util.regex.Matcher.quoteReplacement(match.group()));
    }

    /** 仅消除询问语法，不归一化金额、对象或条件；斜线只在“年龄权重/贴现”固定同主题表达中处理。 */
    private String questionHeader(String text) {
        // “在 X 未定的情况下应如何处理”与“X 尚未确定”的解释可归在同一未决项下。
        // 只取完整 X；数字、版本、范围或复合条件留在 X 中，不凭主题词子串归并。
        var contextual = Pattern.compile("^在([^，,。；;]{2,80}?)(?:尚未确定|未确定)的情况下[，,].*应如何处理[^？?]+[？?]$")
                .matcher(text);
        if (contextual.matches()) text = contextual.group(1);
        // 只消除完整题干末尾的状态/提问语法，再逐字比较剩余对象和全部子项。
        // 复合题的“分歧处理及是否计算一致性”整体保留，不只拿一个主题词去重。
        text = PENDING_SUFFIX.matcher(text).replaceAll("");
        text = REGISTRATION_QUESTION_SUFFIX.matcher(text).replaceAll("");
        text = text.replace("用什么指标衡量", "衡量指标").replace("如何处理", "处理方式")
                .replace("以及", "及");
        return normalize(HEADER_GRAMMAR.matcher(text).replaceAll(""))
                .replace("参考寿命表来源", "参考寿命表")
                .replace("年龄权重/贴现", "年龄权重和贴现")
                .replace("与", "和").replace("的", "");
    }

    /** 只剔除可核对的纯绑定元数据括注，含其他业务描述的括注原样保留。 */
    private String stripDecisionMetadata(String text) {
        String result = text.replaceAll("[（(]用户回答[：:]?[“\"]?暂不确定[”\"]?[）)]", "");
        for (ConfirmedPlanDecision decision : decisions.decisions()) {
            if (decision.questionId() == null) continue;
            if (decision.scope() == Scope.UNRESOLVED) {
                result = result.replaceAll("[（(](?:confirmedDecisions|planAnswers)\\s*中\\s*"
                        + Pattern.quote(decision.questionId()) + "\\s*为[“\"]暂不确定[”\"][）)]", "");
            }
            // 有实际业务说明时仅替换内部绑定名，不能丢掉括注中的已确认范围与仍未知条件。
            result = result.replaceAll("(?:confirmedDecisions|planAnswers)\\s*中\\s*"
                    + Pattern.quote(decision.questionId()) + "(?![A-Za-z0-9_-])", "用户回答");
        }
        return result;
    }

    /** 优先检查模型引用，缺失或引用不正确时只接受唯一的保守文本匹配。 */
    private boolean matchesBoundQuestion(String text, List<AmbiguityReference> references) {
        List<ConfirmedPlanDecision> matching = decisions.assessedDecisions().stream()
                .filter(decision -> conflict(decision.question()).isEmpty())
                .filter(decision -> sameQuestionReminder(text, decision.question())
                        || PlanDecisionIdentity.repeatsDecisionLabel(text, decision.question(), decision.answer())).toList();
        if (matching.size() == 1) return true;
        return references.stream().filter(reference -> reference.message().equals(text))
                .anyMatch(reference -> decisions.decisions().stream().anyMatch(decision ->
                        reference.questionId().equals(decision.questionId())
                                && (matching.contains(decision) || genericReferencedReminder(text, decision.question()))));
    }

    /** 只剔除完全不含业务对象的列表引导语和明确的空列表声明；后续具体缺口始终保留。 */
    private boolean nonDecisionStatement(String text) {
        return text.matches("^(?:无|没有|无待确认事项|没有待确认事项)[。.!！]?$|^无[。]本题必要的事实、范围、读者和交付形式均已提供，不存在影响任务目标且目前缺失的业务决定[。]?$")
                || text.matches("^(?:以下|下列)(?:事项|问题|条件)尚未(?:决定|确定)[，,](?:须保留为预注册前待确认条件[，,])?不得默认补全[：:]$")
                || text.matches("^(?:以下|下列)(?:事项|问题|条件)尚未(?:决定|确定)[，,](?:需|须|需要)在(?:实施|执行)前确认[：:]$");
    }

    /** 同题多个未决子项各有稳定键，不能只用 questionId 让后一个版本、口径或映射覆盖前一个。 */
    private String pendingKey(ConfirmedPlanDecision decision) {
        return pendingIdentity.namedKey(decision).map(key -> "named:" + key).orElseGet(() ->
                (decision.questionId() == null ? "" : decision.questionId()) + "\u0000"
                + PlanDecisionIdentity.exactTextKey(decision.question()));
    }

    /** 未列入常见维度的题目只在有效 ID 和完整剩余主题同时吻合时合并，不凭 ID 清空内容。 */
    private boolean genericReferencedReminder(String text, String question) {
        List<String> clauses = reminderClauses(withoutExamples(text));
        if (clauses.size() > 1) {
            return clauses.stream().allMatch(clause -> genericReferencedReminder(clause, question));
        }
        String candidate = normalize(QUESTION_GRAMMAR.matcher(withoutExamples(text)).replaceAll(""));
        String known = normalize(QUESTION_GRAMMAR.matcher(withoutExamples(question)).replaceAll(""));
        return candidate.length() >= 4 && known.contains(candidate);
    }

    /**
     * 兼容旧 Provider 的自然语言提醒。只消除询问语法和可选示例，剩余内容必须已在原题中出现。
     * 同主题的新金额、版本、对象、条件和第二个决定不能仅凭主题命中而被合并。
     */
    private boolean sameQuestionReminder(String text, String question) {
        String candidate = withoutExamples(text);
        String original = withoutExamples(question);
        if (normalize(candidate).equals(normalize(original))) return true;
        if (sameConditionalCandidateQuestion(candidate, original)) return true;
        if (PlanDecisionIdentity.repeatsReminder(candidate, original)) return true;
        // 每个完整子句都必须有已知依据；不能全局删除“是否/需要/提供/数据”后吞掉第二个问题。
        List<String> clauses = reminderClauses(candidate);
        if (clauses.size() > 1) {
            return clauses.stream().allMatch(clause -> sameQuestionReminder(clause, original));
        }
        List<QuestionAspect> aspects = Arrays.stream(QuestionAspect.values())
                .filter(aspect -> aspect.pattern.matcher(original).find()).toList();
        if (aspects.size() != 1 || !aspects.getFirst().pattern.matcher(candidate).find()) return false;
        Pattern aspect = aspects.getFirst().pattern;
        String remaining = residue(candidate, aspect);
        String known = residue(original, aspect);
        return remaining.isEmpty() || !known.isEmpty() && known.contains(remaining);
    }

    /**
     * “当条件成立时，该候选应如何处理”仅规范化询问语法；完整条件逐字比较，
     * 不改写地区、运算符、数字、否定或附加业务条件。
     */
    private boolean sameConditionalCandidateQuestion(String candidate, String original) {
        Pattern question = Pattern.compile("^(?:当(?=候选|记录))?(.{2,180})时[，,](?:(?:该|这条|此)?(?:候选记录|候选|记录))?"
                + "应(?:如何|怎样)处理(?:(?:该|这条|此)(?:候选记录|候选|记录))?[？?]?$");
        var left = question.matcher(candidate.strip());
        var right = question.matcher(original.strip());
        return left.matches() && right.matches() && PlanDecisionIdentity.exactTextKey(left.group(1))
                .equals(PlanDecisionIdentity.exactTextKey(right.group(1)));
    }

    /** 逐句和分句检查；保留数字之间的逗号、小数点及比较符，不能改变金额或版本。 */
    private List<String> reminderClauses(String text) {
        return Arrays.stream(text.split("[。；;！？?\\r\\n]+|(?<![0-9])[,，]|[,，](?![0-9])"))
                .map(String::strip).filter(value -> !value.isEmpty()).toList();
    }

    private String residue(String text, Pattern aspect) {
        return normalize(QUESTION_GRAMMAR.matcher(aspect.matcher(text).replaceAll("")).replaceAll(""));
    }

    /** 例子只在没有额外询问或强制条件时视为填写辅助，不能隐藏括号中的新要求。 */
    private String withoutExamples(String text) {
        return EXAMPLE.matcher(text).replaceAll(match ->
                match.group().matches("(?s).*(是否|必须|仅|不得|适用|除外|但是|冲突|[？?]).*")
                        ? java.util.regex.Matcher.quoteReplacement(match.group()) : "");
    }

    /** 只从平台冲突格式提取成对证据，不把模型自报的字段名视为已核验冲突。 */
    private Optional<ConflictIdentity> conflict(String text) {
        var match = CONFLICT.matcher(text);
        if (!match.find()) return Optional.empty();
        return Optional.of(new ConflictIdentity(match.group(1), match.group(2), match.group(3),
                match.group(4), match.group(5)));
    }

    private static String normalize(String text) {
        // 比较符、版本小数点和代码标识符不是排版符号，不能归一化掉。
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\s，。；：？、“”‘’（）()?;,:\"']+", "")
                .replaceAll("!(?!=)", "");
    }

    /** 先匹配长词，避免短词破坏完整确认短语。 */
    private static Pattern words(String... values) {
        return Pattern.compile(Arrays.stream(values).sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|")));
    }

    private enum QuestionAspect {
        DATA_SOURCE("数据来源|数据源|来源|从哪里获取"),
        DEFINITION("死亡率定义|具体定义|定义"),
        TIME_RANGE("时间范围|时间段|年份区间"),
        DISEASE_SCOPE("病种范围|疾病范围|病种"),
        PURPOSE("分析目的|(?<!项)目的"),
        REGION("研究范围|地区范围|地区|地域"),
        TOOL("分析工具|工具"),
        OUTPUT_FORMAT("输出格式|交付格式"),
        AUTHENTICATION("认证方式|登录方式|身份保持方式");

        private final Pattern pattern;
        QuestionAspect(String expression) { this.pattern = Pattern.compile(expression); }
    }

    /** 来源与取值成对排序，反向引用相同证据仍是同一冲突，不同取值/来源另行保留。 */
    private record ConflictIdentity(String field, String leftPath, String leftValue,
                                    String rightPath, String rightValue) {
        /** 只认固定来源说明语法；任何额外数值、文件或业务条件都会使整条提醒继续独立。 */
        boolean matchesSelectedNewSourceExplanation(String text, String selected) {
            String selectedValue = normalize(selected);
            String left = normalize(leftValue);
            String right = normalize(rightValue);
            String path = selectedValue.equals(left) ? rightPath : selectedValue.equals(right) ? leftPath : "";
            String added = selectedValue.equals(left) ? right : left;
            if (path.isEmpty()) return false;
            String expression = "^" + Pattern.quote(normalize(path)) + "提出" + Pattern.quote(normalize(field)) + "为"
                    + Pattern.quote(added) + "并注明该意见与候选方案冲突尚未经业务负责人批准本次已确认采用"
                    + Pattern.quote(selectedValue) + "请确认该" + Pattern.quote(added)
                    + "意见是否仍需在方案中作为待批准事项保留或说明其适用范围$";
            return text.matches(expression);
        }

        /** 每个来源与取值都须出现，去除证据后只允许剩下简单询问语法。 */
        boolean repeatsEvidence(String header) {
            String remaining = normalize(header);
            for (String part : List.of(field, leftPath, rightPath, leftValue, rightValue)) {
                String value = normalize(part);
                if (!remaining.contains(value)) return false;
                remaining = remaining.replace(value, "");
            }
            remaining = remaining.replace("本次订单审批", "").replace("订单审批", "").replace("哪个", "");
            return CONFIRMATION.matcher(remaining).replaceAll("").isEmpty();
        }

        String key() {
            return "conflict:" + field + ":" + List.of(leftPath + "\u0000" + leftValue,
                    rightPath + "\u0000" + rightValue).stream().sorted().toList();
        }

        boolean isReminder(String text) {
            String remainder = text;
            for (String component : List.of(leftPath, rightPath, leftValue, rightValue, field)) {
                if (!remainder.contains(component)) return false;
                remainder = remainder.replace(component, "");
            }
            // 这里只识别阈值未决的说明句；新增金额、退款授权或其他范围仍保留在剩余内容中。
            remainder = normalize(remainder).replaceAll(
                    "(?:该值直接(?:影响|决定)|否则(?:无法|不能)确定)(?:审批|财务复核)的?触发条件(?:和财务复核范围)?", "");
            remainder = remainder.replace("资料对存在不同取值", "").replace("该问题尚未确定", "");
            return CONFIRMATION.matcher(remainder).replaceAll("").isEmpty();
        }
    }

    /** 展示列表有上限，复制正文使用完整归并结果，不能让展示预算丢掉执行前提。 */
    record MergeResult(List<String> messages, int omittedCount, List<String> executionPrerequisites) {
        static MergeResult from(List<String> values) {
            List<String> complete = values.stream().distinct().toList();
            return new MergeResult(complete.stream().limit(DISPLAY_LIMIT).toList(),
                    Math.max(0, complete.size() - DISPLAY_LIMIT), complete);
        }
    }
}
