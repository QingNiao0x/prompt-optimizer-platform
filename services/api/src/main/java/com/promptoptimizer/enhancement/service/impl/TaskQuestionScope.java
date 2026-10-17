package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.domain.TaskIntent;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 按肯定的任务指令限定工程提问，不从否定、引文或背景中制造软件执行前提。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class TaskQuestionScope {
    private static final Pattern SORT_GOAL = Pattern.compile(
            "(?:弄个|实现|开发|编写|写|添加|增加|提供|构建|设计).{0,40}(?:排序|\\bsort\\b)"
                    + "|(?:对|将).{0,30}(?:整数|数字|字符串|数组|列表|数据|记录).{0,20}排序"
                    + "|^(?:请|帮我|需要)?(?:排序|sort)(?:功能|算法)?[。.!！]?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATED = Pattern.compile(
            "^(?:(?:并|也|且|本次|同时)\\s*)?(?:不|无需|无须|禁止|避免|未要求|not\\b|do not\\b)");

    private TaskQuestionScope() { }

    /** 只有肯定指令才触发排序缺口；完整需求仍保留，扫描不会改变用户文本。 */
    static boolean requestsSorting(String raw) {
        boolean reference = false;
        boolean fenced = false;
        for (String source : safe(raw).toLowerCase(Locale.ROOT).split("\\R")) {
            String line = source.strip();
            if (line.startsWith("```") || line.startsWith("~~~")) { fenced = !fenced; continue; }
            if (fenced) continue;
            String heading = line.replaceAll("^[#*\\s]+|[*\\s]+$", "");
            if (heading.matches("(?:背景|项目背景|约束|约束条件|验收标准|示例|参考资料|原文|材料|资料|已知资料)[:：]?")) {
                reference = true;
                continue;
            }
            if (heading.matches("(?:任务|任务目标|目标|需求|输出|交付|输出要求|期望输出)[:：]?")) { reference = false; continue; }
            if (reference) continue;
            for (String part : line.split("[。；;，,]")) {
                String clause = part.replaceFirst("^[-*\\d.、\\s]+", "").strip();
                if (NEGATED.matcher(clause).find() || clause.matches("^(?:原文|客户原话|材料|资料|例如|示例)[:：].*")) continue;
                var goal = SORT_GOAL.matcher(clause);
                while (goal.find()) {
                    // 引述、候选和否定范围内的动作不是本次开发指令。
                    if (!clause.substring(0, goal.start()).matches(".*(?:不要求|不需要|无需|不要|不得|禁止|是否|例如|原文|提到|写着)[^。；;]{0,80}$")) return true;
                }
            }
        }
        return false;
    }

    /** 仅过滤明确写作任务中的编程排序提醒；风险分级、新业务对象与附带软件目标仍保留。 */
    static boolean unrelatedEngineeringReminder(String text, String raw) {
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
        if (intent.engineeringConstraints() || intent.auxiliaryProfiles().contains(TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION)
                || intent.deliveryProfile() == TaskDeliveryProfile.GENERAL || requestsSorting(raw)) return false;
        String value = safe(text);
        return value.matches("(?s).*(?:排序.{0,16}(?:字段|数据类型|比较规则)|(?:哪个|什么)字段.{0,16}排序|对哪类数据排序).*" )
                && !value.matches("(?s).*(?:风险|争议|审批|退款|临床|优先级|阈值|授权|权限|新增|另需).*" );
    }

    /**
     * 仅在当前明确为项目理解且没有实现交付时，消除要求另给开发目标的题目。
     * 题干之外的提示、候选和示例也核对，避免复合题中的授权或独立条件被一并删除。
     */
    static boolean unrelatedImplementationGoal(String question, String details, TaskIntent intent) {
        if (intent.deliveryProfile() != TaskDeliveryProfile.PROJECT_UNDERSTANDING || intent.engineeringConstraints()) return false;
        String all = safe(question) + " " + safe(details);
        if (all.matches("(?is).*(?:权限|授权|允许|删除|脱敏|隐私|生产数据|生产环境|冲突|工作区|哪个项目|哪份|阈值|时延|迁移|另需|还需|新条件|例外|permission|authorization|conflict).*")) return false;
        return safe(question).matches("(?is).*(?:本次|这次|此次|当前).{0,30}(?:实现|修改|开发|新增|改动).{0,25}(?:功能|需求|目标|内容|范围).{0,20}(?:什么|哪些|哪项|哪种|如何|怎么|？|\\?).*")
                || safe(question).matches("(?is).*(?:具体|什么|哪些).{0,16}(?:功能|需求|改动).{0,16}(?:实现|修改|开发).*" );
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
