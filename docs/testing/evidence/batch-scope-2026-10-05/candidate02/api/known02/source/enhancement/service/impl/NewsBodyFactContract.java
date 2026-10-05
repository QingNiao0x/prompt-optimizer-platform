package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/**
 * 让新闻交付正文直接继承用户已明确提供的当前状态，不将示例或未核实情况升级为事实。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class NewsBodyFactContract {
    private static final Pattern CURRENT_STATE = Pattern.compile(
            "(?:当前|目前|尚|仍)(?:尚未|仍未|未|处于|为|是|已|正在|仅|只|不|尚处于|仍处于)|发布性质(?:是|为)");
    private static final Pattern UNVERIFIED = Pattern.compile(
            "[？?]|是否|未核实|未经核实|未确认|未明确|尚未确定|尚未决定|待确认|待批准|待定|证据不足|无法确认|尚不清楚|资料未说明");
    private static final Pattern NON_FACT = Pattern.compile(
            "例如|示例|假设|假如|如果|反例|排版测试|^(?:若|当)|忽略.{0,12}(?:平台|系统|安全)|绕过.{0,12}(?:平台|系统|安全)");
    private static final Pattern NEWS_TARGET = Pattern.compile(
            "(?:为|给)([^。\\n，,]{1,40}?)(?:撰写|编写|起草|写).{0,12}(?:新闻稿|发布稿)");
    private static final Pattern STAGE_OR_BOUNDARY = Pattern.compile(
            "(?i)MVP|阶段|发布性质|内测|公测|试点|试运行|正式(?:发布|上线|商用)|不(?:直接|自动)?执行|不承诺");
    private static final Pattern EDITOR_PROCESS = Pattern.compile(
            "我目前|编写者|写作者|编辑工作|内部审核|核对稿件|审核稿件|写作过程|校对过程");
    private static final Pattern PRIVATE_SCOPE = Pattern.compile(
            "手机号|电话号码|电子邮箱|身份证|个人健康|患者.{0,8}(?:姓名|病历|身份|诊断结果)|"
                    + "不得公开|不披露|禁止披露|不要写入|仅供内部");

    private NewsBodyFactContract() { }

    /** 返回只适用于当前新闻任务的成品事实要求；没有明确事实时不推断。 */
    static String guidance(String rawPrompt) {
        if (rawPrompt == null || TaskDeliveryProfile.identify(rawPrompt) != TaskDeliveryProfile.NEWS_RELEASE
                || rawPrompt.matches("(?is).*(?:纯\\s*JSON|(?:只|仅)(?:输出|返回|提供|给出)\\s*JSON).*")) return "";
        // 仅交付标题时不能新增正文任务；正文事实来自本次用户原文，不从材料名称或测试数据猜测。
        if (rawPrompt.matches("(?s).*(?:不(?:输出|提供|写|交付)正文|不要正文|无需正文).*")) return "";
        if (!rawPrompt.contains("正文") && rawPrompt.matches("(?s).*(?:只|仅)(?:输出|提供|给).{0,3}标题.*")) return "";
        if (rawPrompt.matches("(?is).*(?:不得|不要|禁止|不).{0,10}(?:公开|披露|提及|报道).{0,12}"
                + "(?:阶段|发布性质|当前状态|MVP|内测).*")) return "";
        var targetMatch = NEWS_TARGET.matcher(rawPrompt);
        if (!targetMatch.find()) return "";
        String target = targetMatch.group(1).replaceFirst("^(?:合成)?(?:产品|项目|品牌|平台)", "")
                .replaceAll("[「」“”\"]", "").strip();
        if (target.isBlank()) return "";
        var facts = new LinkedHashSet<String>();
        var safety = new SensitiveValueDetector();
        boolean excludedSection = false;
        boolean previousSubjectIsTarget = false;
        for (String line : rawPrompt.lines().toList()) {
            if (line.strip().startsWith("#")) {
                excludedSection = line.matches(".*(?:示例|假设|测试|反例|内部|编写过程).*" );
                previousSubjectIsTarget = false;
                continue;
            }
            if (excludedSection) continue;
            for (String source : line.split("(?<=[。！？!?])")) {
                String sentence = source.strip().replaceFirst("^(?:[-*•]\\s+|\\d+[.)、]\\s*)", "");
                // 泛指只承接紧邻的报道对象；中间引入另一个对象后，不能把“该产品”继续归给原对象。
                boolean ownsTarget = sentence.contains(target) || previousSubjectIsTarget
                        && sentence.matches("^(?:该|本)(?:产品|项目|品牌|平台).*" );
                previousSubjectIsTarget = ownsTarget && !NON_FACT.matcher(sentence).find();
                // 保留完整否定与例外；含未知/示例的整句不升级为事实，也不截短过长条件制造新结论。
                if (ownsTarget && sentence.length() <= 240 && CURRENT_STATE.matcher(sentence).find()
                        && STAGE_OR_BOUNDARY.matcher(sentence).find() && !EDITOR_PROCESS.matcher(sentence).find()
                        && !UNVERIFIED.matcher(sentence).find() && !NON_FACT.matcher(sentence).find()
                        && !PRIVATE_SCOPE.matcher(sentence).find() && !safety.containsCredential(sentence)) facts.add(sentence);
            }
        }
        if (facts.isEmpty()) return "";
        return "正文必须交代下列用户已明确提供的当前状态，不能只留在提示词背景或标题中：\n- "
                + String.join("\n- ", facts) + "\n融入成品叙述，不增加声明清单、编造能力或改变原有篇幅与格式。";
    }
}
