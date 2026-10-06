package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import java.util.regex.Pattern;

/**
 * 将已要求写作成果的纯清单排版委托给执行者，不替用户决定内容、专业口径或权限。
 * 只匹配原文已命名的成果和全为排版选项的题目，复合决定或主动要求确认时保留。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class RoutineWritingPresentation {
    private static final Pattern FORMAT_QUESTION = Pattern.compile(
            "^[“\"]([^”\"]{2,40})[”\"](?:希望|应|应该|需要)?(?:以什么形式|用什么形式|采用哪种格式|如何排版)"
                    + "(?:附在[^？?]{0,40}|展示|呈现)?[？?]$");
    private static final Pattern FORMAT_LABEL = Pattern.compile(
            "^(?:表格(?:形式)?|条目式|条目(?:形式)?|编号列表|项目符号列表|段落(?:说明|形式)?)$");
    private static final Pattern INDEPENDENT_DECISION = Pattern.compile(
            "新增|另外|还需|必须|阈值|权限|隐私|保密|脱敏|审批|法规|期刊|标准|统计|指标|分母|字段映射|"
                    + "版本|归属|生效|有效|适用|公开|对外|"
                    + "数据来源|样本|人群|地区|时间范围|期限|责任人|署名|法域|研究方法|重试|超时|冲突|[<>!=]|\\d");

    private RoutineWritingPresentation() { }

    /** 有明确成果、无独立业务参数、所有候选仅改变排版时才委托；证据不足则保留整题。 */
    static boolean delegated(PlanQuestion question, String rawPrompt) {
        TaskDeliveryProfile profile = TaskDeliveryProfile.identify(rawPrompt);
        if (profile == TaskDeliveryProfile.TRANSLATION && knownTranslationPresentation(question, rawPrompt)) return true;
        if (profile == TaskDeliveryProfile.ACADEMIC_METHODS && knownMatchedFactsLayout(question, rawPrompt)) return true;
        if (knownComparisonPresentation(question, rawPrompt)) return true;
        if (profile == TaskDeliveryProfile.GENERAL || profile.softwareTask()
                || question.options().isEmpty()) return false;
        var match = FORMAT_QUESTION.matcher(question.question());
        if (!match.matches() || !rawPrompt.contains(match.group(1))) return false;
        String artifact = Pattern.quote(match.group(1));
        if (Pattern.compile("(?:请|需要|希望)(?:先)?(?:询问|确认|让我选择|由我选择)[^。；\\n]{0,20}"
                + artifact + "[^。；\\n]{0,20}(?:形式|格式|排版)").matcher(rawPrompt).find()) return false;
        if (INDEPENDENT_DECISION.matcher(question.hint() == null ? "" : question.hint()).find()) return false;
        if (question.examples().stream().anyMatch(value -> INDEPENDENT_DECISION.matcher(value).find())) return false;
        return question.options().stream().allMatch(option ->
                FORMAT_LABEL.matcher(option.label()).matches()
                        && option.answer().contains(match.group(1))
                        && !INDEPENDENT_DECISION.matcher(option.answer() + " " + option.description()
                                + " " + option.recommendationReason()).find());
    }

    /** 已要求并列比较时，纯表格/段落排版交给执行者；版本归属和新增适用范围仍由用户决定。 */
    private static boolean knownComparisonPresentation(PlanQuestion question, String rawPrompt) {
        String raw = rawPrompt == null ? "" : rawPrompt.replaceAll("\\s+", "");
        if (!raw.matches("(?s).*(?:并列|并排).{0,16}(?:展示|对照|比较).{0,30}(?:差异|条款).*"
                + "|(?s).*(?:差异|条款).{0,16}(?:并列|并排).{0,16}(?:展示|对照|比较).*")
                || raw.matches("(?s).*(?:请|希望|需要)(?:先)?(?:询问|确认|让我选择|由我选择)"
                + "[^。；\\n]{0,30}(?:差异|对照)[^。；\\n]{0,30}(?:形式|格式|呈现).*")) return false;
        String text = question.question();
        if (!text.matches("^.{2,50}(?:差异|对照)(?:应|应该)?(?:采用什么形式|以什么形式|如何)(?:呈现|展示|排版)[？?]$")) return false;
        if (INDEPENDENT_DECISION.matcher(question.hint() == null ? "" : question.hint()).find()
                || question.examples().stream().anyMatch(value -> INDEPENDENT_DECISION.matcher(value).find())) return false;
        return !question.options().isEmpty() && question.options().stream().allMatch(option ->
                option.label().matches("并列表格|对照表格|分段说明|段落说明|条目列表")
                && !INDEPENDENT_DECISION.matcher(option.answer() + option.description()
                + option.recommendationReason()).find());
    }

    /** 信息内容和问答边界均已确定时，仅将单纯呈现形式委派给研究方案编写者。 */
    private static boolean knownMatchedFactsLayout(PlanQuestion question, String rawPrompt) {
        String raw = rawPrompt.replaceAll("\\s+", "");
        if (!raw.matches("(?s).*信息量匹配对照(?:组)?获得相同已确认事实[，,]但不经历[^。\\n]{0,20}问答过程.*")
                || raw.matches("(?s).*(?:请|需要|希望)(?:先)?(?:询问|确认|让我选择|由我选择)"
                + "[^。；\\n]{0,30}信息量匹配[^。；\\n]{0,30}(?:形式|格式|呈现).*")) return false;
        if (!question.question().matches("^信息量匹配对照组的已确认事实(?:应|应该)?如何(?:呈现|提供)给下游模型[？?]$" )
                || question.options().isEmpty()) return false;
        if (INDEPENDENT_DECISION.matcher(question.hint() == null ? "" : question.hint()).find()
                || question.examples().stream().anyMatch(value -> INDEPENDENT_DECISION.matcher(value).find())) return false;
        return question.options().stream().allMatch(option ->
                option.label().matches("直接并入提示词|单独事实清单|自然语言叙述")
                        && !INDEPENDENT_DECISION.matcher(option.answer() + option.description()
                        + option.recommendationReason()).find());
    }

    /** 仅沿用已给定的译文格式；正式名称、术语冲突、额外法律或发布条件不视为普通排版。 */
    private static boolean knownTranslationPresentation(PlanQuestion question, String raw) {
        if (question.options().isEmpty() || !raw.matches("(?s).*(?:只输出译文|仅输出译文).*")) return false;
        String text = question.question();
        if ((text + " " + question.hint()).matches("(?s).*(?:冲突|正式名称|官方名称|法域|法律效力|保密|署名|新增条件).*")) return false;
        if (raw.matches("(?s).*(?:请|需要|希望)(?:先)?(?:询问|确认|让我选择).{0,30}(?:语气|段落|解释).*")) return false;
        if (text.matches("^原文(?:的)?(?:两|2)段在译文中如何对应[？?]$")
                && raw.matches("(?s).*保留原文(?:的)?(?:两|2)个?段落.*")) return true;
        return text.matches("^Plan Mode 保持英文时[，,]是否需要附带简短解释[？?]$")
                && raw.matches("(?s).*Plan Mode.*保持英文.*")
                && raw.matches("(?s).*不输出[^。\\n]{0,60}(?:语言解释|注释).*");
    }
}
