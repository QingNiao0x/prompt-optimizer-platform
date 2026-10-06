package com.promptoptimizer.template.domain;

import com.promptoptimizer.enhancement.domain.ConfirmedPlanDecision;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Plan、直接增强及 Mock 共用的轻量交付识别器。
 * 只从本次肯定目标和已验证的交付答案判断，附件、当前状态与未选候选不参与改写目标。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class TaskIntentResolver {
    private record Goal(TemplateCode code, TaskDeliveryProfile profile) { }
    private static final Pattern NEGATIVE = Pattern.compile(
            "^(?:(?:也|并|且|亦|同时|本次)\\s*)?(?:不(?:要求|需要|要|得|应|会)?|无需|禁止|不得|避免|不要|未要求|无须|not\\b|do not\\b)");
    private static final Pattern DELIVERY_ANSWER = Pattern.compile(
            "交付|输出|成品|任务目标|需要.*结果|希望.*(?:完成|得到)|目标.*(?:是什么|什么)|用途|主要用来|主要用于");
    private static final Pattern PENDING = Pattern.compile("暂不确定|尚未确定|待定|不知道|不清楚|未决定|unknown|tbd", Pattern.CASE_INSENSITIVE);
    private static final String WRITE = "(?:撰写|编写|起草|写|生成|输出|提供|交付|拟定|制作|设计|制定|\\bwrite\\b|\\bdraft\\b|\\bcreate\\b|\\bproduce\\b)";
    // 表达式仅来自下面的固定规则，按固定数量缓存，避免每个子句重新编译同一规则。
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    private TaskIntentResolver() { }

    /** 兼容无 Plan 的调用；空输入保守返回通用，不把技术名词当开发命令。 */
    public static TaskIntent resolve(TemplateCode requested, String rawPrompt) {
        return resolve(requested, rawPrompt, List.of());
    }

    /**
     * 显式模板优先；确认答案只能细化本次交付，现状及未决答案不能覆盖原始目标。
     * 调用方必须先完成服务端 Plan 所有权及问题绑定校验。
     */
    public static TaskIntent resolve(TemplateCode requested, String rawPrompt, List<ConfirmedPlanDecision> decisions) {
        List<Goal> goals = goals(rawPrompt);
        TaskIntent.ResolutionStatus status = goals.isEmpty()
                ? TaskIntent.ResolutionStatus.DEFAULT : TaskIntent.ResolutionStatus.RAW_GOAL;
        List<Goal> confirmedGoals = new ArrayList<>();
        for (ConfirmedPlanDecision decision : decisions == null ? List.<ConfirmedPlanDecision>of() : decisions) {
            if (decision.scope() == ConfirmedPlanDecision.Scope.UNRESOLVED
                    || decision.scope() == ConfirmedPlanDecision.Scope.CURRENT_STATE
                    || PENDING.matcher(decision.answer()).find()
                    || !overallDeliveryQuestion(decision.question())) continue;
            confirmedGoals.addAll(goals(decision.answer()));
        }
        // 仅针对交付主题的明确答案细化；审批阈值等独立答案不改变任务类型。
        if (!confirmedGoals.isEmpty()) {
            goals = confirmedGoals;
            status = TaskIntent.ResolutionStatus.USER_CONFIRMED;
        }
        Goal primary = goals.isEmpty() ? new Goal(TemplateCode.GENERAL, TaskDeliveryProfile.GENERAL) : goals.getFirst();
        TemplateCode code = primary.code();
        TaskDeliveryProfile profile = primary.profile();
        if (requested != null && requested != TemplateCode.AUTO) {
            code = requested;
            status = TaskIntent.ResolutionStatus.EXPLICIT;
            if (software(code) && !profile.softwareTask()) profile = TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION;
        }
        List<TaskDeliveryProfile> auxiliary = goals.stream().map(Goal::profile).filter(value -> value != primary.profile())
                .distinct().toList();
        boolean engineering = software(code) || profile.softwareTask()
                || auxiliary.stream().anyMatch(TaskDeliveryProfile::softwareTask);
        return new TaskIntent(code, profile, status, auxiliary, engineering);
    }

    /** 公开模板仍保留原有六类；细分交付画像只在内部使用。 */
    public static boolean software(TemplateCode code) {
        return code == TemplateCode.FEATURE_DEVELOPMENT || code == TemplateCode.BUG_FIX
                || code == TemplateCode.REFACTORING || code == TemplateCode.TESTING;
    }

    /** 只识别交付画像，供已有过滤器兼容使用，避免与注册中心互相回调。 */
    public static TaskDeliveryProfile identifyDelivery(String rawPrompt) {
        return resolve(TemplateCode.AUTO, rawPrompt).deliveryProfile();
    }

    /** 整体交付答案可更新目标；附表、字段、下一阶段等局部选择不能替代主任务。 */
    public static boolean overallDeliveryQuestion(String question) {
        return question != null && DELIVERY_ANSWER.matcher(question).find()
                && !question.matches("(?s).*(?:附表|附录|附件.{0,12}(?:格式|语言)|字段|局部|某一|另一个|下一阶段|下一轮|第二阶段|还需要|另外|额外|示例|示范).*");
    }

    /**
     * 以指令子句而非整段关键词分类；背景、约束、验收、原文和代码块不能正向触发目标。
     * 仍保留完整原始需求供模型执行，多目标不在此删减为一项任务。
     */
    private static List<Goal> goals(String input) {
        List<Goal> values = new ArrayList<>();
        boolean fenced = false;
        boolean referenceSection = false;
        for (String line : (input == null ? "" : input).toLowerCase(Locale.ROOT).split("\\R")) {
            String text = line.trim();
            if (text.startsWith("```") || text.startsWith("~~~")) { fenced = !fenced; continue; }
            if (fenced) continue;
            String heading = text.replaceAll("^[#*\\s]+|[*\\s]+$", "");
            if (heading.matches("(?:背景|项目背景|约束|约束条件|验收标准|示例|参考资料|原文|材料|资料|已知资料)[:：]?")) {
                referenceSection = true;
                continue;
            }
            if (heading.matches("(?:任务|任务目标|目标|需求|输出|交付|输出要求|期望输出)[:：]?")) {
                referenceSection = false;
                continue;
            }
            if (text.matches("^(?:原文|待译文本|source text)[:：].*")) {
                referenceSection = true;
                continue;
            }
            if (referenceSection) continue;
            for (String part : text.split("[。；;，,]")) {
                String clause = part.replaceAll("^[-*\\d.、\\s]+", "").trim();
                if (clause.isBlank() || NEGATIVE.matcher(clause).find()
                        || clause.matches("^(?:原文|材料|资料|例如|示例|当前|现有|目前|已实现)[:：].*")) continue;
                int reference = clause.indexOf("原文：");
                if (reference >= 0) clause = clause.substring(0, reference);
                Goal goal = classify(clause);
                if (goal != null) values.add(goal);
                // 翻译指令后的冒号引入待译内容；原文中的“开发/研究”不作为附带任务。
                if (reference >= 0 || (goal != null && goal.profile() == TaskDeliveryProfile.TRANSLATION
                        && clause.matches(".*(?:翻译|译成|translate)[^：:]{0,100}[:：].*"))) {
                    referenceSection = true;
                    break;
                }
            }
        }
        return List.copyOf(values);
    }

    /** 肯定交付物优先于领域名词；研究的附带复现代码不会将研究目标改成开发。 */
    private static Goal classify(String clause) {
        if (match(clause, "(?:翻译|译成|translate)(?!.*(?:是否|待定)).*")) return general(TaskDeliveryProfile.TRANSLATION);
        if (deliver(clause, "新闻稿|新闻通稿|press release")) return general(TaskDeliveryProfile.NEWS_RELEASE);
        if (deliver(clause, "用户指南|使用指南|操作指南|使用手册|用户手册|user guide|usage guide|operating manual")) return general(TaskDeliveryProfile.USER_GUIDE);
        if (deliver(clause, "教案|教学设计|教学方案|课程设计|课堂活动|练习页|教学课")) return general(TaskDeliveryProfile.TEACHING);
        if (deliver(clause, "工作报告|工作总结|年度报告|机关报告|调研报告|汇报材料")) return general(TaskDeliveryProfile.INSTITUTIONAL_REPORT);
        if (match(clause, "(?:整理|生成|撰写|输出).{0,40}(?:会议纪要|会议记录|行动项)")) return general(TaskDeliveryProfile.MEETING_MINUTES);
        if (deliver(clause, "论文(?:的)?方法(?:部分|提纲|章节)|方法提纲|研究方案|研究设计|research proposal|research design|methods section"))
            return research(TaskDeliveryProfile.ACADEMIC_METHODS);
        if (deliver(clause, "论文|文献综述|学术报告|开题报告|毕业报告")) return research(TaskDeliveryProfile.ACADEMIC_WRITING);
        if (match(clause, "(?:整理|核对|比较|提取|汇总).{0,50}(?:法务|法律|律师|租赁|合同|诉讼|判决|案件)(?:材料|资料|条款|记录|文件)?")
                || match(clause, "(?:法务|法律|律师|租赁|合同|诉讼|判决|案件).{0,40}(?:材料|资料|条款|记录|文件).{0,30}(?:整理|核对|比较|提取|汇总)"))
            return general(TaskDeliveryProfile.LEGAL_MATERIAL);
        if (match(clause, "(?:整理|核对|比较|提取|汇总).{0,60}(?:医院|门诊|医疗|患者|诊疗).{0,35}(?:资料|数据|记录|材料)"))
            return general(TaskDeliveryProfile.MEDICAL_MATERIAL);
        if (deliver(clause, "统计分析方案|数据分析方案|统计方案|分析方案"))
            return research(TaskDeliveryProfile.DATA_ANALYSIS);
        if (match(clause, "(?:总结|摘要|概括|整理|归纳|提取|对照).{0,60}(?:材料|资料|文件|文本|文档|记录|报告|规则)|(?:生成|输出|撰写).{0,30}(?:摘要|对照表)"))
            return general(TaskDeliveryProfile.MATERIAL_SYNTHESIS);
        if (match(clause, "(?:修复|排查|定位).{0,50}(?:bug|报错|故障|缺陷|异常|接口|页面|代码)|^(?:请|帮我|需要)?修复(?:问题|bug)"))
            return new Goal(TemplateCode.BUG_FIX, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (match(clause, "\\b(?:fix|debug|diagnose|troubleshoot)\\b.{0,60}\\b(?:bug|error|failure|exception|endpoint|code)\\b"))
            return new Goal(TemplateCode.BUG_FIX, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (match(clause, "(?:重构|refactor|整理代码|拆分模块|迁移).{0,70}(?:代码|模块|接口|前端|后端|react|vue|jpa|mybatis)|^(?:请|帮我)?重构"))
            return new Goal(TemplateCode.REFACTORING, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (match(clause, "\\b(?:refactor|migrate)\\b.{0,60}\\b(?:code|module|api|frontend|backend|react|vue|repository)\\b"))
            return new Goal(TemplateCode.REFACTORING, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (match(clause, "(?:编写|补充|完善|执行|运行|设计|测试).{0,35}(?:单元测试|接口测试|集成测试|回归测试|自动化测试|性能测试|测试用例)|^测试.{0,25}(?:接口|代码|功能|模块)"))
            return new Goal(TemplateCode.TESTING, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (match(clause, "\\b(?:write|add|implement|run|design)\\b.{0,40}\\b(?:unit|integration|regression|performance|automated) tests?\\b"))
            return new Goal(TemplateCode.TESTING, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (match(clause, "(?:开发|实现|新增|添加|增加|编写|构建).{0,60}(?:接口|功能|模块|系统|服务|程序|代码|算法|函数|页面|缓存|认证|登录)|(?:设计|制定|提供|给出).{0,60}(?:实现方案|开发方案|功能方案)"))
            return new Goal(TemplateCode.FEATURE_DEVELOPMENT, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (match(clause, "(?:提供|交付|给出)(?:可复现|复现|分析|示例|完整|可运行)?代码(?:[。；;\\s]|$)|\\bprovide\\b.{0,30}\\b(?:reproducible|analysis) code\\b"))
            return new Goal(TemplateCode.FEATURE_DEVELOPMENT, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (match(clause, "\\b(?:implement|add|create|build|develop)\\b.{0,60}\\b(?:api|endpoint|function|feature|service|module|authentication|login|algorithm|program|cache)\\b"))
            return new Goal(TemplateCode.FEATURE_DEVELOPMENT, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (match(clause, "(?:研究|分析|计算|评估|比较|撰写).{0,80}(?:数据|死亡率|发病率|时间序列|回归|统计|yll|arriaga|股票|风险|分布|趋势)|完成这项分析"))
            return research(TaskDeliveryProfile.DATA_ANALYSIS);
        return null;
    }

    private static Goal general(TaskDeliveryProfile profile) { return new Goal(TemplateCode.GENERAL, profile); }
    private static Goal research(TaskDeliveryProfile profile) { return new Goal(TemplateCode.RESEARCH_ANALYSIS, profile); }

    /** 否定后出现的交付动词不匹配，例如“不要求编写代码测试”；不跨过完整子句边界。 */
    private static boolean deliver(String clause, String object) {
        return match(clause, WRITE + "[^。；;\\n]{0,64}(?:" + object + ")");
    }

    /** 复用固定规则表达式，并拒绝位于否定、候选或资料引文中的交付动词命中。 */
    private static boolean match(String clause, String expression) {
        var matcher = PATTERNS.computeIfAbsent(expression,
                value -> Pattern.compile(value, Pattern.CASE_INSENSITIVE)).matcher(clause);
        while (matcher.find()) {
            String prefix = clause.substring(0, matcher.start());
            if (!prefix.matches(".*(?:不要求|不需要|无需|不要|不得|禁止|无须|不提供|不附|是否|例如|示例|原文|材料中|资料中)[^，。；;]{0,80}$")) return true;
        }
        return false;
    }
}
