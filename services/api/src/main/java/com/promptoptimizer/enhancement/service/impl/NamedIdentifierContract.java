package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanQuestionType;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import com.promptoptimizer.enhancement.domain.PlanningFactOrigin;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 区分标识的取值集合与具名实体的对应证据，避免按字母顺序或排除法绑定机构。
 * 当前只登记明确甲乙两院、hospital_id取值A/B的门诊比较，不替代跨行业实体识别。
 * 只消费已过滤的本次快照与服务端绑定答案，不读取文件、不生成患者数据。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class NamedIdentifierContract {
    /** 资料明确与用户实际确认分开；无证据和资料冲突都不能提供当前选值。 */
    enum State { UNRESOLVED, EVIDENCED, USER_CONFIRMED, CONFLICTED }

    /** 机构、字段及年份共同限定关系；来源用于核查，不自动证明当前对应。 */
    record Relation(String object, String field, String period, String value, State state, List<String> sources) {
        Relation { sources = List.copyOf(sources); }
    }

    private record Evidence(String text, String source, boolean confirmed) { }
    private record Binding(String object, String value) { }
    private static final String FIELD = "hospital_id";
    private static final List<String> OBJECTS = List.of("甲院", "乙院");
    private static final Pattern DOMAIN = Pattern.compile(
            "hospital_id(?:只能(?:取值)?为|可(?:取值)?为|取值(?:范围)?(?:为|是|:)|允许(?:为)?|仅为)"
                    + "(?:A(?:或|/|、|,|和|与)B|B(?:或|/|、|,|和|与)A)(?=$|[。；;，,\\s])");
    private static final Pattern YEAR = Pattern.compile("(?<![0-9])20[0-9]{2}(?![0-9])");
    private static final Pattern NON_FACT = Pattern.compile(
            "如果|假设|以后|将来|未来|例如|示例|样例|暂定|草稿|推荐|建议|候选|可能|或许|考虑|未批准|尚未批准|待批准|未生效|尚未生效|尚未确认|待确认是否|是否对应|能否对应");
    private static final String QUALIFIER = "(?:20[0-9]{2}年)?(?:普通门诊)?(?:的)?";
    private static final String VALUE_END = "(?=$|[，,。；;:：)）\\]}])";
    private static final Pattern DIRECT = Pattern.compile("(甲院|乙院)" + QUALIFIER
            + "(?:[（(])?(?:(?:对应|使用|采用)(?:的)?)?(?:hospital_id|医院代码|医院编码|代码|编码)"
            + "(?:对应|为|是|=|:|采用)?[\\\"'‘’“”]*([AB])[\\\"'‘’“”]*" + VALUE_END);
    private static final Pattern REVERSE = Pattern.compile("(?:hospital_id|医院代码|代码)(?:为|是|=|:)?"
            + "[\\\"'‘’“”]*([AB])[\\\"'‘’“”]*(?:对应|代表)(甲院|乙院)" + QUALIFIER + VALUE_END);
    private static final Pattern PARENTHETICAL = Pattern.compile("(甲院|乙院)" + QUALIFIER + "[(（]([AB])[)）]");
    private static final Pattern CODE_MAPPING = Pattern.compile("[\\\"'‘’“”]*(甲院|乙院)[\\\"'‘’“”]*"
            + "(?:对应|代码为|编码为|=|:)[\\\"'‘’“”]*([AB])[\\\"'‘’“”]*" + VALUE_END);
    private static final Pattern CODE_PAIR = Pattern.compile("[AB](?:和|与|、|/|或)[AB]");
    /** 同一A/B对应未知的纯后果句，不包含机构选择、候选赋值或别的业务属性。 */
    private static final Pattern UNKNOWN_INVERSE_MAPPING = Pattern.compile(
            "(?<=[，,；;。])(?:因此|因而|从而)?(?:无法|不能|尚不能)(?:确定|确认|核实)"
                    + "(?:A(?:/|、|和|与|或)B|B(?:/|、|和|与|或)A)(?:代码|取值)?(?:分别)?对应"
                    + "(?:哪家|哪所)医院(?=$|[，,；;。])");
    private static final List<String> MAPPING_QUESTION_WORDS = List.of("数据中的", "文件中的", "hospital_id", "医院代码",
            "医院编码", "哪家医院", "哪所医院", "中的哪一家", "对应", "分别", "说明", "确认", "它们", "甲院", "乙院", "还是",
            "取值", "请", "的", "为", "是", "和", "与", "、", "/", "或", "A", "B")
            .stream().sorted((left, right) -> Integer.compare(right.length(), left.length())).toList();
    private static final List<String> MAPPING_REMINDER_WORDS = List.of("hospital_id", "医院代码", "医院编码", "对应关系",
            "尚无证据", "当前无对应证据", "尚未确定", "尚未确认", "尚未核实", "未提供", "未确定", "未确认", "未核实", "待确认", "待核实",
            "正文、表格及伪代码中未知项用“医院代码待确认”占位", "仅依赖该对应的步骤需等待", "其他清洗与指标模板继续交付",
            "数据说明中", "数据中的", "资料中的", "具体院区名称", "医院维度分组", "按医院分组", "医院分组", "独立计算", "比较结果", "哪个代码",
            "可继续交付", "指标模板", "需等待", "需确认", "需核实", "该对应", "对应", "影响", "涉及", "取值",
            "出现", "仅以", "哪个", "代码", "甲院", "乙院", "两院", "比较", "归属", "清洗", "分组", "步骤", "其他",
            "分别", "前", "在", "的", "与", "和", "或", "/", "、", "A", "B")
            .stream().sorted((left, right) -> Integer.compare(right.length(), left.length())).toList();
    private final List<Relation> relations;
    private final Set<String> years;

    private NamedIdentifierContract(List<Relation> relations, Set<String> years) {
        this.relations = List.copyOf(relations);
        this.years = Set.copyOf(years);
    }

    /** 实际来源与有效答案按完整机构分别登记，不将仅出现的代码集合升级为对应关系。 */
    static NamedIdentifierContract from(String raw, ContextSnapshot context, List<PlanningFactCard> facts,
                                        List<ConfirmedPlanDecision> confirmations) {
        String original = raw == null ? "" : raw;
        String currentGoal = currentText(original);
        Set<String> years = YEAR.matcher(currentGoal).results().map(match -> match.group())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        // 非本次具名门诊任务立即返回，不扫描其上传快照或扩大其他功能的工作量。
        if (!currentGoal.contains("甲院") || !currentGoal.contains("乙院")
                || !currentGoal.matches("(?s).*(?:门诊|候诊|医院).*(?:比较|对比|分析|统计).*|(?s).*(?:比较|对比|分析|统计).*(?:门诊|候诊|医院).*")
                || com.promptoptimizer.template.domain.TaskIntentResolver.resolve(
                        com.promptoptimizer.enhancement.domain.TemplateCode.AUTO, original).deliveryProfile()
                        == com.promptoptimizer.template.domain.TaskDeliveryProfile.TRANSLATION) {
            return new NamedIdentifierContract(List.of(), years);
        }
        var policy = new PlanningEvidencePolicy(original);
        var materials = new ArrayList<Evidence>();
        materials.add(new Evidence(original, "原始需求", false));
        if (context != null) {
            if (context.customDescription() != null && !context.customDescription().isBlank()) {
                materials.add(new Evidence(context.customDescription(), "用户补充描述", false));
            }
            context.fileSnippets().stream().filter(policy::allows)
                    .filter(file -> trusted(PlanningEvidencePolicy.origin(file.path(), file.language())))
                    .forEach(file -> materials.add(new Evidence(String.join("\n", policy.evidenceLines(file)), file.path(), false)));
        }
        if (facts != null) facts.stream().filter(fact -> trusted(fact.origin()))
                .forEach(fact -> materials.add(new Evidence(fact.evidence(), fact.sourcePath(), false)));
        boolean enabled = materials.stream().anyMatch(material -> DOMAIN.matcher(normalize(currentText(material.text()))).find());
        if (!enabled) return new NamedIdentifierContract(List.of(), years);
        var preliminary = new NamedIdentifierContract(List.of(), years);
        if (confirmations != null) confirmations.forEach(decision -> {
            String answer = preliminary.currentConfirmedPart(decision);
            if (answer.isBlank()) return;
            // 简短代码必须由本次单个具名对应问题限定；不能用另一院或另一年的题干解释它。
            var owners = preliminary.questionOwners(decision.question());
            if (owners.size() == 1 && normalize(answer).matches("[AB][。.]?")) {
                answer = owners.getFirst() + "的hospital_id=" + normalize(answer).replaceAll("[。.]$", "");
            }
            materials.add(new Evidence(answer, "本次用户明确回答", true));
        });
        var result = new ArrayList<Relation>();
        var safety = new SensitiveValueDetector();
        String period = years.size() == 1 ? years.iterator().next() : "本次资料";
        for (String object : OBJECTS) {
            var evidenceValues = new LinkedHashSet<String>();
            var confirmedValues = new LinkedHashSet<String>();
            var evidenceSources = new LinkedHashSet<String>();
            var confirmedSources = new LinkedHashSet<String>();
            for (Evidence material : materials) {
                if (safety.containsCredential(material.text())) continue;
                Set<String> materialYears = YEAR.matcher(material.text()).results().map(match -> match.group())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                for (String clause : currentText(material.text()).split("[。；;\\n，,]")) {
                    if (!preliminary.currentScope(clause) || !admissible(clause)) continue;
                    // 混合新旧年份的材料无行级年份时保持未知，不能只因相邻句出现本次年份就继承。
                    if (!materialYears.isEmpty() && !years.containsAll(materialYears)
                            && !YEAR.matcher(clause).find()) continue;
                    for (Binding binding : bindings(clause)) {
                        if (!binding.object().equals(object)) continue;
                        (material.confirmed() ? confirmedValues : evidenceValues).add(binding.value());
                        (material.confirmed() ? confirmedSources : evidenceSources).add(material.source());
                    }
                }
                if (materialYears.isEmpty() || years.containsAll(materialYears)) {
                    for (Binding binding : materialTableBindings(material.text())) {
                        if (!binding.object().equals(object)) continue;
                        (material.confirmed() ? confirmedValues : evidenceValues).add(binding.value());
                        (material.confirmed() ? confirmedSources : evidenceSources).add(material.source());
                    }
                }
            }
            // 显式有效回答只解决同院冲突；另一院不按二选一排除法推定。
            Set<String> values = confirmedValues.isEmpty() ? evidenceValues : confirmedValues;
            List<String> sources = List.copyOf(confirmedValues.isEmpty() ? evidenceSources : confirmedSources);
            State state = values.size() > 1 ? State.CONFLICTED : values.isEmpty() ? State.UNRESOLVED
                    : confirmedValues.isEmpty() ? State.EVIDENCED : State.USER_CONFIRMED;
            result.add(new Relation(object, FIELD, period, values.size() == 1 ? values.iterator().next() : "", state, sources));
        }
        return new NamedIdentifierContract(result, years);
    }

    /**
     * 复用通用肯定子句；仅补全“本院明确赋值，另一院明确未知”的完整标识语法。
     * 不扩大通用逗号切分，也不把待定开头、同院自相矛盾、另一字段或条件句当确认。
     */
    private String currentConfirmedPart(ConfirmedPlanDecision decision) {
        if (decision.scope() != ConfirmedPlanDecision.Scope.UNRESOLVED) return decision.answer();
        String confirmed = PlanAnswerSemantics.confirmedPart(decision.answer());
        if (!confirmed.isBlank()) return confirmed;
        String[] clauses = normalize(decision.answer()).split("[，,]", 2);
        if (clauses.length != 2 || !admissible(clauses[0]) || !currentScope(clauses[0])) return "";
        List<Binding> first = bindings(clauses[0]);
        if (first.size() != 1 || !questionOwners(decision.question()).contains(first.getFirst().object())) return "";
        String other = OBJECTS.stream().filter(object -> !object.equals(first.getFirst().object())).findFirst().orElseThrow();
        if (!clauses[1].matches(Pattern.quote(other) + "(?:的)?hospital_id(?:尚未|仍未|未)(?:确定|确认|决定)[。.]?")) return "";
        return clauses[0];
    }

    /** 代码集合不能作为推荐依据；未决对应关系采用自由填写，不默认推荐A或B。 */
    List<PlanQuestion> requiredQuestions() {
        var questions = new ArrayList<PlanQuestion>();
        for (int index = 0; index < relations.size(); index++) {
            Relation relation = relations.get(index);
            if (known(relation)) continue;
            questions.add(new PlanQuestion("entity-id-relation-" + (index + 1),
                    relation.object() + "与 hospital_id 的对应关系是什么？",
                    relation.state() == State.CONFLICTED ? "同院材料的对应值冲突，请核对本次采用的代码；另一院需要独立依据。"
                            : "资料仅列出A/B取值集合，没有证明医院名称与代码的对应；不能按顺序推定。",
                    PlanQuestionType.FREE_TEXT, List.of(), List.of(), true));
        }
        return List.copyOf(questions);
    }

    /** 仅替代本次纯对应关系问题；新增年份、审批条件或其他字段仍由原流程处理。 */
    boolean coveredQuestion(PlanQuestion question) {
        String hint = normalize(question.hint());
        return !relations.isEmpty() && currentScope(hint)
                && !hint.matches(".*(?:新增|审批|授权|冲突|另一字段|其他字段).*" )
                && !questionOwners(question.question()).isEmpty();
    }

    /** 提供一份简短的当前关系视图，资料证据不得写成用户已确认。 */
    String guidance() {
        if (relations.isEmpty()) return "";
        var rows = new ArrayList<String>();
        for (Relation relation : relations) {
            String state = switch (relation.state()) {
                case UNRESOLVED -> "无对应证据";
                case CONFLICTED -> "资料对应冲突，须独立确认";
                case EVIDENCED -> "资料明确：" + String.join("；", relation.sources());
                case USER_CONFIRMED -> "本次用户明确回答";
            };
            rows.add("| " + relation.object() + " | hospital_id | "
                    + (known(relation) ? relation.value() : "对应关系待确认") + " | " + state.replace("|", "\\|") + " |");
        }
        return "实体标识的独立依据（A/B集合不证明名称对应，不按次序或排除法绑定）：\n"
                + "| 机构 | 标识字段 | 当前对应 | 状态与依据 |\n| --- | --- | --- | --- |\n"
                + String.join("\n", rows)
                + "\n正文、表格及伪代码采用同一状态。未知项用“医院代码待确认”占位；"
                + "仅依赖该对应的步骤需等待，其他清洗与指标模板继续交付。平台统一保留未决清单，模型不要再复写同义提醒。";
    }

    /** 未决关系随同一执行前提清单交付，不能只留在页面或原资料中。 */
    List<String> pendingStatements() {
        return relations.stream().filter(relation -> !known(relation)).map(relation -> relation.object()
                + "与 hospital_id 的对应关系" + (relation.state() == State.CONFLICTED ? "存在冲突，尚需确认。" : "尚未确定。"))
                .toList();
    }

    /** 绑定问题已完整保留该项未知时不另追加短状态，不借另一院问题覆盖。 */
    boolean coveredByBoundPending(String statement, ConfirmedDecisionSet decisions) {
        return decisions.pendingDecisions().stream().anyMatch(decision -> questionOwners(decision.question()).stream()
                .anyMatch(object -> statement.startsWith(object + "与 hospital_id")));
    }

    /**
     * 仅用本次独立未知关系覆盖模型的同项说明；权威关系表和短清单保留依赖影响。
     * 另一年份、审批、来源、候选值或未来条件不因出现相同字段被删除。
     */
    boolean coveredPendingStatement(String statement) {
        if (relations.isEmpty() || statement == null) return false;
        String value = normalize(statement);
        if (!currentScope(value) || value.matches("^[>‘’“”\\\"'].*")
                || value.matches(".*(?:审批|授权|冲突|阈值|窗口|患者|科室|来源|校验|建议|候选|以后|如果|假设|重新|迁移|版本|选择|是否|如何|[0-9]).*")) {
            return false;
        }
        if (!value.matches(".*(?:hospital_id|医院代码|医院编码).*") || !value.contains("对应")
                || !value.matches(".*(?:尚未(?:确定|确认|核实)|未(?:确定|确认|核实|提供)|待(?:确认|核实)|尚无证据|无对应证据).*")) {
            return false;
        }
        // 只消费当前关系及其依赖说明；还有绩效、另一字段或新规则时保留整句，不能靠黑名单猜测全部业务词。
        // 正向关系已核对对象、字段和未知状态；反向的A/B归属问法只能消费完整的同项后果句。
        // 不把“无法确定”作为通用删除词，未消费的新条件仍阻止归并。
        String unconsumed = UNKNOWN_INVERSE_MAPPING.matcher(value).replaceAll("");
        for (String word : MAPPING_REMINDER_WORDS) unconsumed = unconsumed.replace(word, "");
        if (!unconsumed.matches("[，,:：;；.。?？()（）]*")) return false;
        var owners = OBJECTS.stream().filter(value::contains).toList();
        // 一个具名说明不能覆盖另一院；混合已知、未知或冲突时不丢弃模型的整句。
        return !owners.isEmpty() && owners.stream().allMatch(object -> relations.stream()
                .anyMatch(relation -> relation.object().equals(object) && relation.state() == State.UNRESOLVED));
    }

    /**
     * 已有说明的首个决定明确覆盖同院未知关系时，不再追加一条短状态。
     * 此方法不删改已有说明；其附带的新审批、条件或依赖解释完整保留，另一院仍独立登记。
     */
    boolean coversPendingStatement(String finding, String pending) {
        if (relations.isEmpty() || finding == null || pending == null) return false;
        Relation target = relations.stream().filter(relation -> relation.state() == State.UNRESOLVED
                && pending.equals(relation.object() + "与 hospital_id 的对应关系尚未确定。"))
                .findFirst().orElse(null);
        if (target == null) return false;
        String primary = normalize(finding).split("[，,。；;：:]", 2)[0];
        if (!currentScope(primary) || primary.matches("^[>‘’“”\\\"'].*")
                || primary.matches(".*(?:如果|以后|假设|建议|推荐|候选|已确认|并非|不是|不再|阈值|窗口|分母|[0-9]).*")) return false;
        if (!Pattern.compile("(?<![A-Za-z0-9_])hospital_id(?![A-Za-z0-9_])|医院代码|医院编码").matcher(primary).find()
                || !primary.matches(".*(?:对应|映射).*" )
                || !primary.matches(".*(?:(?:尚未|未|仍未|暂未|没有)(?:确定|确认|核实|明确|说明|定义|提供)"
                        + "|未在[^，,。；;]{0,12}说明|待(?:确认|核实)|(?:尚无|无|缺少)(?:对应)?证据).*")) {
            return false;
        }
        // 不删除任何原说明，因此语序可变；只以当前具名对象、完整字段及显式未知状态阻止重复追加。
        var mentioned = OBJECTS.stream().filter(object -> Pattern.compile(Pattern.quote(object)
                + "(?=的|与|和|在|分别|还是|/|、|对应|hospital_id|医院代码|医院编码|[)）]|$)").matcher(primary).find()).toList();
        return mentioned.contains(target.object()) && mentioned.stream().allMatch(object -> relations.stream()
                .anyMatch(relation -> relation.object().equals(object) && relation.state() == State.UNRESOLVED));
    }

    /** 不用句尾未知或另一院状态抵消具体赋值；分别校验叙述、纵向表和代码字典。 */
    void validate(String content, String field) {
        if (relations.isEmpty() || content == null) return;
        List<String> header = List.of();
        for (String line : content.lines().toList()) {
            String normalized = normalize(line);
            if (normalized.startsWith("|")) {
                var cells = java.util.Arrays.stream(normalized.split("(?<!\\\\)\\|", -1))
                        .filter(cell -> !cell.isBlank()).toList();
                if (cells.stream().allMatch(cell -> cell.matches(":?-+:?"))) continue;
                if (header.isEmpty()) { header = cells; continue; }
                tableBindings(header, cells).forEach(binding -> validateBinding(binding, normalized, field));
            } else header = List.of();
            for (String clause : normalized.split("[。；;\\n，,]")) {
                // 代码字典的引号键是执行赋值，不能借“引用原文”规则绕过；普通整句引用仍保留。
                boolean codeKey = clause.matches("^[\\\"'](?:甲院|乙院)[\\\"']:.*");
                if (!admissible(clause) && !(codeKey && !NON_FACT.matcher(clause).find())) continue;
                bindings(clause).forEach(binding -> validateBinding(binding, clause, field));
            }
        }
    }

    /** 完整关系证据只授权该机构及本次适用范围，其他年份或不同值不能复用。 */
    private void validateBinding(Binding binding, String clause, String field) {
        Relation relation = relations.stream().filter(value -> value.object().equals(binding.object())).findFirst().orElseThrow();
        if (!currentScope(clause) || !known(relation) || !relation.value().equals(binding.value())) {
            throw new ProviderResponseValidationException(ProviderResponseValidationException.Reason.SOURCE_SCOPE_CONFLICT, field);
        }
    }

    /** 题干须只询问具名标识对应，不把另一年份、状态或新的审批要求当作已覆盖关系。 */
    private List<String> questionOwners(String question) {
        String value = normalize(question);
        if (!currentScope(value) || value.matches(".*(?:审批|状态|窗口|阈值|患者|来源|校验|以后|如果|假设).*")) return List.of();
        // 集合只限定纯对应题的覆盖；完整词法消费后仍有新内容时不按部分关键词删除。
        if (pureCodePairQuestion(value)) return OBJECTS;
        if (value.matches("^(?:数据中的|文件中的)?(?:hospital_id|医院代码|医院编码)(?:与|和)"
                + "(?:甲院|乙院)(?:(?:与|和|、|/)(?:甲院|乙院))?(?:的)?对应关系是什么[？?]?$")) {
            return OBJECTS.stream().filter(value::contains).toList();
        }
        if (!value.matches("^(?:甲院|乙院)(?:(?:与|和|、)(?:甲院|乙院))?(?:的|与)?"
                + "(?:hospital_id|医院代码|医院编码|代码)(?:的)?(?:对应关系|对应|映射|取值)?"
                + "(?:是什么|是多少|如何对应|怎么对应|如何确定)[？?]?$")) return List.of();
        return OBJECTS.stream().filter(value::contains).toList();
    }

    /** 允许纯对应问题调整语序，但不能消费另一字段、年份、对象或新增业务条件。 */
    private static boolean pureCodePairQuestion(String value) {
        if (!value.matches(".*(?:hospital_id|医院代码|医院编码).*") || !value.contains("对应")
                || !CODE_PAIR.matcher(value).results().anyMatch(match -> match.group().contains("A") && match.group().contains("B"))
                || !(value.contains("甲院") && value.contains("乙院") || value.contains("哪家医院") || value.contains("哪所医院"))) {
            return false;
        }
        String unconsumed = value;
        for (String word : MAPPING_QUESTION_WORDS) unconsumed = unconsumed.replace(word, "");
        return unconsumed.matches("[，,:：?？.。()（）]*");
    }

    /** 不让旧年份或不同就诊人群的证据为本次普通门诊背书。 */
    private boolean currentScope(String clause) {
        var namedYears = YEAR.matcher(clause).results().map(match -> match.group()).toList();
        return (namedYears.isEmpty() || years.containsAll(namedYears))
                && !clause.matches(".*(?:急诊|住院|体检|丙院|丁院).*" );
    }

    /** 否定、引用、假设、示例和未来条件保留为资料，不登记为当前已采用关系。 */
    private static boolean admissible(String clause) {
        String value = normalize(clause).replaceFirst("^[-*]", "");
        return !value.isBlank() && !value.matches("^[>‘’“”\\\"'`].*")
                && !NON_FACT.matcher(value).find()
                && !value.matches("^(?:此前|之前|过去|原先|历史记录|旧资料).*" )
                && !value.matches(".*(?:不得|不能|不要|不应|不可|未确定|尚未确定|仍未确定|未对应|尚未对应|未确认|未核实|未建立).*" );
    }

    /** 明确关系不依赖文字出现顺序；这里只识别完整赋值、反向对应与具名括号关系。 */
    private static List<Binding> bindings(String text) {
        var result = new LinkedHashSet<Binding>();
        DIRECT.matcher(normalize(text)).results().forEach(match -> result.add(new Binding(match.group(1), match.group(2))));
        REVERSE.matcher(normalize(text)).results().forEach(match -> result.add(new Binding(match.group(2), match.group(1))));
        PARENTHETICAL.matcher(normalize(text)).results().forEach(match -> result.add(new Binding(match.group(1), match.group(2))));
        CODE_MAPPING.matcher(normalize(text)).results().forEach(match -> result.add(new Binding(match.group(1), match.group(2))));
        return List.copyOf(result);
    }

    /** 明确表头与同一行共同证明对应，不从两行顺序或表尾说明推断。 */
    private static List<Binding> materialTableBindings(String text) {
        var result = new LinkedHashSet<Binding>();
        List<String> header = List.of();
        for (String line : text.lines().toList()) {
            String value = normalize(line);
            if (!value.startsWith("|")) { header = List.of(); continue; }
            var cells = java.util.Arrays.stream(value.split("(?<!\\\\)\\|", -1)).filter(cell -> !cell.isBlank()).toList();
            if (cells.stream().allMatch(cell -> cell.matches(":?-+:?"))) continue;
            if (header.isEmpty()) { header = cells; continue; }
            if (admissible(value)) result.addAll(tableBindings(header, cells));
        }
        return List.copyOf(result);
    }

    /** 纵向具名行与横向具名列都按完整字段检查；未知、A/B集合或另一字段不建立选值。 */
    private static List<Binding> tableBindings(List<String> header, List<String> cells) {
        var result = new LinkedHashSet<Binding>();
        int idColumn = -1;
        for (String label : List.of(FIELD, "当前对应", "医院代码", "医院编码")) {
            if (header.contains(label)) { idColumn = header.indexOf(label); break; }
        }
        if (idColumn >= 0 && idColumn < cells.size() && cells.get(idColumn).matches("[AB]")) {
            for (String object : OBJECTS) if (cells.contains(object)) result.add(new Binding(object, cells.get(idColumn)));
        }
        if (!cells.isEmpty() && cells.getFirst().equals(FIELD)) {
            for (int index = 0; index < Math.min(header.size(), cells.size()); index++) {
                if (OBJECTS.contains(header.get(index)) && cells.get(index).matches("[AB]")) {
                    result.add(new Binding(header.get(index), cells.get(index)));
                }
            }
        }
        return List.copyOf(result);
    }

    /** 只识别本次肯定目标与材料正文，围栏和独立引用不建立当前机构范围。 */
    private static String currentText(String text) {
        var result = new ArrayList<String>();
        boolean fenced = false;
        for (String line : text.lines().toList()) {
            String value = line.strip();
            if (value.startsWith("```") || value.startsWith("~~~")) { fenced = !fenced; continue; }
            if (fenced || value.matches("^[>‘’“”\\\"'].*")) continue;
            for (String sentence : line.split("[。；;]")) {
                // 条件覆盖整句时不从逗号后的子句建立确认；普通部分确认则逐对象保留。
                if (NON_FACT.matcher(sentence).find()) continue;
                for (String clause : sentence.split("[，,]")) if (admissible(clause)) result.add(clause);
            }
        }
        return String.join("。", result);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .replace("`", "").replace("**", "").replaceAll("\\s", "");
    }

    private static boolean known(Relation relation) {
        return relation.state() == State.EVIDENCED || relation.state() == State.USER_CONFIRMED;
    }

    private static boolean trusted(PlanningFactOrigin origin) {
        return origin == PlanningFactOrigin.USER_MATERIAL || origin == PlanningFactOrigin.PROJECT_DOCUMENT
                || origin == PlanningFactOrigin.PROJECT_SOURCE;
    }
}
