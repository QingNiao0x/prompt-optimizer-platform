package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 将用户指南中已明确的能力边界和常规写法交给执行者，不把写法重新变成业务选择。
 * 仅处理具名的表达维度；发布、授权、专业口径、数值或复合问题继续保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class RoutineGuideDecision {
    private static final Pattern EXTRA_DECISION = Pattern.compile(
            "新增|另外|此外|另需|还需(?:要)?(?:确认|确定|决定)|授权(?:期限|范围)|审批|审核|法域|"
                    + "医学口径|统计口径|收费|付费|退款|有效期|保存天数|保留天数|新增脱敏权限|[<>≤≥]|\\d");
    private static final Pattern EXPLICIT_CHOICE = Pattern.compile(
            "(?:请|需要|希望)(?:先)?(?:让我选择|由我选择|询问我|向我确认|由用户选择)[^。；\\n]{0,40}"
                    + "(?:按钮|界面名称|资料类型|保留|历史期限|常见问题|术语|用词|英文词)");

    private RoutineGuideDecision() { }

    /** 先确认任务与现有决定，再检查整题是否夹带新要求；不按行业名称批量删题。 */
    static boolean delegated(PlanQuestion question, String raw) {
        if (TaskDeliveryProfile.identify(raw) != TaskDeliveryProfile.USER_GUIDE
                || EXPLICIT_CHOICE.matcher(raw).find()) return false;
        String text = question.question();
        boolean known = buttonPresentation(text, raw) || contextPresentation(text, raw)
                || unspecifiedHistoryLimit(text, raw) || knownSecurityPlacement(text, raw)
                || terminologyPresentation(text, raw) || knownCopyCoverage(text, raw)
                || unverifiedGuideEntry(text, raw);
        if (!known) return false;
        List<String> details = java.util.stream.Stream.concat(
                java.util.stream.Stream.of(text, question.hint()),
                java.util.stream.Stream.concat(question.examples().stream(), question.options().stream()
                        .flatMap(option -> java.util.stream.Stream.of(option.label(), option.description(),
                                option.answer(), option.recommendationReason())))).toList();
        return details.stream().noneMatch(value -> EXTRA_DECISION.matcher(value).find());
    }

    /** 界面事实以已展示界面为准，措辞位置不另作业务选择。 */
    private static boolean buttonPresentation(String question, String raw) {
        return question.matches(".*(?:按钮|入口名称|界面名称).*(?:哪种方式处理|如何处理|怎么写|怎样写|写法|具体位置|界面区域).*" )
                && raw.matches("(?s).*(?:不编造|不得编造|不能编造).*(?:按钮|界面).*" )
                && raw.contains("以当前界面为准");
    }

    /** 原文已要求复制必要规则和未决前提，不能再让用户选择省略这些内容。 */
    private static boolean knownCopyCoverage(String question, String raw) {
        return question.matches(".*(?:复制).*(?:复制结果的范围|复制范围).*" )
                && raw.contains("复制应包含重要规则及未决前提");
    }

    /** 未证明的入口位置沿用原文的界面核对规则，具名权限或新前提仍由整题检查保留。 */
    private static boolean unverifiedGuideEntry(String question, String raw) {
        return question.matches(".*历史记录.*再次增强.*(?:操作入口|入口位置).*" )
                && raw.contains("以当前界面为准") && raw.contains("只介绍附件明确提供的行为");
    }

    /** 用户已指定资料种类及 OCR 边界，指南继承这些说明。 */
    private static boolean contextPresentation(String question, String raw) {
        return question.matches(".*(?:添加上下文|资料类型|材料类型).*(?:是否需要说明|是否列出|是否需要列出).*" )
                && raw.contains("代码索引") && raw.contains("办公文件")
                && raw.contains("图片仅元数据") && raw.matches("(?s).*扫描PDF.*不支持OCR.*");
    }

    /** 未披露的期限保持未知，不让指南写作阶段替产品决定数字。 */
    private static boolean unspecifiedHistoryLimit(String question, String raw) {
        return question.matches(".*历史.*(?:保留期限|保留时间|保存期限|数量限制|条数限制).*" )
                && raw.matches("(?s).*(?:没有在材料中说明|材料没有说明|材料未说明|附件未说明).*(?:不写具体数字|不得写具体数字).*" )
                && raw.contains("历史");
    }

    /** 已要求的安全边界由指南组织呈现，不重新询问是否包含。 */
    private static boolean knownSecurityPlacement(String question, String raw) {
        return question.matches(".*(?:常见问题|权限边界).*(?:是否需要包含|是否需要说明|放在哪里|写在哪里).*" )
                && question.matches(".*(?:管理员|密钥).*" )
                && raw.contains("权限边界") && raw.matches("(?s).*(?:不要求|不得要求|不能要求).*密码.*");
    }

    /** 明确的首次解释要求优先，英文词写法交给撰写者处理。 */
    private static boolean terminologyPresentation(String question, String raw) {
        return question.matches("(?s).*Plan.*(?:英文词|中文解释|统一用词|用词).*" )
                && raw.matches("(?s).*首次出现Plan.*解释.*");
    }
}
