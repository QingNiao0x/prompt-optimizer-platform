package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException.Reason;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import java.util.Map;
import java.util.Optional;

/**
 * 将明确未决的具名参数绑定到交付物，防止正文保留未知而表格或公式擅自确定。
 * 只检查可定位的对象与属性，不替用户选择专业口径，也不执行下游任务。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class UnresolvedDecisionContract {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(UnresolvedDecisionContract.class);
    private static final Pattern DECLARATION = Pattern.compile(
            "([^。；;，,：:\\r\\n？?]{2,160}?)(?:(?:尚未|仍未|暂未|未)(?:确定|决定|核实|明确|确认|提供)"
                    + "|(?:尚需|仍需|需)(?:分别)?(?:确定|确认|核实)|(?:尚待|待)(?:分别)?(?:确定|确认|核实)|未决)");
    private static final Pattern PARAMETER = Pattern.compile("^(.{2,65}?)(?:的)?(分母|阈值|观察窗口|覆盖度|观察起点|观察终点|观察日期范围)$");
    private static final Pattern CONDITIONAL = Pattern.compile("若|如果|假如|假设|仅当|只有|例如|示例|引用|不要|不得|不能|不应|禁止");
    private static final Pattern PENDING = Pattern.compile("待确认|待定|未决|尚未|未确定|未决定|未核实|未提供|未确认|待核实|TBD|None|null", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENT_PARAMETER = Pattern.compile(
            "([^。；;，,：:\\r\\n？?]{2,80}?)(?:的)?(分母|阈值|观察窗口|覆盖度|观察起点|观察终点|观察日期范围)(?:明确)?(?:采用|使用|选定|包含|包括|为|是)([^。；;，,\\r\\n？?]+)");
    private static final Pattern ASSERTED_PARAMETER = Pattern.compile(
            "(?:用户|我|你)(?:已|已经)(?:明确)?(?:确认|确定|选定)([^。；;，,：:\\r\\n？?]{2,80}?)(?:的)?(分母|阈值|观察窗口|覆盖度|观察起点|观察终点|观察日期范围)"
                    + "|([^。；;，,：:\\r\\n？?]{2,80}?)(?:的)?(分母|阈值|观察窗口|覆盖度|观察起点|观察终点|观察日期范围)(?:已|已经)(?:由用户)?(?:确认|确定|选定)");
    private static final Pattern CONFIRMED_CELL = Pattern.compile("^(?:用户)?(?:已确认|已由用户确认|分母已确认)"
            + "(?:分母|口径|阈值|观察窗口|覆盖度)?(?:$|[（(：:，,；;].*)");
    private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})(.*)$");
    private static final Pattern CURRENT_CONFIRMATION = Pattern.compile("^(?:(?:本次|这次)(?:仅|只)?(?:明确)?(?:确认|确定|选定)"
            + "|(?:用户|我|你)(?:已|已经)(?:明确)?(?:确认|确定|选定))");
    private static final Pattern PREFIXED_WINDOW_CHOICE = Pattern.compile(
            "^([^。；;，,：:\\r\\n？?]{2,80}?)(?:的)?(\\d{1,4}(?:\\.\\d{1,4})?小时)观察窗口$");
    private static final Pattern PREFIXED_WINDOW_SUBJECT = Pattern.compile("^(.{2,80}?)(\\d{1,4}(?:\\.\\d{1,4})?小时)$");
    private static final Pattern DENIED_WINDOW_CONFIRMATION = Pattern.compile(
            "^(?:(已确认的)(?:(\\d{1,4}(?:\\.\\d{1,4})?小时))?(?:观察)?窗口|确认(?:观察)?窗口)"
                    + "(?:不代表|不等于)(?!.*(?:但|不过|然而|用户(?:已|已经)))[^，,。；;？?]*$");
    private static final String COMPOSITE_PARAMETER_COLUMN = "分母或所需参数";
    private static final List<String> PARAMETER_PROPERTIES = List.of("分母", "阈值", "观察窗口", "覆盖度", "观察起点", "观察终点", "观察日期范围");
    private static final Pattern INDEPENDENT_HOSPITALS = Pattern.compile("^两院(?:需要|须|应)?(?:独立|分别)(?:计算|确认).*$");
    private static final Pattern CURRENT_PENDING_CLAUSE = Pattern.compile(
            "(^|[，,])(\\s*)((?:(?:甲院|乙院)(?:的)?)?(?:异常等待(?:的)?阈值)|"
                    + "(?:甲院|乙院|两院)(?:的)?(?:比较)?观察窗口)"
                    + "(?:也|都|均)?(?:(?:尚未|仍未|暂未|未)(?:确定|决定|核实|明确|确认|提供)|(?:还)?没有(?:定|确定|决定))"
                    + "(?=$|[，,。；;])");
    static final String DELIVERY_GUIDANCE = "同一未决决定在用户已要求的交付形式中保持一致："
            + "相关参数格明确标为“待确认”，不得填入惯例、示例值或占位口径；"
            + "依赖该参数的计算只声明待确认参数并在确认前停止该计算，不能设置默认值或生成假结果。"
            + "不同指标的分母或阈值分别命名，禁止用同一个通用变量或共同分母覆盖未决指标。"
            + "已确认决定仅适用于对应对象、指标和条件；其他指标不得标成已确认，须有各自的独立依据。"
            + "只暂停依赖未决参数的步骤，其余已要求的内容继续交付；不得因此新增未要求的指标、表格或伪代码。";

    private record Parameter(String subject, String property) { }
    private record ParameterValue(Parameter parameter, String value) { }
    private final List<Parameter> parameters;
    private final List<Parameter> confirmedParameters;
    private final List<ParameterValue> confirmedValues;
    private final List<ParameterValue> materialValues;
    /** 服务端仍标为未决的原题；允许其明确范围参与提醒覆盖，不赋予任何参数选值。 */
    private final List<String> boundPendingQuestions;
    private final boolean twoNamedHospitals;
    private final boolean independentHospitalParameters;
    private final boolean hospitalWaitingComparison;
    private final boolean indicatorTableRequested;
    private final boolean pseudocodeRequested;
    private final boolean formatParameterNotEvidenced;

    private UnresolvedDecisionContract(List<Parameter> parameters, List<ParameterValue> confirmedValues,
                                       List<ParameterValue> materialValues, boolean twoNamedHospitals,
                                       boolean independentHospitalParameters, boolean hospitalWaitingComparison, boolean indicatorTableRequested,
                                       boolean pseudocodeRequested, boolean formatIndicatorsRequested, List<String> boundPendingQuestions) {
        this.parameters = List.copyOf(parameters);
        this.confirmedValues = List.copyOf(confirmedValues);
        this.confirmedParameters = confirmedValues.stream().map(ParameterValue::parameter).distinct().toList();
        this.materialValues = List.copyOf(materialValues);
        this.boundPendingQuestions = List.copyOf(boundPendingQuestions);
        this.twoNamedHospitals = twoNamedHospitals;
        this.independentHospitalParameters = independentHospitalParameters;
        this.hospitalWaitingComparison = hospitalWaitingComparison;
        this.indicatorTableRequested = indicatorTableRequested;
        this.pseudocodeRequested = pseudocodeRequested;
        this.formatParameterNotEvidenced = formatIndicatorsRequested && java.util.stream.Stream.concat(confirmedValues.stream(), materialValues.stream())
                .noneMatch(value -> value.parameter().subject().contains("格式"));
    }

    /** 原需求及有效回答分别建立未决状态；另一指标的已确认分母不能消除当前指标的未知。 */
    static UnresolvedDecisionContract from(String raw, ConfirmedDecisionSet decisions) {
        return from(raw, decisions, List.of());
    }

    /** 已筛选资料的明确状态也参与同一契约；有效答案只更新完全相同的具名参数，原证据不修改。 */
    static UnresolvedDecisionContract from(String raw, ConfirmedDecisionSet decisions, List<String> evidence) {
        var resolved = ResolvedPlanState.from(decisions, raw);
        boolean twoNamedHospitals = raw != null && raw.contains("甲院和乙院")
                && java.util.stream.Stream.concat(java.util.stream.Stream.of(raw), evidence.stream())
                .noneMatch(value -> value.matches("(?s).*(?:丙院|丁院|其他医院|[A-Z]医院).*"));
        boolean independentHospitalParameters = twoNamedHospitals && explicitlyIndependentHospitals(raw);
        var sources = new ArrayList<>(declaredPending(resolved.reconcile(raw)));
        evidence.forEach(value -> sources.addAll(declaredPending(resolved.reconcile(value))));
        decisions.pendingDecisions().forEach(value -> sources.addAll(declaredPending(value.answer())));
        var confirmedSources = new ArrayList<String>();
        if (raw != null) confirmedSources.add(raw);
        decisions.knownDecisions().forEach(value -> confirmedSources.add(value.answer()));
        var currentValues = new java.util.LinkedHashMap<Parameter, ParameterValue>();
        // 原始要求先登记，服务端有效回答后更新；同名旧值不能在当前视图再占一行，其他对象不受影响。
        confirmedSources.stream().flatMap(value -> currentParameterValues(value).stream())
                .map(value -> independentHospitalParameters ? new ParameterValue(independentHospitalName(value.parameter()), value.value()) : value)
                .forEach(value -> currentValues.put(value.parameter(), value));
        List<ParameterValue> confirmedValues = List.copyOf(currentValues.values());
        List<Parameter> confirmed = confirmedValues.stream().map(ParameterValue::parameter).distinct().toList();
        // 资料中的并列未知可能只被回答了一项：按完整对象＋属性更新当前视图，不修改证据或替另一项确认。
        List<Parameter> parameters = sources.stream().flatMap(value -> DECLARATION.matcher(value).results())
                .map(match -> canonical(match.group(1))).flatMap(value -> namedParameters(value).stream())
                // 只有任务要求明确独立时展开概述的范围；先展开再应用真实回答，不能让甲院取值覆盖乙院。
                .flatMap(parameter -> (independentHospitalParameters ? hospitalParameterScopes(parameter)
                        : List.of(parameter)).stream()).distinct()
                .filter(parameter -> !confirmed.contains(parameter)).toList();
        // 资料提供某一口径，不等于用户确认了它；建议或待批准卡片只保留原证据，不生成当前口径行。
        List<ParameterValue> materialValues = evidence.stream()
                .filter(value -> !value.matches("(?s).*(?:建议|候选|未批准|尚未批准).*$"))
                .flatMap(value -> currentParameterValues(value).stream())
                .map(value -> independentHospitalParameters ? new ParameterValue(independentHospitalName(value.parameter()), value.value()) : value)
                .filter(value -> !parameters.contains(value.parameter()) && !confirmed.contains(value.parameter())).distinct().toList();
        return new UnresolvedDecisionContract(parameters, confirmedValues, materialValues,
                twoNamedHospitals, independentHospitalParameters, independentHospitalParameters && raw.contains("候诊时间"),
                confirmedSources.stream().anyMatch(value -> requestedDeliverable(value, "指标表")),
                confirmedSources.stream().anyMatch(value -> requestedDeliverable(value, "伪代码")),
                raw != null && raw.matches("(?s).*(?:格式不合法|格式异常|格式错误|格式正确率|格式合法率).*"),
                decisions.pendingDecisions().stream().map(com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision::question).toList());
    }

    /** 本次肯定交付指令才激活相应保护；条件、引用、示例及单纯提到表名不扩大任务范围。 */
    private static boolean requestedDeliverable(String text, String name) {
        var directive = Pattern.compile("交付|输出|提供|包含|提交|列出|给出|生成|完成|附上|制作|设计|需要|要求");
        var hypothetical = Pattern.compile("若|如果|假如|假设|仅当|只有|例如|示例|引用");
        var negativeAction = Pattern.compile("(?:不|不要|无需|不需|禁止|避免|不必|不得|不能)"
                + "(?:需要|要求)?(?:再|另行|额外|自行|单独|强行)*\\s*$");
        for (String sentence : currentDecisionSentences(text)) {
            boolean condition = false;
            for (String clause : sentence.split("[，,]")) {
                var action = directive.matcher(clause);
                String prefix = action.find() ? clause.substring(0, action.start()) : clause;
                condition |= hypothetical.matcher(prefix).find();
                // 后半句的禁止计算不撤销前半句的真实交付；条件前提则持续约束同句后续分句。
                int nameIndex = clause.indexOf(name);
                if (condition || nameIndex < 0) continue;
                // 否定作用于该交付物前最近的要求动词；“不同口径”“不超过两张”是范围，不是禁止交付。
                var precedingAction = directive.matcher(clause.substring(0, nameIndex));
                int actionStart = -1;
                int actionEnd = -1;
                while (precedingAction.find()) {
                    actionStart = precedingAction.start();
                    actionEnd = precedingAction.end();
                }
                if (actionStart >= 0 && nameIndex - actionEnd <= 80
                        && !negativeAction.matcher(clause.substring(0, actionStart)).find()) return true;
            }
        }
        return false;
    }

    /**
     * 只接受本次原始要求中的肯定指令，引用、假设与代码不能建立机构范围。
     * 当前有限语法只对应已具名且唯一的甲乙两院，不按医院类别推断所有机构或年份。
     */
    private static boolean explicitlyIndependentHospitals(String raw) {
        char fenceType = 0;
        int fenceLength = 0;
        for (String line : raw.lines().toList()) {
            var fence = FENCE.matcher(line.strip());
            if (fence.matches()) {
                String marker = fence.group(1);
                if (fenceType == 0) {
                    fenceType = marker.charAt(0);
                    fenceLength = marker.length();
                } else if (marker.charAt(0) == fenceType && marker.length() >= fenceLength && fence.group(2).isBlank()) {
                    fenceType = 0;
                }
                continue;
            }
            if (fenceType != 0) continue;
            for (String sentence : line.split("[。；;]+")) {
                String stripped = sentence.strip().replaceFirst("^[-*•]\\s+", "");
                if (stripped.matches("^[>‘’“”\\\"'`].*")) continue;
                // 限制词位于肯定行动之前时不匹配；后续“不能混为一项”不否定已明确的独立要求。
                if (INDEPENDENT_HOSPITALS.matcher(canonical(stripped)).matches()) return true;
            }
        }
        return false;
    }

    /** 完整的概述名才展开；带年份、另一业务对象或额外条件的名称原样保留。 */
    private static List<Parameter> hospitalParameterScopes(Parameter parameter) {
        if (parameter.property().equals("观察窗口") && parameter.subject().matches("两院(?:比较)?")) {
            return List.of(new Parameter("甲院", "观察窗口"), new Parameter("乙院", "观察窗口"));
        }
        if (parameter.property().equals("阈值") && parameter.subject().equals("异常等待")) {
            return List.of(new Parameter("甲院异常等待", "阈值"), new Parameter("乙院异常等待", "阈值"));
        }
        return List.of(independentHospitalName(parameter));
    }

    /** 当前独立比较内的完整参数别名归一化；另一年份、指标或非独立任务不参与。 */
    private static Parameter independentHospitalName(Parameter parameter) {
        if (parameter.property().equals("观察窗口") && parameter.subject().matches("(?:甲院|乙院)(?:的)?比较")) {
            return new Parameter(parameter.subject().replaceFirst("(?:的)?比较$", ""), parameter.property());
        }
        if (parameter.property().equals("阈值") && parameter.subject().matches("(?:甲院|乙院)的异常等待")) {
            return new Parameter(parameter.subject().replaceFirst("的(?=异常等待$)", ""), parameter.property());
        }
        return parameter;
    }

    /**
     * 独立状态已登记时，用其当前视图替换整句公共未知，不重复追加一份公共提醒。
     * 带新条件、来源、取值或解释的提醒不消费；未绑定到当前参数的模型新问题仍保留。
     */
    List<String> independentlyScopedPending(List<String> findings) {
        if (!independentHospitalParameters) return findings;
        return findings.stream().flatMap(finding -> {
            String value = canonical(finding).replaceFirst("[。]$", "");
            var declaration = DECLARATION.matcher(value);
            if (!declaration.matches()) return java.util.stream.Stream.of(finding);
            List<Parameter> named = namedParameters(declaration.group(1));
            if (named.size() != 1) return java.util.stream.Stream.of(finding);
            List<Parameter> scopes = hospitalParameterScopes(named.getFirst());
            if (scopes.size() != 2 || scopes.stream().anyMatch(parameter -> !parameters.contains(parameter)
                    && !confirmedParameters.contains(parameter))) return java.util.stream.Stream.of(finding);
            return scopes.stream().filter(parameters::contains)
                    .map(parameter -> parameter.subject() + "的" + parameter.property() + "尚未确定。");
        }).distinct().toList();
    }

    /**
     * 纯概述的每个独立参数已有单项提醒时才移除概述，不消费解释、年份或新条件。
     * 只用当前已登记参数作覆盖证明；不借概述创建新决定，也不删除剩余单项。
     */
    List<String> withoutCoveredSummaries(List<String> reminders) {
        List<String> complete = withoutCoveredPureStatuses(reminders);
        if (!independentHospitalParameters) return complete;
        return complete.stream().filter(reminder -> {
            if (reminder.matches("^[>‘’“”\\\"'`].*")) return true;
            var summary = Pattern.compile("^该问题尚未确定:(.+?)(?:分别)?(?:尚未|仍未|未)(?:确定|决定)[。]?$"
                    ).matcher(canonical(reminder));
            if (!summary.matches()) return true;
            // 最终仍保留的逐项提醒证明覆盖，不借提醒新增事实、确认值或计算前提。
            List<Parameter> covered = complete.stream().filter(other -> !other.equals(reminder))
                    .flatMap(other -> independentReminderParameter(other).stream()).distinct().toList();
            var mentioned = new ArrayList<Parameter>();
            for (String part : summary.group(1).split("与|和|及|、")) {
                List<Parameter> parsed = namedParameters(part);
                if (parsed.size() != 1) return true;
                Parameter parameter = parsed.getFirst();
                // 裸院名阈值只在当前候诊任务的唯一同院阈值明确登记时映射；不推广到其他指标。
                if (hospitalWaitingComparison && parameter.property().equals("阈值")
                        && parameter.subject().matches("甲院|乙院")) {
                    String object = parameter.subject();
                    List<Parameter> candidates = covered.stream().filter(value -> value.property().equals("阈值")
                            && value.subject().startsWith(object)).distinct().toList();
                    if (candidates.size() != 1 || !candidates.getFirst().subject().equals(object + "异常等待")) return true;
                    parameter = candidates.getFirst();
                }
                List<Parameter> scoped = hospitalParameterScopes(parameter);
                if (scoped.stream().anyMatch(value -> !isIndependentHospitalParameter(value))) return true;
                mentioned.addAll(scoped);
            }
            if (mentioned.stream().distinct().count() < 2) return true;
            return mentioned.stream().anyMatch(parameter -> !covered.contains(parameter));
        }).toList();
    }

    /** 只识别最终清单中完整具名的独立标题，解释尾句不用于猜测对象或覆盖新条件。 */
    private Optional<Parameter> independentReminderParameter(String reminder) {
        String heading = reminder.replaceFirst("^该问题尚未确定[：:]", "")
                .split("[。；;？?]|(?<=未确定|未决定|未核实)[，,]", 2)[0].strip();
        if (heading.strip().matches("^[>‘’“”\\\"'`].*")) return Optional.empty();
        String subject = PlanAnswerSemantics.pendingSubject(canonical(heading));
        if (subject.isEmpty()) subject = canonical(heading).replaceFirst(
                "(?:(?:应|应该)?如何(?:确定|确认|设定|定义)|是多少(?:分钟)?|是什么)$", "");
        List<Parameter> named = namedParameters(subject).stream().map(this::boundParameterName).toList();
        return named.size() == 1 && isIndependentHospitalParameter(named.getFirst())
                ? Optional.of(named.getFirst()) : Optional.empty();
    }

    /**
     * 同一独立机构参数的语法匹配复用当前登记，避免提醒链路另造一份宽泛主题键。
     * 只有绑定项对应本次确实未决的完整参数时才作明确判断；无具名范围的提醒不能借该项归并。
     */
    Optional<Boolean> matchesBoundIndependentParameter(String heading, String boundQuestion) {
        var complete = boundReminderParameter(boundQuestion, true);
        if (complete.isPresent() && parameters.contains(complete.get())) {
            var candidate = boundReminderParameter(heading, false);
            // 已绑定完整对象后，另一年份、属性及新增限定必须明确不匹配，不能落入宽泛主题回退。
            if (candidate.isPresent()) return Optional.of(candidate.equals(complete));
            if (heading.strip().matches("^[>‘’“”\\\"'`].*")
                    || canonical(heading).matches("^(?:若|如果|假如|假设|仅当|只有).*")) return Optional.of(false);
            // 长说明或完整原题不是一个纯参数声明；不据此否定原有绑定，交给保留新条件的原链路。
        }
        if (!independentHospitalParameters) return Optional.empty();
        String knownSubject = PlanAnswerSemantics.pendingSubject(canonical(boundQuestion));
        if (knownSubject.isEmpty()) {
            knownSubject = canonical(boundQuestion).replaceFirst(
                    "(?:(?:应|应该)?如何(?:确定|确认|设定|定义)|是多少(?:分钟)?|是什么)[？?]?$", "");
        }
        List<Parameter> known = namedParameters(knownSubject).stream().map(this::boundParameterName).toList();
        if (known.size() != 1 || (!parameters.contains(known.getFirst()) && !boundPendingQuestions.contains(boundQuestion))
                || !isIndependentHospitalParameter(known.getFirst())) return Optional.empty();
        // 不能先去除引号再把引用中的旧状态当作当前执行决定。
        if (heading.strip().matches("^[>‘’“”\\\"'`].*")) return Optional.of(false);
        String currentHeading = canonical(heading).replaceFirst("[。？?]$", "");
        // 原题仍未决时，“需要院方后续核实”只是该窗口的完整状态复写；独立条件不由此确认为未知。
        if (boundPendingQuestions.contains(boundQuestion)) {
            currentHeading = currentHeading.replaceFirst("(?<=观察窗口)需要(?:院方)?后续核实$", "尚未核实");
        }
        var declaration = DECLARATION.matcher(currentHeading);
        if (!declaration.matches()) return Optional.of(false);
        List<Parameter> candidate = namedParameters(declaration.group(1)).stream()
                .map(this::boundParameterName).toList();
        return Optional.of(candidate.size() == 1 && candidate.getFirst().equals(known.getFirst()));
    }

    /**
     * 完整具名问句与纯未知共享参数身份，只统一明确的属性说法和提问尾部。
     * 不省略机构、年份、科室、比较符或适用条件，引用与未来语境不提供当前状态依据。
     */
    private Optional<Parameter> boundReminderParameter(String text, boolean question) {
        if (text == null || text.strip().matches("^[>‘’“”\\\"'`].*")) return Optional.empty();
        String value = canonical(text).replaceFirst("[。？?]+$", "");
        if (CONDITIONAL.matcher(value).find()) return Optional.empty();
        var declaration = DECLARATION.matcher(value);
        if (declaration.matches()) value = declaration.group(1);
        else if (question) value = value.replaceFirst(
                "(?:(?:应|应该)?如何(?:确定|确认|设定|定义)|是多少(?:分钟)?|是什么)$", "");
        else return Optional.empty();
        value = value.replace("观察窗口的起点事件", "观察起点")
                .replace("观察窗口的终点事件", "观察终点")
                .replace("观察窗口的起点", "观察起点")
                .replace("观察窗口的终点", "观察终点")
                .replace("观察的日期范围", "观察日期范围")
                .replaceFirst("观察窗口时长$", "观察窗口")
                .replaceFirst("的具体数值和单位$", "")
                .replaceFirst("的统计分母$", "的分母");
        // 复合条件、事件描述与有值比较不属于一个纯参数名，继续由原绑定语境处理。
        if (value.matches(".*[，,。；;：:（）()<>≤≥].*")) return Optional.empty();
        List<Parameter> named = namedParameters(value).stream()
                .map(parameter -> independentHospitalParameters ? boundParameterName(parameter) : parameter).toList();
        return named.size() == 1 ? Optional.of(named.getFirst()) : Optional.empty();
    }

    /** 仅在本次明确的候诊比较任务中接受其完整窗口别名，不扩展到其他指标、年份或未具名范围。 */
    private Parameter boundParameterName(Parameter parameter) {
        Parameter normalized = independentHospitalName(parameter);
        if (hospitalWaitingComparison && normalized.property().equals("观察窗口")
                && normalized.subject().matches("(?:甲院|乙院)(?:的)?(?:用于(?:两院)?比较|候诊时间(?:统计)?(?:的)?比较)")) {
            return new Parameter(normalized.subject().substring(0, 2), "观察窗口");
        }
        if (hospitalWaitingComparison && normalized.property().equals("阈值")
                && normalized.subject().matches("(?:甲院|乙院)(?:的)?(?:判定|用于(?:判定|标记))?"
                        + "异常等待(?:时间)?(?:的)?(?:判定)?")) {
            return new Parameter(normalized.subject().substring(0, 2) + "异常等待", "阈值");
        }
        return normalized;
    }

    /** 只对应已经明确展开的完整槽位，额外年份、亚类或另一属性不能继承此别名。 */
    private static boolean isIndependentHospitalParameter(Parameter parameter) {
        return parameter.property().equals("观察窗口") && parameter.subject().matches("甲院|乙院")
                || parameter.property().equals("阈值") && parameter.subject().matches("(?:甲院|乙院)异常等待");
    }

    /**
     * 同步当前执行正文中的公共旧未知，避免参数表已更新而任务仍说两院都未定。
     * 仅使用已登记的独立范围与真实选值；原始材料、引文、代码及未来条件不在此改写。
     */
    String reconcileCurrentText(String text) {
        if (text == null || !independentHospitalParameters || confirmedValues.isEmpty()) return text;
        var lines = new ArrayList<String>();
        String fence = null;
        for (String line : text.split("\n", -1)) {
            String stripped = line.strip();
            var marker = FENCE.matcher(stripped);
            if (marker.matches()) {
                String token = marker.group(1);
                if (fence == null) fence = token;
                else if (token.charAt(0) == fence.charAt(0) && token.length() >= fence.length()
                        && marker.group(2).isBlank()) fence = null;
                lines.add(line);
                continue;
            }
            if (fence != null || stripped.matches("^[>‘’“”\\\"'`|].*")) {
                lines.add(line);
                continue;
            }
            var result = new StringBuilder();
            var sentences = Pattern.compile("[^。；;]+(?:[。；;]|$)|[。；;]").matcher(line);
            while (sentences.find()) {
                // 分号并不终止假设、引用或年份的作用域；宁可保留材料原句，也不能跨句升级状态。
                String prefix = line.substring(0, sentences.start());
                boolean scoped = CONDITIONAL.matcher(prefix).find()
                        || prefix.matches("(?s).*(?:19|20)\\d{2}年.*");
                result.append(scoped ? sentences.group() : reconcileCurrentSentence(sentences.group()));
            }
            lines.add(result.toString());
        }
        return String.join("\n", lines);
    }

    /** 完整公共参数只在至少一项实际确认时展开；保留其后业务限制，条件及年份语境不消费。 */
    private String reconcileCurrentSentence(String sentence) {
        var result = new StringBuilder();
        var pending = CURRENT_PENDING_CLAUSE.matcher(sentence);
        while (pending.find()) {
            if (CONDITIONAL.matcher(sentence.substring(0, pending.start(3))).find()
                    || sentence.matches("(?s).*(?:19|20)\\d{2}年.*")) continue;
            List<Parameter> scopes = namedParameters(canonical(pending.group(3))).stream()
                    .flatMap(value -> hospitalParameterScopes(value).stream()).toList();
            if (scopes.isEmpty() || scopes.stream().noneMatch(confirmedParameters::contains)
                    || scopes.stream().anyMatch(value -> !parameters.contains(value) && !confirmedParameters.contains(value))) continue;
            String current = scopes.stream().map(parameter -> confirmedValues.stream()
                    .filter(value -> value.parameter().equals(parameter)).findFirst()
                    .map(value -> parameter.subject() + "的" + parameter.property() + "采用" + value.value())
                    .orElse(parameter.subject() + "的" + parameter.property() + "尚未确定"))
                    .collect(java.util.stream.Collectors.joining("；"));
            pending.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(pending.group(1) + pending.group(2) + current));
        }
        pending.appendTail(result);
        return result.toString();
    }

    /** 两院概述仅在两院各自参数已独立登记时被覆盖；新年份、新院及新增条件仍保留原提醒。 */
    boolean coveredByBoundPending(String statement, ConfirmedDecisionSet decisions) {
        if (!twoNamedHospitals) return false;
        String text = canonical(statement).replace("的", "");
        String property = text.matches("^两院(?:比较)?观察窗口(?:尚未|未)确定[。]?$" ) ? "观察窗口"
                : text.matches("^异常等待阈值(?:尚未|未)确定[。]?$" ) ? "阈值" : null;
        if (property == null) return false;
        return List.of("甲院", "乙院").stream().allMatch(owner -> decisions.pendingDecisions().stream().anyMatch(decision -> {
            String scope = decision.question() + " " + decision.answer();
            return scope.contains(owner) && scope.contains(property)
                    && (property.equals("观察窗口") || scope.contains("异常等待"))
                    && !scope.matches("(?s).*(?:19|20)\\d{2}年.*");
        }));
    }

    /** 只有本次明确要求及有效答案能够产生“用户已确认”范围，资料不能自行充当用户授权。 */
    private static List<ParameterValue> currentParameterValues(String text) {
        var result = new ArrayList<ParameterValue>();
        for (String sentence : currentDecisionSentences(text)) {
            // 仅移除肯定的当前确认前缀，完整保留机构、年份、指标和属性，不扩大到其他参数。
            var confirmation = CURRENT_CONFIRMATION.matcher(canonical(sentence));
            boolean explicitlyConfirmed = confirmation.find();
            String clause = confirmation.replaceFirst("");
            // 数值前置是当前明确选择的语法变体，不将资料中的“24小时窗口已批准”当作本次选择。
            var prefixedWindow = PREFIXED_WINDOW_CHOICE.matcher(clause);
            if (explicitlyConfirmed && !CONDITIONAL.matcher(clause).find() && !PENDING.matcher(clause).find()
                    && !clause.matches(".*(?:建议|候选|示例).*") && prefixedWindow.matches()) {
                result.add(new ParameterValue(new Parameter(subjectKey(prefixedWindow.group(1).replaceFirst("的$", "")),
                        "观察窗口"), prefixedWindow.group(2)));
            }
            var choices = CURRENT_PARAMETER.matcher(clause);
            while (choices.find()) {
                if (CONDITIONAL.matcher(clause.substring(0, choices.end())).find()
                        || choices.group().matches(".*(?:尚未|待确认|未知|未决|建议|候选|例如|示例).*")) continue;
                String subject = choices.group(1).replaceFirst("的$", "");
                if (subject.matches(".*(?:与|和|及|其他|其余|本次只|仅).*")) continue;
                String value = choices.group(3);
                // 选值后的纳入／排除限定属于这个口径；不将随后独立的指标选择或未来条件拼入当前取值。
                var qualifiers = Pattern.compile("^[，,]((?:包括|包含|不包括|不含|排除|仅含)[^，,。；;]+)")
                        .matcher(clause.substring(choices.end()));
                if (qualifiers.find() && !qualifiers.group(1).matches(".*(?:如果|以后|若|候选|尚未|待确认).*")) {
                    value += "，" + qualifiers.group(1);
                }
                result.add(new ParameterValue(new Parameter(subjectKey(subject), choices.group(2)), value));
            }
        }
        return result;
    }

    /** 旧窗口请求只借同一完整对象的唯一有效选值更新，不由资料批准、假设或另一院选择推断。 */
    static Optional<String> selectedWindowValue(String subject, List<com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision> decisions) {
        var values = decisions.stream().filter(decision -> decision.scope()
                        != com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope.CURRENT_STATE)
                .flatMap(decision -> currentParameterValues(decision.scope()
                        == com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision.Scope.UNRESOLVED
                        ? PlanAnswerSemantics.confirmedPart(decision.answer()) : decision.answer()).stream())
                .filter(value -> value.parameter().equals(new Parameter(subjectKey(subject), "观察窗口")))
                .map(ParameterValue::value).distinct().toList();
        return values.size() == 1 ? Optional.of(values.getFirst()) : Optional.empty();
    }

    /**
     * 在归一化前排除引文和围栏示例，避免去掉引号后把示例值当成用户选择。
     * 围栏按类型和长度配对，关闭后的真实陈述继续参与核对；未关闭的示例不建立确认。
     */
    private static List<String> currentDecisionSentences(String text) {
        if (text == null || text.isBlank()) return List.of();
        var result = new ArrayList<String>();
        String fence = null;
        for (String line : text.lines().toList()) {
            String current = line.strip();
            var marker = FENCE.matcher(current);
            if (marker.matches()) {
                if (fence == null) fence = marker.group(1);
                else if (marker.group(1).charAt(0) == fence.charAt(0)
                        && marker.group(1).length() >= fence.length() && marker.group(2).isBlank()) fence = null;
                continue;
            }
            if (fence != null) continue;
            for (String sentence : current.split("[。；;]+")) {
                String clause = sentence.strip().replaceFirst("^(?:[-*•]\\s+|#+\\s+|\\d+[.)、]\\s*)", "");
                if (!clause.isBlank() && !clause.matches("^[>‘’“”\\\"'`].*")) result.add(clause);
            }
        }
        return List.copyOf(result);
    }

    /** 并列声明只分开明确具名的对象和共同属性，不把类别相同或省略对象的代词当成同一决定。 */
    private static List<Parameter> namedParameters(String declaration) {
        var complete = PARAMETER.matcher(declaration);
        if (!complete.matches()) return List.of();
        String property = complete.group(2);
        return Arrays.stream(complete.group(1).split("与|和|及|、"))
                .map(value -> value.replaceFirst("(?:的)?" + Pattern.quote(property) + "$", "").replaceFirst("的$", ""))
                .filter(value -> value.length() >= 2 && !value.matches(".*(?:是否|如何|哪些|其他|其余|这个|该项).*"))
                .map(value -> new Parameter(subjectKey(value), property)).toList();
    }

    /** 具名未知用于统一提醒；它不是根据缺少信息推断出的新事实。 */
    List<String> pendingStatements() {
        return parameters.stream().map(value -> value.subject() + "的" + value.property() + "尚未确定。").toList();
    }

    boolean hasNamedParameters() {
        return !parameters.isEmpty() || !confirmedParameters.isEmpty();
    }

    /** Plan仅识别本次实际登记的具名未知，不从类别唯一值或另一对象推断该项已交付给执行者。 */
    boolean namesPendingParameter(String name) {
        List<Parameter> named = namedParameters(canonical(name)).stream()
                .map(parameter -> independentHospitalParameters ? boundParameterName(parameter) : parameter).toList();
        return named.size() == 1 && parameters.contains(named.getFirst());
    }

    /** 同名完整未知通过对象与属性核对；状态谓词差异不创建新决定，附带说明不在此删除。 */
    boolean samePendingStatement(String finding, String statement) {
        var first = purePendingParameter(finding);
        var second = purePendingParameter(statement);
        return first.isPresent() && first.equals(second);
    }

    /** 仅识别完整的一项纯未知陈述，引文、假设、复合对象和说明尾句不能冒充短状态。 */
    private Optional<Parameter> purePendingParameter(String statement) {
        if (statement == null || statement.strip().matches("^[>‘’“”\\\"'`].*")) return Optional.empty();
        String value = canonical(statement).replaceFirst("[。？?]+$", "");
        if (CONDITIONAL.matcher(value).find()) return Optional.empty();
        var declaration = DECLARATION.matcher(value);
        if (!declaration.matches()) return Optional.empty();
        List<Parameter> named = namedParameters(declaration.group(1)).stream()
                .map(parameter -> independentHospitalParameters ? boundParameterName(parameter) : parameter).toList();
        return named.size() == 1 ? Optional.of(named.getFirst()) : Optional.empty();
    }

    /** 已保留的完整说明覆盖同一纯状态时，只删除短状态，不截断其中的审批、新条件或专业解释。 */
    private List<String> withoutCoveredPureStatuses(List<String> reminders) {
        return reminders.stream().filter(reminder -> {
            var target = purePendingParameter(reminder);
            if (target.isEmpty()) return true;
            return reminders.stream().filter(other -> !other.equals(reminder))
                    .noneMatch(other -> purePendingParameter(other).isEmpty() && coversPendingStatement(other, reminder));
        }).toList();
    }

    /** 同一未知若已在带新说明的提醒中登记，只避免追加第二条短状态，不裁掉任何新说明。 */
    boolean coversPendingStatement(String finding, String statement) {
        if (finding == null || finding.strip().matches("^[>‘’“”\\\"'`].*")) return false;
        // “分母口径尚未确定”只用于证明已有同项说明覆盖短状态；不修改原句或放宽具体口径校验。
        String observed = finding.replaceAll("分母口径(?=(?:尚未|仍未|暂未|未)(?:确定|决定|核实|明确|确认|提供))", "分母");
        return samePendingStatement(observed, statement) || coversIndependentPair(observed, statement) || declaredPending(observed).stream()
                .anyMatch(declaration -> samePendingStatement(declaration, statement));
    }

    /**
     * 明确列出两院且两项当前均未决的完整说明，已覆盖各院的短状态，不重复补四条。
     * 保留原说明及全部新条件；部分确认、引文和年份限定不能按公共未知覆盖当前参数。
     */
    private boolean coversIndependentPair(String finding, String statement) {
        if (!independentHospitalParameters || finding.strip().matches("^[>‘’“”\\\"'`].*")
                || finding.matches("(?s).*(?:19|20)\\d{2}年.*")) return false;
        var declaration = DECLARATION.matcher(canonical(statement).replaceFirst("[。]$", ""));
        if (!declaration.matches()) return false;
        List<Parameter> named = namedParameters(declaration.group(1));
        if (named.size() != 1) return false;
        Parameter expected = independentHospitalName(named.getFirst());
        String suffix;
        List<Parameter> pair;
        if (expected.property().equals("观察窗口") && expected.subject().matches("甲院|乙院")) {
            suffix = "(?:比较)?观察窗口";
            pair = List.of(new Parameter("甲院", "观察窗口"), new Parameter("乙院", "观察窗口"));
        } else if (expected.property().equals("阈值") && expected.subject().matches("(?:甲院|乙院)异常等待")) {
            suffix = "异常等待(?:的)?阈值";
            pair = List.of(new Parameter("甲院异常等待", "阈值"), new Parameter("乙院异常等待", "阈值"));
        } else return false;
        if (!parameters.containsAll(pair)) return false;
        String owners = "^(?:甲院(?:和|与|、)乙院|乙院(?:和|与|、)甲院)(?:的)?";
        String observed = canonical(finding);
        if (observed.matches(owners + suffix
                + "(?:均|都|分别)?(?:尚未|仍未|暂未|未)(?:确定|决定|核实|明确|确认|提供)(?:$|[，,。；;].*)")) return true;
        // 完整并列问题已分别登记两项未知，只免于追加短状态；保留原问题与示例、条件等全部解释。
        // 必须出现“分别／各自”，共同数值选择不能冒充两个独立决定，已回答任一项时也不适用。
        String questionProperty = expected.property().equals("观察窗口")
                ? "(?:用于(?:两院)?比较的|比较)?观察窗口" : "(?:判定)?异常等待(?:的)?阈值";
        return observed.matches(owners + questionProperty
                + "(?:分别|各自)(?:是什么|是多少(?:分钟)?|如何(?:确定|确认))[？?](?:$|.*)");
    }

    /**
     * 完整具名未知只在统一执行前提中说明。独立整句才参与精简，表格、代码、条件或附带新信息不删。
     * 原始资料留在证据卡片，当前正文不需要再抄一份同义状态。
     */
    void compactPendingStatements(Map<PromptSectionType, PromptSection> sections) {
        if (parameters.isEmpty()) return;
        sections.replaceAll((type, section) -> {
            if (type == PromptSectionType.CLARIFICATIONS) return section;
            var retained = new ArrayList<String>();
            boolean code = false;
            for (String line : section.content().lines().toList()) {
                if (line.strip().startsWith("```")) code = !code;
                if (code || line.strip().startsWith("```") || line.strip().startsWith("|") || line.strip().startsWith(">")) {
                    retained.add(line);
                    continue;
                }
                // 来源前缀只用于核对独立摘录，不能截断含冒号的条件或业务断言。
                String candidate = line.strip().replaceFirst("^[-*•]\\s+", "");
                if (candidate.matches("[^：:]+\\.(?:md|txt|docx|pdf)[：:].*")) candidate = candidate.substring(candidate.indexOf('：') >= 0
                        ? candidate.indexOf('：') + 1 : candidate.indexOf(':') + 1).strip();
                String comparison = candidate;
                if (pendingStatements().stream().noneMatch(statement -> samePendingStatement(comparison, statement))) retained.add(line);
            }
            String content = String.join("\n", retained).strip();
            return content.isBlank() ? section : new PromptSection(type, section.title(), content);
        });
    }

    /** 只继承独立的明确未知陈述，不从关键词缺失、假设句、引文或数据代码制造问题。 */
    static List<String> declaredPending(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        var result = new ArrayList<String>();
        for (String sentence : raw.split("[。；;\\r\\n]+")) {
            String value = sentence.strip().replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", "");
            if (value.startsWith(">") || value.startsWith("```")) continue;
            var declarations = DECLARATION.matcher(value);
            while (declarations.find()) {
                // “尚未决定，不能继承另一指标”先声明真实未知，再限制使用；后半句不应抹掉前半句。
                // 假设、引用或禁止位于当前断言之前时仍不建立事实，不能把条件内的未知当成现状。
                if (CONDITIONAL.matcher(value.substring(0, declarations.end())).find()) continue;
                String subject = declarations.group(1).strip();
                if (subject.matches(".*(?:分母|阈值|观察窗口|覆盖度|观察起点|观察终点|观察日期范围|插补|方法|审批标准|退款标准)$")) {
                    result.add(declarations.group().strip() + "。");
                }
            }
        }
        return result.stream().distinct().toList();
    }

    /** 具名未决参数随复制正文保留，避免下游只看到通用原则而漏掉具体指标的状态。 */
    String deliveryGuidance() {
        String named = parameters.stream().map(parameter -> parameter.subject() + "的" + parameter.property() + "：待确认")
                .collect(java.util.stream.Collectors.joining("；"));
        String confirmed = confirmedParameters.stream().map(parameter -> parameter.subject() + "的" + parameter.property())
                .collect(java.util.stream.Collectors.joining("、"));
        return baseDeliveryGuidance() + (named.isBlank() ? "" : "本次尚未确定的参数：" + named + "。")
                + (confirmed.isBlank() ? "" : "本次需求或有效回答明确的参数范围仅包含：" + confirmed + "；不得扩大到其他指标。")
                + independentEvidenceGuidance();
    }

    /** 模型前指导和最终复制正文使用同一交付范围；有指标表不意味着也要求伪代码，反之亦然。 */
    String baseDeliveryGuidance() {
        return DELIVERY_GUIDANCE
                + observationBoundaryGuidance()
                + (indicatorTableRequested ? "原定指标表仍须交付，逐指标说明依据、参数状态和计算前提。" : "")
                + (pseudocodeRequested ? "原定伪代码仍须交付，只暂停依赖未决参数的计算，其余已要求步骤继续完成。" : "");
    }

    /** 窗口时长只确定自身参数；缺失起止事件或日期范围不能由年份、惯例或示例补成事实。 */
    private String observationBoundaryGuidance() {
        boolean observation = java.util.stream.Stream.concat(parameters.stream(), confirmedParameters.stream())
                .anyMatch(parameter -> parameter.property().startsWith("观察"));
        return observation ? "窗口时长不证明观察起止事件、采集日期范围或其他指标分母。"
                + "未提供的边界保持未知；候选定义明确标为建议，另行确认后才用于依赖它的计算。" : "";
    }

    /** 只有原定指标表增加逐行依据要求；参数视图是执行依据，不新增临床、法律或其他指标任务。 */
    String independentEvidenceGuidance() {
        return (indicatorTableRequested ? "新增指标必须独立列出口径依据及状态：无独立依据时，具体口径只能列为建议，执行参数保持待确认；"
                + "已知字段格式只证明校验规则，不能确认格式正确率等新增指标的分母。"
                + "指标表逐行包含指标、分子、分母或所需参数、适用记录、口径依据、状态；"
                + "已具备规则但未确定统计参数的行仍交付规则与待确认参数，不省略指标表。"
                + "计数指标本身不需要分母时写“不适用（计数指标）”，不能强行增加分母或改成率指标。"
                + MetricCalculationContract.GUIDANCE : "")
                + (indicatorTableRequested && formatParameterNotEvidenced ? "本次参数依据没有登记格式类指标的独立分母；"
                + "新增格式率指标的执行分母填写“待确认”，依据列写“本次材料未提供独立口径”，不能借日期格式或完整性分母标为已确认。" : "")
                + parameterStateTable();
    }

    /** 当前参数视图随复制正文保留；完整名称与原选值一起提供，来源状态不互相升级。 */
    private String parameterStateTable() {
        var rows = new ArrayList<String>();
        confirmedValues.forEach(value -> rows.add(parameterRow(value.parameter(), value.value(), "用户明确（原始需求或本次回答）")));
        materialValues.forEach(value -> rows.add(parameterRow(value.parameter(), value.value(), "资料明确；不是用户确认")));
        parameters.forEach(value -> rows.add(parameterRow(value, "待确认", "未决；无当前选值")));
        if (rows.isEmpty()) return "";
        return (indicatorTableRequested
                ? "\n\n以下参数依据只作为输入，合入最终指标表；除用户明确要求，不再重复交付同一参数表或多份待确认清单。\n"
                    + "当前参数依据（用于生成指标表，不代替指标表）：\n"
                : "\n\n以下参数依据只作为输入，按本次已要求的形式表达；不新增指标表、计算或其他未要求的交付，不重复交付同一参数表或待确认清单。\n"
                    + "当前参数依据（用于核对原定交付，不新增交付物）：\n")
                + "| 参数 | 当前口径 | 状态与依据 |\n| --- | --- | --- |\n" + String.join("\n", rows);
    }

    /** 仅转义表格分隔符，保留对象、年份、条件与完整取值，不从相同类别推断其他参数。 */
    private static String parameterRow(Parameter parameter, String value, String state) {
        return "| " + parameter.subject() + "的" + parameter.property() + " | "
                + value.replace("|", "&#124;") + " | " + state + " |";
    }

    /** 模型自己的确定口径与明确未决状态冲突时进入既有修复预算，不靠附加未知尾注放行。 */
    void validate(String content, String field) {
        if (content == null) return;
        if (indicatorTableRequested) MetricCalculationContract.validate(content, field);
        List<Parameter> validationParameters = parametersWithSharedBoundary();
        List<String> header = List.of();
        for (String line : content.lines().toList()) {
            String text = line.strip();
            if (text.startsWith("|")) {
                List<String> cells = cells(text);
                if (cells.stream().anyMatch(value -> value.equals(COMPOSITE_PARAMETER_COLUMN)
                        || value.matches("(?:分母|阈值|观察窗口|覆盖度|观察起点|观察终点|观察日期范围)(?:口径|定义)?"))) {
                    header = cells.stream().map(value -> value.equals("指标名称") ? "指标"
                            : value.replaceFirst("(?<=分母|阈值|观察窗口|覆盖度|观察起点|观察终点|观察日期范围)(?:口径|定义)$", "")).toList();
                    continue;
                }
                if (text.matches("[|:\\-\\s]+") || header.isEmpty() || cells.size() != header.size()) continue;
                for (Parameter parameter : validationParameters) {
                    int index = parameterColumn(header, parameter.property());
                    if (index < 0) continue;
                    boolean sameObject = java.util.stream.IntStream.range(0, cells.size())
                            .filter(column -> column != index).anyMatch(column -> sameSubject(cells.get(column), parameter.subject(), parameter.property()));
                    if (sameObject && !unknownCell(cells.get(index))) reject(field, "PENDING_TABLE_CELL");
                }
                // 状态列的“已确认”必须有同指标、同属性的本次决定，不能借另一行的确认。
                int subjectColumn = header.indexOf("指标");
                boolean claimsConfirmed = cells.stream().anyMatch(cell -> CONFIRMED_CELL.matcher(cell).matches());
                if (subjectColumn >= 0 && claimsConfirmed) {
                    for (String property : PARAMETER_PROPERTIES) {
                        var claimed = new Parameter(subjectKey(cells.get(subjectColumn)), property);
                        if (independentHospitalParameters) claimed = boundParameterName(claimed);
                        if (header.contains(property) && !confirmedParameters.contains(claimed)) reject(field, "TABLE_CONFIRMATION_SCOPE");
                    }
                    validateCompositeConfirmation(header, cells, subjectColumn, field);
                }
            } else {
                header = List.of();
                for (String clause : text.split("[，,。；;]+")) {
                    if (CONDITIONAL.matcher(clause).find() || PENDING.matcher(clause).find()) continue;
                    String compact = canonical(clause).replaceFirst("^[-*#]+", "");
                    var assertions = ASSERTED_PARAMETER.matcher(compact);
                    while (assertions.find()) {
                        String subject = assertions.group(1) == null ? assertions.group(3) : assertions.group(1);
                        String property = assertions.group(2) == null ? assertions.group(4) : assertions.group(2);
                        // 否认窗口确认能推导出另一参数，不是确认该参数；逐项核对，转折后的真实断言仍须验证。
                        if (deniesWindowConfirmationInference(subject)) continue;
                        var claimed = new Parameter(subjectKey(subject.replaceFirst("的$", "")), property);
                        if (independentHospitalParameters) claimed = boundParameterName(claimed);
                        if (!isConfirmedNarrativeParameter(claimed)) reject(field, "NARRATIVE_CONFIRMATION_SCOPE");
                    }
                    // 建议可以提出备选定义，但不能冒充当前执行参数；上面的确认断言仍独立校验。
                    if (compact.matches("^(?:建议|候选方案|备选方案)[：:]?.*")) continue;
                    for (Parameter parameter : validationParameters) {
                        String target = assignmentTarget(parameter);
                        if (Pattern.compile(target + "(?:采用|取|为|是|=|设为|定义为)(?=.+)").matcher(compact).find()) reject(field, "PENDING_PARAMETER_ASSIGNMENT");
                    }
                }
            }
        }
    }

    /** 数值前置断言须同时匹配完整对象和实际选值；相同机构的另一个窗口值不能借用确认。 */
    private boolean isConfirmedNarrativeParameter(Parameter claimed) {
        if (confirmedParameters.contains(claimed)) return true;
        if (!claimed.property().equals("观察窗口")) return false;
        var prefixed = PREFIXED_WINDOW_SUBJECT.matcher(claimed.subject());
        if (!prefixed.matches()) return false;
        var parameter = new Parameter(subjectKey(prefixed.group(1).replaceFirst("的$", "")), "观察窗口");
        return confirmedValues.stream().anyMatch(value -> value.parameter().equals(parameter)
                && value.value().equals(prefixed.group(2)));
    }

    /** 已确认窗口的比较说明仍须有对应选值；未知48小时不能借24小时的确认建立此类陈述。 */
    private boolean deniesWindowConfirmationInference(String subject) {
        var denial = DENIED_WINDOW_CONFIRMATION.matcher(subject);
        if (!denial.matches()) return false;
        if (denial.group(1) == null) return true;
        return confirmedValues.stream().filter(value -> value.parameter().property().equals("观察窗口"))
                .anyMatch(value -> denial.group(2) == null || value.value().equals(denial.group(2)));
    }

    /** 独立属性列优先；平台规定的复合参数列不能成为绕过未决格校验的另一种表头。 */
    private static int parameterColumn(List<String> header, String property) {
        int dedicated = header.indexOf(property);
        return dedicated >= 0 ? dedicated : header.indexOf(COMPOSITE_PARAMETER_COLUMN);
    }

    /**
     * 复合列先按完整对象登记的属性核对，不将已确认阈值误分类为分母。
     * 未登记的新率指标不能借同类确认；只有明确计数行与不适用格同时出现才免分母要求。
     */
    private void validateCompositeConfirmation(List<String> header, List<String> cells, int subjectColumn, String field) {
        int column = header.indexOf(COMPOSITE_PARAMETER_COLUMN);
        if (column < 0) return;
        String subject = cells.get(subjectColumn);
        if (subject.matches(".*(?:记录数|人数|例数|次数|总数|计数|数量|个数)$")
                && cells.get(column).matches("不适用(?:$|[（(](?:计数指标|无需分母|无分母)[）)])")) return;
        List<Parameter> scoped = java.util.stream.Stream.concat(parameters.stream(),
                java.util.stream.Stream.concat(confirmedParameters.stream(), materialValues.stream().map(ParameterValue::parameter)))
                .filter(parameter -> sameSubject(subject, parameter.subject(), parameter.property())).distinct().toList();
        // 缺少完整对象依据时沿用分母列的确认边界，不按唯一类别值或另一机构推断本行已确认。
        if (scoped.isEmpty() || scoped.stream().anyMatch(parameter -> !confirmedParameters.contains(parameter))) {
            reject(field, "TABLE_CONFIRMATION_SCOPE");
        }
    }

    /**
     * 展开对象不能解除公共赋值的旧保护：任一院未决时，无机构归属的共同口径仍须等待确认。
     * 这些别名仅用于拦截赋值，不进入参数表、不产生选值，也不扩大已确认范围。
     */
    private List<Parameter> parametersWithSharedBoundary() {
        if (!independentHospitalParameters) return parameters;
        var boundaries = new ArrayList<>(parameters);
        if (parameters.contains(new Parameter("甲院", "观察窗口"))
                || parameters.contains(new Parameter("乙院", "观察窗口"))) {
            boundaries.add(new Parameter("两院", "观察窗口"));
            boundaries.add(new Parameter("两院比较", "观察窗口"));
        }
        if (parameters.contains(new Parameter("甲院异常等待", "阈值"))
                || parameters.contains(new Parameter("乙院异常等待", "阈值"))) {
            boundaries.add(new Parameter("异常等待", "阈值"));
        }
        return List.copyOf(boundaries);
    }

    /** 同一个受限参数别名在提醒、表格与赋值校验中一致处理，不能成为规避未知保护的写法。 */
    private String assignmentTarget(Parameter parameter) {
        if (independentHospitalParameters && parameter.property().equals("阈值")
                && parameter.subject().matches("(?:甲院|乙院)异常等待")) {
            return subjectPattern(parameter.subject().replaceFirst("异常等待$", ""))
                    + "(?:的)?" + (hospitalWaitingComparison ? "(?:判定|用于(?:判定|标记))?" : "")
                    + "异常等待" + (hospitalWaitingComparison ? "(?:时间)?(?:的)?(?:判定)?" : "") + "(?:的)?阈值";
        }
        String alias = independentHospitalParameters && parameter.property().equals("观察窗口")
                && parameter.subject().matches("甲院|乙院") ? hospitalWaitingComparison
                ? "(?:比较|用于(?:两院)?比较|候诊时间(?:统计)?(?:的)?比较)?(?:的)?" : "(?:比较)?(?:的)?" : "";
        return subjectPattern(parameter.subject()) + "(?:的)?" + alias + Pattern.quote(parameter.property());
    }

    /** 具体口径不能通过追加“尚待确认”来伪装成空参数，未知格只允许状态和等待说明。 */
    private boolean unknownCell(String value) {
        String text = canonical(value);
        return Pattern.compile("^(?:待确认|待定|未决|尚未确定|尚未决定|待核实|TBD|None|null)"
                        + "(?:$|[（(，,；;：:]?(?:确认|核实|不|不能|不得|需|须|尚|仍|另|等待|不可|按).*)", Pattern.CASE_INSENSITIVE)
                .matcher(text).matches() && !text.matches(".*(?:记录数|样本数|len\\(|count\\(|\\d+(?:分钟|小时|%)).*");
    }

    /** 完整具名对象逐字核对；有限的跨字段一致性子指标别名只覆盖该同类指标。 */
    private boolean sameSubject(String candidate, String subject, String property) {
        String name = canonical(candidate).replaceAll("[*`\\s]", "");
        if (independentHospitalParameters) name = boundParameterName(new Parameter(subjectKey(name), property)).subject();
        return subjectKey(name).equals(subject)
                || subject.equals("跨字段一致性指标") && name.equals("出院日期早于入院日期不一致率");
    }

    /** 仅规范完整一致性指标别名；机构、年份及其他前缀逐字保留，不能消除跨对象边界。 */
    private static String subjectKey(String name) {
        return name.replace("完整性指标(缺失率)", "完整性指标")
                .replaceFirst("完整性$", "完整性指标")
                .replaceFirst("(?:跨字段(?:逻辑)?)?一致性(?:指标|率)$", "跨字段一致性指标");
    }

    /** 叙述允许同一完整别名，不把另一具名对象包含短名称误当成本参数。 */
    private static String subjectPattern(String subject) {
        String suffix = "跨字段一致性指标";
        if (subject.endsWith(suffix)) {
            return "(?<![\\p{L}\\d])" + Pattern.quote(subject.substring(0, subject.length() - suffix.length()))
                    + "(?:跨字段(?:逻辑)?)?一致性(?:指标|率)";
        }
        if (subject.endsWith("完整性指标")) {
            return "(?<![\\p{L}\\d])" + Pattern.quote(subject.substring(0, subject.length() - "完整性指标".length()))
                    + "完整性(?:指标)?";
        }
        return "(?<![\\p{L}\\d])" + Pattern.quote(subject);
    }

    private static List<String> cells(String row) {
        return Arrays.stream(row.substring(1, row.endsWith("|") ? row.length() - 1 : row.length()).split("\\|", -1))
                .map(String::strip).map(UnresolvedDecisionContract::canonical).toList();
    }

    private static String canonical(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[\\s*`“”]", "");
    }

    /** 仅记录固定的校验类型，不记录业务对象、模型正文或用户资料，便于定位误拦和真实违约。 */
    private static void reject(String field, String check) {
        LOGGER.warn("event=decision.delivery.validation_failed field={} check={}", field, check);
        throw new ProviderResponseValidationException(Reason.RULE_CONFLICT, field);
    }
}
