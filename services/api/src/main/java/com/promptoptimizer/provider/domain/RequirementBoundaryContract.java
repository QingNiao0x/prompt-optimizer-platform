package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.PlanningFactCard;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 保持要求的强度和确认范围：数据禁令不产生演示例外，选择条件不升级为禁止，方法不确认参数。
 * 只处理有明确输入依据的边界，不执行统计任务，也不推测任何用户选值。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class RequirementBoundaryContract {
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern NO_INVENTED_DATA = Pattern.compile(
            "(?:禁止|不得|不要|不允许|不能|不|拒绝)(?:使用|采用|提供|生成|构造)?(?:任何|虚构的)?(?:假设|虚构|模拟|编造)"
                    + "(?:(?:或|和|及|、)(?:假设|虚构|模拟|编造))*(?:的)?(?:数据|材料|信息|示例)"
                    + "|(?:假设|虚构|模拟)(?:的)?数据(?:不得|禁止|不能)使用"
                    + "|\\b(?:no|do not|never)\\b[^.\\r\\n]{0,25}\\b(?:hypothetical|fictional|synthetic|made-up)\\s+data\\b", FLAGS);
    private static final Pattern DATA = Pattern.compile("(?:虚构|假设|模拟|合成|编造)(?:的|演示|示例|验证)?数据"
            + "|\\b(?:hypothetical|fictional|synthetic|made-up)\\s+data\\b", FLAGS);
    private static final Pattern DATA_ACTION = Pattern.compile("(?:允许|可以|可用|可使|应使用|使用|构造|生成|编造|提供)"
            + "|\\b(?:allow|may|can|use|generate|construct|create|provide)\\b", FLAGS);
    private static final Pattern NEGATIVE_PREFIX = Pattern.compile("(?:不|不得|禁止|严禁|不能|不要|避免|拒绝)[^，,。；;\\r\\n]{0,8}$"
            + "|未(?:曾|实际)?$|\\b(?:no|not|never|without|avoid)\\b[^,.;\\r\\n]{0,12}$", FLAGS);
    private static final Pattern TEST_SELECTION = Pattern.compile(
            "(?:检验[^。；;\\r\\n]{0,35}(?:依据|根据|视)[^。；;\\r\\n]{0,20}(?:数据|分布)[^。；;\\r\\n]{0,20}(?:选择|确定))"
                    + "|(?:(?:依据|根据|视)[^。；;\\r\\n]{0,25}(?:数据|分布)[^。；;\\r\\n]{0,25}(?:选择|确定)[^。；;\\r\\n]{0,10}检验)"
                    + "|\\b(?:select|choose)\\b[^.;\\r\\n]{0,30}\\btests?\\b[^.;\\r\\n]{0,35}\\b(?:data|distribution)\\b", FLAGS);
    private static final Pattern NO_TEST_NAMES = Pattern.compile(
            "(?:不|不得|禁止|不要)(?:再|能|可)?(?:写|给出|列出|提供|出现)[^，,。；;\\r\\n]{0,12}(?:检验|测试)(?:方法)?(?:名称|名字)"
                    + "|(?:检验|测试)(?:方法)?名称[^，,。；;\\r\\n]{0,8}(?:不得|禁止|不能)(?:出现|列出|给出)"
                    + "|\\b(?:do not|never)\\b[^.;\\r\\n]{0,25}\\b(?:name|list|mention)\\b[^.;\\r\\n]{0,25}\\btests?\\b", FLAGS);
    private static final Pattern AGE_STANDARDIZATION = Pattern.compile("年龄标(?:准化|化)|age[- ]standardi[sz]", FLAGS);
    private static final Pattern STANDARDIZATION_BRANCH = Pattern.compile("(直接|间接)(?:年龄)?标(?:准化|化)");
    private static final Pattern POPULATION_SELECTION = Pattern.compile(
            "(?:采用|使用|选用|选定|以)([^，,。；;\\r\\n]{1,65}?)(?:作为|为)(?:年龄标准化的)?标准人口"
                    + "|标准人口(?:的选值)?\\s*(?:暂按|默认(?:采用)?|采用|使用|选用|选定|为|取自|[:：])\\s*([^，,。；;\\r\\n]{1,65})", FLAGS);
    private static final Pattern CANDIDATE_PREFIX = Pattern.compile(
            "(?:建议|可考虑|可以考虑|可选|候选|例如|比如|若|如果|假如|假设|仅当|只有|待确认后|确认后可)[^。；;\\r\\n]{0,65}$", FLAGS);
    private static final Pattern NOT_SELECTED = Pattern.compile("^(?:待确认|待核实|未知|未定|尚未|未提供|未指定|待定|\\[|【)", FLAGS);
    private final boolean noInventedData;
    private final boolean conditionalTestSelection;
    private final boolean ageStandardization;
    private final List<String> populations;
    private final List<String> selectedBranches;
    private final boolean userBansExampleData;
    private final boolean userQuestionAnswerWorkflow;

    private RequirementBoundaryContract(boolean noInventedData, boolean conditionalTestSelection,
            boolean ageStandardization, List<String> populations, List<String> selectedBranches, boolean userBansExampleData,
            boolean userQuestionAnswerWorkflow) {
        this.noInventedData = noInventedData;
        this.conditionalTestSelection = conditionalTestSelection;
        this.ageStandardization = ageStandardization;
        this.populations = List.copyOf(populations);
        this.selectedBranches = List.copyOf(selectedBranches);
        this.userBansExampleData = userBansExampleData;
        this.userQuestionAnswerWorkflow = userQuestionAnswerWorkflow;
    }

    /** 用户禁令只读原需求和实际回答；资料可证明参数取值，但不能替用户新增例外或禁止。 */
    public static RequirementBoundaryContract from(String prompt, List<ConfirmedPlanDecision> decisions,
            List<PlanningFactCard> facts) {
        String instructions = DraftDeliveryContract.instructionText(prompt);
        var confirmed = decisions == null ? List.<ConfirmedPlanDecision>of() : decisions;
        String source = instructions + confirmed.stream().map(decision -> DraftDeliveryContract.instructionText(decision.answer()))
                .collect(java.util.stream.Collectors.joining("\n", "\n", ""));
        List<String> populations = new ArrayList<>(selectedPopulations(source));
        if (facts != null) facts.stream().map(PlanningFactCard::evidence).map(DraftDeliveryContract::instructionText)
                .flatMap(value -> selectedPopulations(value).stream()).forEach(populations::add);
        return new RequirementBoundaryContract(prohibitsInventedData(source),
                TEST_SELECTION.matcher(source).find() && !NO_TEST_NAMES.matcher(source).find(),
                chosenAgeMethod(source, confirmed), populations.stream().distinct().toList(), selectedBranches(source, confirmed), bansAllExampleData(source),
                Pattern.compile("Plan\\s*问答|(?:让|由)用户[^。；;\\r\\n]{0,15}回答[^。；;\\r\\n]{0,35}增强提示词", FLAGS).matcher(source).find());
    }

    /** 与示例契约共用禁令识别，关闭示例也不意味着取消数据使用限制。 */
    public static boolean prohibitsInventedData(String instructions) {
        return NO_INVENTED_DATA.matcher(DraftDeliveryContract.instructionText(instructions)).find();
    }

    /** 只提供本次实际相关的规则，不向其他任务灌入统计参数或长缺项清单。 */
    public String guidance() {
        var rules = new ArrayList<String>();
        if (userQuestionAnswerWorkflow) rules.add("本次问答流程由增强器提出关键问题、用户回答，再将有效回答融入优化提示词；直接增强不主动发起这轮问答，但仍正常使用已经提供的需求与确认信息。Plan不能改为模型自行生成执行计划再作答。研究若要求同信息对照，两组接收并可使用同一原始任务和同一份实际确认信息，保留各自指定增强流程；不能改写其中一组的回答、另换任务或只增加某组修订轮次。");
        if (noInventedData) rules.add("不得使用假设或虚构数据，禁令适用于所有用途，包括测试和验证；不得缩为仅禁止冒充真实业务数据。"
                + (userBansExampleData ? "" : "不能由这项禁令推出禁止使用可核实的真实示例资料；其他明确的数据限制和权限要求照常保留。"));
        if (conditionalTestSelection) rules.add("具体检验依据未来数据分布和适用条件选择；可说明候选检验名称及适用前提，不预先选定，也不预设显著性。");
        if (ageStandardization && selectedBranches.isEmpty()) rules.add("年龄标准化仅确认方法类别，不能宣称直接或间接标准化方式也已确定；可解释候选方式和适用条件。");
        if (ageStandardization && populations.isEmpty()) rules.add("年龄标准化方法已确定，但标准人口的年份和来源仍未选定；可给有条件的候选建议，不默认采用某年人口，仅限制依赖该参数的计算，其他原定内容继续交付。");
        return String.join("\n", rules);
    }

    /** 自然表述已落实全部边界时不再补整句；只查当前指令，不用引文或示例充当覆盖。 */
    public String missingGuidance(String content) {
        String current = DraftDeliveryContract.instructionText(content);
        return guidance().lines().filter(rule -> {
            if (rule.startsWith("本次问答流程")) return !current.contains(rule);
            if (rule.startsWith("不得使用")) return !(prohibitsInventedData(current)
                    && current.matches("(?s).*(?:所有用途|全部用途|测试[^。；;]{0,12}验证[^。；;]{0,12}(?:不例外|均不能|不得使用)).*"));
            if (rule.startsWith("具体检验")) return !(TEST_SELECTION.matcher(current).find()
                    && Pattern.compile("候选(?:检验|方法|统计检验)[^。；;\\r\\n]{0,45}(?:适用|前提|条件)").matcher(current).find());
            if (rule.startsWith("年龄标准化仅确认")) return !current.contains(rule);
            // 标准人口的未定和不得默认采用须在同一命题，其他参数的未知不能冒充覆盖。
            return Pattern.compile("[。；;\\r\\n]+").splitAsStream(current).noneMatch(clause -> clause.contains("标准人口")
                    && clause.matches(".*(?:未选定|未确定|未指定|待确认).*" )
                    && clause.matches(".*(?:不默认|不得默认|不能默认|不得擅自|不擅自).*"));
        }).collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 在模型草稿和平台最终正文各核对一次；引用和围栏中的旧错误不算当前执行许可。 */
    public void validate(String content, String field) {
        String text = DraftDeliveryContract.instructionText(content);
        if (userQuestionAnswerWorkflow) validateInteractionActors(text, field);
        if (noInventedData && permitsInventedData(text)) reject(ProviderResponseValidationException.Reason.DATA_PROHIBITION_CONFLICT, field);
        if (noInventedData && !userBansExampleData && bansAllExampleData(text)) {
            reject(ProviderResponseValidationException.Reason.DATA_PROHIBITION_CONFLICT, field);
        }
        if (conditionalTestSelection && NO_TEST_NAMES.matcher(text).find()) reject(ProviderResponseValidationException.Reason.METHOD_CONDITION_CONFLICT, field);
        if (ageStandardization) {
            for (String branch : claimedBranches(text)) {
                if (!selectedBranches.contains(branch)) reject(ProviderResponseValidationException.Reason.METHOD_PARAMETER_CONFLICT, field);
            }
            for (String selected : selectedPopulations(text)) {
                if (!populations.contains(selected)) reject(ProviderResponseValidationException.Reason.METHOD_PARAMETER_CONFLICT, field);
            }
        }
    }

    /** 只拒绝本次明确用户问答被替换的断言；引文、否定说明和用户原定自规划任务不受影响。 */
    private static void validateInteractionActors(String text, String field) {
        Pattern changedActor = Pattern.compile("Plan(?:组|模式|步骤)?[^。；;\\r\\n]{0,50}(?:模型|研究者|撰写者)[^。；;\\r\\n]{0,20}(?:生成|撰写|制定|产出|列出)[^。；;\\r\\n]{0,8}(?:执行)?计划", FLAGS);
        for (String clause : text.split("[。；;\\r\\n]+")) {
            var match = changedActor.matcher(clause);
            while (match.find()) {
                String claim = match.group();
                // 否定必须在这次替换动作内，其他句末的通用保真声明不能撤销前面的错误。
                if (claim.matches(".*(?:不能|不得|不要|并非|不是|不应)[^。；;]{0,35}(?:生成|撰写|制定|产出|列出).*")) continue;
                reject(ProviderResponseValidationException.Reason.INTERACTION_ACTOR_CONFLICT, field);
            }
        }
    }

    /** 确定性声明与候选解释分开；不能把未选的具体方式写成用户已经确定。 */
    private static List<String> claimedBranches(String content) {
        var result = new ArrayList<String>();
        for (String clause : content.split("[。；;\\r\\n]+")) {
            var branch = STANDARDIZATION_BRANCH.matcher(clause);
            while (branch.find()) {
                String prefix = clause.substring(0, branch.start());
                if (NEGATIVE_PREFIX.matcher(prefix).find() || CANDIDATE_PREFIX.matcher(prefix).find()) continue;
                String suffix = clause.substring(branch.end());
                if (prefix.matches(".*(?:已确定|已确认|已选定)[^，,]{0,12}$")
                        || suffix.matches("^(?:方法|方式)?(?:也)?(?:已确定|已确认|已选定).*")) result.add(branch.group(1));
            }
        }
        return result;
    }

    /** 具体方式必须来自实际采用表述或绑定问题的明确短答，单纯出现名称不是选定证据。 */
    private static List<String> selectedBranches(String source, List<ConfirmedPlanDecision> decisions) {
        var result = new ArrayList<>(claimedBranches(source));
        for (String clause : source.split("[。；;\\r\\n]+")) {
            var branch = STANDARDIZATION_BRANCH.matcher(clause);
            while (branch.find()) {
                String prefix = clause.substring(0, branch.start());
                if (NEGATIVE_PREFIX.matcher(prefix).find() || CANDIDATE_PREFIX.matcher(prefix).find() || clause.contains("？")) continue;
                if (prefix.matches(".*(?:采用|使用|选定|选用)[^，,]{0,10}$")) result.add(branch.group(1));
            }
        }
        decisions.stream().filter(decision -> decision.question().matches(".*(?:标准化|标化|死亡率|方法).*"))
                .filter(decision -> decision.answer().strip().matches("(?:直接|间接)(?:年龄)?标(?:准化|化)[。.]?"))
                .map(decision -> decision.answer().strip().substring(0, 2)).forEach(result::add);
        return result.stream().distinct().toList();
    }

    /** 提及、候选或提问不等于采用；有效 Plan 的短答仍可明确选择方法。 */
    private static boolean chosenAgeMethod(String source, List<ConfirmedPlanDecision> decisions) {
        for (String clause : source.split("[。；;\\r\\n]+")) {
            var method = AGE_STANDARDIZATION.matcher(clause);
            while (method.find()) {
                String prefix = clause.substring(0, method.start());
                if (clause.contains("？") || clause.contains("?") || CANDIDATE_PREFIX.matcher(prefix).find()
                        || prefix.matches(".*(?:是否|不得|不要|禁止|不能|未确定|未确认)[^，,]{0,15}$")) continue;
                if (prefix.matches(".*(?:采用|使用|要求|按)[^，,]{0,12}$")
                        || clause.substring(method.end()).matches("^(?:方法)?(?:已确定|已确认).*")) return true;
            }
        }
        return decisions.stream().anyMatch(decision -> decision.answer().strip().matches("年龄标(?:准化|化)[。.]?")
                && decision.question().matches(".*(?:方法|死亡率|标化|标准化|粗率).*"));
    }

    /** 未决参数与有条件的候选不同于当前选定；“暂按某年，以后确认”仍是未经授权的采用。 */
    private static List<String> selectedPopulations(String text) {
        var values = new ArrayList<String>();
        for (String clause : text.split("[。；;\\r\\n]+")) {
            var selection = POPULATION_SELECTION.matcher(clause);
            while (selection.find()) {
                String prefix = clause.substring(0, selection.start());
                if (NEGATIVE_PREFIX.matcher(prefix).find() || CANDIDATE_PREFIX.matcher(prefix).find()) continue;
                String value = selection.group(1) == null ? selection.group(2) : selection.group(1);
                if (!NOT_SELECTED.matcher(value.strip()).find()) values.add(normalizePopulation(value));
            }
        }
        return values;
    }

    /** 对每个数据动作单独判否定，另一子句的“不得作为正式依据”不能掩盖实际使用许可。 */
    private static boolean permitsInventedData(String text) {
        for (String clause : text.split("[。；;\\r\\n]+")) {
            var data = DATA.matcher(clause);
            while (data.find()) {
                String prefix = clause.substring(0, data.start());
                int comma = Math.max(prefix.lastIndexOf('，'), prefix.lastIndexOf(','));
                var actions = DATA_ACTION.matcher(prefix.substring(comma + 1));
                while (actions.find()) {
                    String before = prefix.substring(comma + 1, comma + 1 + actions.start());
                    if (!NEGATIVE_PREFIX.matcher(before).find()) return true;
                }
                String after = clause.substring(data.end());
                if (after.matches("(?is)^\\s*(?:可以|可|允许|may|can)[^。；;]{0,12}(?:使用|用于|be used).*")) return true;
            }
        }
        return false;
    }

    /** 只识别无用途限定的全面禁例数据；“虚构示例”仍属于虚构禁令，不等于禁用真实样本。 */
    private static boolean bansAllExampleData(String text) {
        String normalized = text.replaceAll("(?:虚构|假设|模拟)(?:的)?(?:示例|演示|测试)数据", "虚构数据");
        return Pattern.compile("(?:不得|禁止|不能|不要)(?:使用|采用)[^，,。；;\\r\\n]{0,45}(?:示例|演示|测试)数据\\s*(?:$|[。；;\\r\\n])")
                .matcher(normalized).find();
    }

    /** 只归一化书写，不把不同年份、国家或来源的人口值归成同一选值。 */
    private static String normalizePopulation(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[\\s*`《》]", "")
                .replaceFirst("^(?:本次|该研究)?(?:采用|使用)", "");
    }

    private static void reject(ProviderResponseValidationException.Reason reason, String field) {
        throw new ProviderResponseValidationException(reason, field);
    }
}
