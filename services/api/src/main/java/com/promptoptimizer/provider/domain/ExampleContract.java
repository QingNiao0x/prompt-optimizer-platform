package com.promptoptimizer.provider.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.regex.Pattern;

/**
 * 明确“要求下游回答包含输入与预期输出示例”的契约；不是平台预生成 few-shot 的开关。
 * 用户的禁例和严格输出限制优先，关闭开关不取消原始需求中的明确示例要求。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ExampleContract(Mode mode, String source, String guidance, @JsonIgnore boolean fictionalDataAllowed) {
    private static final Pattern PROHIBITION = Pattern.compile(
            "(?:不要|不得|禁止|无需|不需要|不提供|不包含|不输出|不附带|不附|不加|不添加)[^。；;，,！？!?\\r\\n]{0,16}(?:示例|示范|例子)|不举例"
                    + "|\\b(?:no|without|do not|never)\\b[^.;,!?\\r\\n]{0,60}\\bexamples?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRICT_OUTPUT = Pattern.compile(
            "(?:只|仅)(?:输出|返回|交付|提供).{0,18}(?:译文|翻译结果|JSON|正文|答案|代码)"
                    + "|\\b(?:return|output|provide)\\s+only\\b|\\bonly\\s+(?:the\\s+)?translation\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUEST = Pattern.compile(
            "(?:提供|给出|包含|附上|附带|输出|需要|要求)[^。；;，,！？!?\\r\\n]{0,30}(?:示例|示范|例子)"
                    + "|\\b(?:include|provide|give|add)\\b[^.;,!?\\r\\n]{0,40}\\bexamples?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXAMPLE_DATA_QUALIFIER = Pattern.compile(
            "虚构|假设|模拟|编造|合成|\\b(?:fictional|hypothetical|synthetic|made-up)\\b", Pattern.CASE_INSENSITIVE);
    private static final String REQUIRED_GUIDANCE = "提供与本次任务相关的输入与预期输出示例，并说明二者的对应关系。"
            + "缺少真实资料时可用明确标注为虚构演示的材料；演示不得充当正式事实、统计口径或研究结论。"
            + "示例服务原定交付，不另增无关任务。";

    /** 兼容原有内部调用；显式禁虚构由 from 统一识别，不参与公开 API 扩展。 */
    public ExampleContract(Mode mode, String source, String guidance) {
        this(mode, source, guidance, true);
    }

    /** 同一实例供模型输入、Mock 和最终组装使用；资料中的要求不能决定用户开关。 */
    public static ExampleContract from(String effectivePrompt, boolean enabled) {
        String instructions = instructionText(effectivePrompt);
        if (examplesProhibited(instructions)) return new ExampleContract(
                Mode.PROHIBITED, "USER_PROHIBITION", "遵守用户禁止示例的要求，不附带示例。");
        boolean requested = examplesRequested(instructions);
        if (STRICT_OUTPUT.matcher(instructions).find() && !requested) return new ExampleContract(
                Mode.FORMAT_RESTRICTED, "STRICT_OUTPUT", "保持用户限定的输出形式，不追加示例或新增格式字段。");
        if (requested || enabled) {
            boolean fictional = !RequirementBoundaryContract.prohibitsInventedData(instructions);
            String guidance = fictional ? REQUIRED_GUIDANCE : "提供与本次任务相关的输入与预期输出示例，并说明二者的对应关系。"
                    + "遵守用户禁止虚构的要求，不得使用虚构或假设数据；仅用可核实材料，缺少材料时提供不引入事实或数值的格式、符号示例。"
                    + "示例服务原定交付，不另增无关任务。";
            if (!DraftDeliveryContract.promptDeliveryTarget(instructions).isBlank()) {
                guidance += "本次交付的是提示词，必须把至少一组输入及对应预期输出示例写入这份提示词，不能只留下要求别人提供示例的说明。";
            } else if (Pattern.compile("(?:撰写|起草|写).{0,40}(?:报告|特征分析)").matcher(instructions).find()) {
                guidance += "本次回答须先完成原定分析或报告正文，再附至少一组输入及对应预期输出示例；示例不能替代正文，"
                        + "示例中的演示地区、寿命表或统计参数不能成为正式分析的已选口径。";
            }
            return new ExampleContract(Mode.REQUIRED, requested ? "USER_REQUIREMENT" : "USER_SWITCH", guidance, fictional);
        }
        return new ExampleContract(Mode.DISABLED, "SWITCH_OFF", "未要求示例，不主动新增示例交付要求，包括其他段落中的调用或输入输出示例；原任务所需源码、测试及运行说明继续交付。");
    }

    /** 是否要求下游提供示例，不能仅以模型是否返回 EXAMPLES 段判断。 */
    public boolean required() { return mode == Mode.REQUIRED; }

    /**
     * 判断示例交付动词的否定方向，供响应校验与输入契约共用语法。
     * “禁止虚构示例”在此仍是否定表达，不代表禁止真实示例；是否整体禁例由下面的资料性质判断决定。
     */
    public static boolean negatesExampleDelivery(String expression) {
        if (expression == null) return false;
        var matcher = PROHIBITION.matcher(expression);
        while (matcher.find()) {
            if (!doubleNegative(matcher.group())) return true;
        }
        return false;
    }

    /** 禁令只作用于同一子句中的示例对象；禁虚构示例限制资料性质，不等于禁止真实或符号示例。 */
    private static boolean examplesProhibited(String instructions) {
        var matcher = PROHIBITION.matcher(instructions);
        while (matcher.find()) {
            if (!doubleNegative(matcher.group()) && !EXAMPLE_DATA_QUALIFIER.matcher(matcher.group()).find()) return true;
        }
        return false;
    }

    /** 否定原文请求不成为肯定请求；开启开关仍由禁例与实际格式冲突独立决定。 */
    private static boolean examplesRequested(String instructions) {
        var matcher = REQUEST.matcher(instructions);
        while (matcher.find()) {
            String prefix = instructions.substring(Math.max(0, matcher.start() - 20), matcher.start());
            if (prefix.matches("(?s).*(?:不得不|不能不|不可不)(?:再|额外)?$")) return true;
            if (!prefix.matches("(?s).*(?:不|不要|不得|禁止|无需|不需要|不要求|并未|没有)(?:再|额外)?$")) return true;
        }
        return false;
    }

    /** 双重否定是肯定义务；不能作为省去示例要求或拒绝合法响应的证据。 */
    private static boolean doubleNegative(String expression) {
        return expression.matches("(?s)^(?:不得不|不能不|不可不).*");
    }

    /** 仅移除代码和引用行；不把被引用的产品文案或资料内容当作当前用户指令。 */
    private static String instructionText(String prompt) {
        String value = prompt == null ? "" : prompt.replaceAll("[“‘\"].*?[”’\"]", "");
        return DraftDeliveryContract.instructionText(value);
    }

    /** 禁例和严格格式为独立状态，便于核对开关被用户何种要求覆盖。 */
    public enum Mode { REQUIRED, DISABLED, PROHIBITED, FORMAT_RESTRICTED }
}
