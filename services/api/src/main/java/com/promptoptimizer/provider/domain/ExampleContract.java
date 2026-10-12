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
            "(?:不要|不得|禁止|无需|不需要|不提供|不包含|不输出|不附带|不加|不添加).{0,16}(?:示例|示范|例子)|不举例"
                    + "|\\b(?:no|without|do not|never)\\b[^.\\r\\n]{0,60}\\bexamples?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRICT_OUTPUT = Pattern.compile(
            "(?:只|仅)(?:输出|返回|交付|提供).{0,18}(?:译文|翻译结果|JSON|正文|答案|代码)"
                    + "|\\b(?:return|output|provide)\\s+only\\b|\\bonly\\s+(?:the\\s+)?translation\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUEST = Pattern.compile(
            "(?:提供|给出|包含|附上|附带|输出|需要|要求).{0,30}(?:示例|示范|例子)"
                    + "|\\b(?:include|provide|give|add)\\b[^.\\r\\n]{0,40}\\bexamples?\\b", Pattern.CASE_INSENSITIVE);
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
        if (PROHIBITION.matcher(instructions).find()) return new ExampleContract(
                Mode.PROHIBITED, "USER_PROHIBITION", "遵守用户禁止示例的要求，不附带示例。");
        boolean requested = REQUEST.matcher(instructions).find();
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
        return new ExampleContract(Mode.DISABLED, "SWITCH_OFF", "未要求示例，不主动追加示例段。");
    }

    /** 是否要求下游提供示例，不能仅以模型是否返回 EXAMPLES 段判断。 */
    public boolean required() { return mode == Mode.REQUIRED; }

    /** 仅移除代码和引用行；不把被引用的产品文案或资料内容当作当前用户指令。 */
    private static String instructionText(String prompt) {
        String value = prompt == null ? "" : prompt.replaceAll("[“‘\"].*?[”’\"]", "");
        return DraftDeliveryContract.instructionText(value);
    }

    /** 禁例和严格格式为独立状态，便于核对开关被用户何种要求覆盖。 */
    public enum Mode { REQUIRED, DISABLED, PROHIBITED, FORMAT_RESTRICTED }
}
