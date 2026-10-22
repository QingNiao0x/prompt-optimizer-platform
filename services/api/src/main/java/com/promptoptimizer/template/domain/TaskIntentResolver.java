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
    /** 模板画像和正文动作分别记录；阅读目标不隐含作品，复合请求仍可明确要求写作。 */
    private record ActionResolution(List<Goal> goals, boolean writingRequested) { }
    // 同一交付物规则供肯定、整体否定和局部附加物共用；“源码的说明”交付的是文档，不是源码。
    private static final String CODE_ARTIFACT = "(?<!伪)(?:代码|源码|脚本|程序)(?!\\s*(?:的)?(?:说明|文档|介绍|摘要|清单|签名|引用|作用|原理))";
    private static final Pattern QUOTED_TEXT = Pattern.compile("“[^”]*”|‘[^’]*’|\"[^\"]*\"");
    private static final Pattern NEGATIVE = Pattern.compile(
            "^(?:(?:请|也|并|且|亦|同时|本次)\\s*)*(?:不(?:要求|需要|要|得|应|会)?|勿|无需|禁止|不得|避免|不要|未要求|无须|not\\b|do not\\b)");
    private static final Pattern DELIVERY_ANSWER = Pattern.compile(
            "交付|输出|成品|任务目标|需要.*结果|希望.*(?:完成|得到)|目标.*(?:是什么|什么)|用途|主要用来|主要用于");
    private static final Pattern PENDING = Pattern.compile("暂不确定|尚未确定|待定|不知道|不清楚|未决定|unknown|tbd", Pattern.CASE_INSENSITIVE);
    // 交付物作主语时，否定词不一定出现在句首，例如“本次交付不包含 R 脚本”。
    private static final Pattern DECLINED_CODE_DELIVERY = Pattern.compile(
            "^(?:(?:本次|本轮|此次|这次|最终)(?:的)?\\s*)?"
                    + "(?:(?:交付|输出)(?:物|内容|范围)?\\s*)?"
                    + "(?:不包含|不含|不提供|不交付|不输出|不附带|不附上|不需要|不编写|无需|无须)"
                    + "[^。；;，,\\r\\n]{0,32}" + CODE_ARTIFACT, Pattern.CASE_INSENSITIVE);
    private static final String WRITE = "(?:撰写|编写|起草|写|生成|输出|提供|交付|拟定|制作|设计|制定|\\bwrite\\b|\\bdraft\\b|\\bcreate\\b|\\bproduce\\b)";
    private static final Pattern WRITING_VERB = Pattern.compile(WRITE, Pattern.CASE_INSENSITIVE);
    // 沿用写作动词范围，但要求存在动作宾语；背景中的孤立“进行编写”不能建立正文交付。
    private static final String WRITING_ACTION = "(?:撰写|起草|编写|写出|写一(?:份|篇)|写份|写篇|写(?=.{0,8}报告)|生成(?=.{0,12}(?:报告|正文|稿件)))"
            + "\\s*[^\\s。；;，,]+|\\b(?:write|draft)\\b\\s+[^\\s.;,]+";
    // 语言名仅是工具证据，须与句首肯定的实现动作及非空对象同时出现；未知工具继续保守回退。
    private static final Pattern LANGUAGE_IMPLEMENTATION = Pattern.compile(
            "^(?:(?:请|帮我|需要|本次|现在|立即|这次)\\s*)*(?:使用|用)\\s*"
                    + "(?:typescript|javascript|python|java|kotlin|rust|go|c\\+\\+|c#|c|r)\\s*"
                    + "(?:来|去)?\\s*(?:实现|开发)(?!后的?|过的|完成的|完的|好的|的|了)\\s*[^。；;，,]{1,100}$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_CODE_LANGUAGE_ARTIFACT = Pattern.compile(
            "思路|原理|说明|方案|报告|文章|教程|研究|统计分析|数据分析");
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
        Boolean auxiliaryCode = null;
        for (ConfirmedPlanDecision decision : decisions == null ? List.<ConfirmedPlanDecision>of() : decisions) {
            if (decision.scope() == ConfirmedPlanDecision.Scope.UNRESOLVED
                    || decision.scope() == ConfirmedPlanDecision.Scope.CURRENT_STATE
                    || PENDING.matcher(decision.answer()).find()) continue;
            // 附带代码题只决定辅助交付，不能把“统计分析方案”替换为软件主任务。
            if (codeDeliveryQuestion(decision.question())) {
                String answer = decision.answer().trim().toLowerCase(Locale.ROOT);
                // 按子句核对，允许“不提供 Python 代码，但提供 R 脚本”，不把前一项否定扩大到全部交付。
                boolean affirmed = java.util.Arrays.stream(answer.split("[。；;，,\\r\\n]"))
                        .anyMatch(TaskIntentResolver::explicitCodeDelivery);
                if (affirmed || answer.matches("^(?:是|需要|要|提供|包含|yes)[。.!！\\s]*$")) auxiliaryCode = true;
                else if (NEGATIVE.matcher(answer).find() || DECLINED_CODE_DELIVERY.matcher(answer).find()) auxiliaryCode = false;
                continue;
            }
            if (!overallDeliveryQuestion(decision.question())) continue;
            confirmedGoals.addAll(goals(decision.answer()));
        }
        // 仅针对交付主题的明确答案细化；审批阈值等独立答案不改变任务类型。
        if (!confirmedGoals.isEmpty()) {
            goals = confirmedGoals;
            status = TaskIntent.ResolutionStatus.USER_CONFIRMED;
        }
        if (Boolean.TRUE.equals(auxiliaryCode)) {
            goals = new ArrayList<>(goals);
            goals.add(new Goal(TemplateCode.FEATURE_DEVELOPMENT, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION));
        } else if (Boolean.FALSE.equals(auxiliaryCode) && !goals.isEmpty() && !goals.getFirst().profile().softwareTask()) {
            goals = goals.stream().filter(goal -> !goal.profile().softwareTask()).toList();
        }
        // “先熟悉再实现”仍有明确的软件交付；单独熟悉项目则是完整目标，不能被背景中的构建状态取代。
        Goal primary = goals.isEmpty() ? new Goal(TemplateCode.GENERAL, TaskDeliveryProfile.GENERAL) : goals.getFirst();
        for (Goal goal : goals) {
            if (goal.profile() != TaskDeliveryProfile.PROJECT_UNDERSTANDING) { primary = goal; break; }
        }
        Goal primaryGoal = primary;
        TemplateCode code = primary.code();
        TaskDeliveryProfile profile = primary.profile();
        if (requested != null && requested != TemplateCode.AUTO) {
            code = requested;
            status = TaskIntent.ResolutionStatus.EXPLICIT;
            if (software(code) && !profile.softwareTask()) profile = TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION;
        }
        List<TaskDeliveryProfile> auxiliary = goals.stream().map(Goal::profile).filter(value -> value != primaryGoal.profile())
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

    /**
     * 提供与模板识别同源的当前写作动作证据，供交付契约使用，而非依据所选模板反推用户要作品。
     * 这是旧链的保守确定性识别；未识别的表达仍完整交给模型，不据此新增作品或删减原始要求。
     */
    public static boolean requestsWriting(String rawPrompt) {
        return actions(rawPrompt).writingRequested();
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
        return actions(input).goals();
    }

    /** 一次遍历同一指令范围，同时提取模板参考和独立写作动作，避免下游重新按动词猜测。 */
    private static ActionResolution actions(String input) {
        List<Goal> values = new ArrayList<>();
        boolean writingRequested = false;
        var implicitGoals = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Goal, Boolean>());
        boolean refuseImplicitCode = false;
        boolean fenced = false;
        boolean referenceSection = false;
        boolean languageImplementationScope = true;
        for (String line : (input == null ? "" : input).toLowerCase(Locale.ROOT).split("\\R")) {
            String text = line.trim();
            if (text.startsWith("```") || text.startsWith("~~~")) { fenced = !fenced; continue; }
            if (fenced || text.startsWith(">") || text.startsWith("|")) continue;
            String heading = text.replaceAll("^[#*\\s]+|[*\\s]+$", "");
            if (heading.matches("(?:背景|项目背景|约束|约束条件|验收标准|示例|参考资料|原文|材料|资料|已知资料)[:：]?")) {
                referenceSection = true;
                continue;
            }
            if (heading.matches("(?:任务|任务目标|目标|需求|输出|交付|输出要求|期望输出)[:：]?")) {
                referenceSection = false;
                languageImplementationScope = true;
                continue;
            }
            if (text.matches("^(?:原文|待译文本|source text)[:：].*")) {
                referenceSection = true;
                continue;
            }
            if (referenceSection) continue;
            // 未知/未来标题不能把其后省略交付名词的句子提升为当前代码任务；显式任务标题可恢复。
            if ((text.startsWith("#") || heading.endsWith("：") || heading.endsWith(":"))
                    && !implicitLanguageAction(heading, "")) languageImplementationScope = false;
            int partOffset = 0;
            for (String part : text.split("[。；;，,]")) {
                String priorClauses = text.substring(0, partOffset);
                partOffset += part.length() + 1;
                String clause = part.replaceAll("^[-*\\d.、\\s]+", "").trim();
                // 仅撤回隐含的库调用/语言实现判断；原有明确开发目标不因另一局部否定被删除。
                if (declinesWholeCodeDelivery(clause)
                        || clause.matches("^(?:只|仅)(?:解释|说明|讲解|输出思路).*$")
                        || clause.matches("^(?:only\\s+(?:explain|describe)|(?:do not|don't)\\s+"
                            + "(?:provide|write|output|include)\\s+(?:any\\s+)?(?:code|scripts?)\\b).*$")) refuseImplicitCode = true;
                if (clause.isBlank() || NEGATIVE.matcher(clause).find()
                        || clause.matches("^(?:原文|材料|资料|例如|示例|当前|现有|目前|已实现)[:：].*")) continue;
                int reference = clause.indexOf("原文：");
                if (reference >= 0) clause = clause.substring(0, reference);
                Goal goal = classify(clause);
                // 翻译的宾语是待译文本；其中的写作动词不能建立另一个正文任务。
                if (goal == null || goal.profile() != TaskDeliveryProfile.TRANSLATION) {
                    writingRequested |= engineeringMatch(clause, WRITING_ACTION)
                            || goal != null && proseProfile(goal.profile());
                }
                if (goal == null && (implicitLibraryAction(clause)
                        || languageImplementationScope && implicitLanguageAction(clause, priorClauses))) {
                    goal = new Goal(TemplateCode.FEATURE_DEVELOPMENT, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
                    implicitGoals.add(goal);
                }
                if (goal != null) values.add(goal);
                if (goal != null && !goal.profile().softwareTask()
                        && goal.profile() != TaskDeliveryProfile.TRANSLATION && explicitCodeDelivery(clause)) {
                    values.add(new Goal(TemplateCode.FEATURE_DEVELOPMENT, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION));
                }
                // 翻译指令后的冒号引入待译内容；原文中的“开发/研究”不作为附带任务。
                if (reference >= 0 || (goal != null && goal.profile() == TaskDeliveryProfile.TRANSLATION
                        && clause.matches(".*(?:翻译|译成|translate)[^：:]{0,100}[:：].*"))) {
                    referenceSection = true;
                    break;
                }
            }
        }
        boolean reserved = refuseImplicitCode;
        return new ActionResolution(values.stream().filter(goal -> !reserved || !implicitGoals.contains(goal)).toList(),
                writingRequested);
    }

    /** 肯定的作品目标也可通过“交付/提供”表达；只有本轮解析出的动作可采用该画像。 */
    private static boolean proseProfile(TaskDeliveryProfile profile) {
        return switch (profile) {
            case NEWS_RELEASE, INSTITUTIONAL_REPORT, ACADEMIC_WRITING, USER_GUIDE, TEACHING -> true;
            default -> false;
        };
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
        if (deliver(clause, "统计分析方案|数据分析方案|统计方案|分析方案|分析报告"))
            return research(TaskDeliveryProfile.DATA_ANALYSIS);
        if (match(clause, "(?:总结|摘要|概括|整理|归纳|提取|对照).{0,60}(?:材料|资料|文件|文本|文档|记录|报告|规则)|(?:生成|输出|撰写).{0,30}(?:摘要|对照表)"))
            return general(TaskDeliveryProfile.MATERIAL_SYNTHESIS);
        if (engineeringMatch(clause, "(?:修复|排查|定位).{0,50}(?:bug|报错|故障|缺陷|异常|接口|页面|代码)|^(?:请|帮我|需要)?修复(?:问题|bug)"))
            return new Goal(TemplateCode.BUG_FIX, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (engineeringMatch(clause, "\\b(?:fix|debug|diagnose|troubleshoot)\\b.{0,60}\\b(?:bug|error|failure|exception|endpoint|code)\\b"))
            return new Goal(TemplateCode.BUG_FIX, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (engineeringMatch(clause, "(?:重构|refactor|整理代码|拆分模块|迁移).{0,70}(?:代码|模块|接口|前端|后端|react|vue|jpa|mybatis)|^(?:请|帮我)?重构"))
            return new Goal(TemplateCode.REFACTORING, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (engineeringMatch(clause, "\\b(?:refactor|migrate)\\b.{0,60}\\b(?:code|module|api|frontend|backend|react|vue|repository)\\b"))
            return new Goal(TemplateCode.REFACTORING, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (engineeringMatch(clause, "(?:编写|补充|完善|执行|运行|设计|测试).{0,35}(?:单元测试|接口测试|集成测试|回归测试|自动化测试|性能测试|测试用例)|^测试.{0,25}(?:接口|代码|功能|模块)"))
            return new Goal(TemplateCode.TESTING, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (engineeringMatch(clause, "\\b(?:write|add|implement|run|design)\\b.{0,40}\\b(?:unit|integration|regression|performance|automated) tests?\\b"))
            return new Goal(TemplateCode.TESTING, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (engineeringMatch(clause, "(?:开发|实现|新增|添加|增加|编写|构建).{0,60}(?:接口|功能|模块|系统|服务|程序|代码|算法|函数|页面|缓存|认证|登录)|(?:设计|制定|提供|给出).{0,60}(?:实现方案|开发方案|功能方案)"))
            return new Goal(TemplateCode.FEATURE_DEVELOPMENT, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (explicitCodeDelivery(clause))
            return new Goal(TemplateCode.FEATURE_DEVELOPMENT, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (engineeringMatch(clause, "\\b(?:implement|add|create|build|develop)\\b.{0,60}\\b(?:api|endpoint|function|feature|service|module|authentication|login|algorithm|program|cache)\\b"))
            return new Goal(TemplateCode.FEATURE_DEVELOPMENT, TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        if (match(clause, "(?:熟悉|了解|阅读|梳理|理解|审阅|浏览|查看|摸清).{0,45}(?:项目|工程|仓库|代码库|前后端|源码|代码|模块|架构|目录|依赖)"
                + "|\\b(?:read|explore|understand|review|familiarize)\\b.{0,50}\\b(?:project|repository|codebase|architecture|source code)\\b"))
            return general(TaskDeliveryProfile.PROJECT_UNDERSTANDING);
        if (match(clause, "(?:研究|分析|计算|评估|比较|撰写).{0,80}(?:数据|死亡率|发病率|时间序列|回归|统计|yll|arriaga|股票|风险|分布|趋势)|完成这项分析"))
            return research(TaskDeliveryProfile.DATA_ANALYSIS);
        return null;
    }

    private static Goal general(TaskDeliveryProfile profile) { return new Goal(TemplateCode.GENERAL, profile); }
    private static Goal research(TaskDeliveryProfile profile) { return new Goal(TemplateCode.RESEARCH_ANALYSIS, profile); }

    /** 肯定的库调用动作可隐含实现交付；说明性提及及纯解释不建立代码义务。 */
    private static boolean implicitLibraryAction(String clause) {
        return !clause.matches("(?is).*(?:介绍|解释|说明|讲解|文章|教程|原理|参考|示例|引用|是否|\\b(?:explain|describe|article|tutorial)\\b).*" )
                && match(clause, "^(?:请|帮我|需要)?(?:使用|用)\\s*[A-Za-z][A-Za-z0-9_.-]{1,40}\\s*(?:库|包|模块)"
                    + "(?:来|去)?(?:爬取|抓取|提取|请求|读取|解析|下载|转换|保存)[^。；;\\n]+"
                    + "|^\\s*use\\s+[A-Za-z][A-Za-z0-9_.-]{1,40}\\s+to\\s+(?:fetch|scrape|extract|parse|download|read|convert|save)\\b.+");
    }

    /** 只新增当前肯定的语言实现义务；前置条件、资料和说明性成果不借语言名建立代码交付。 */
    private static boolean implicitLanguageAction(String clause, String priorClauses) {
        if (!LANGUAGE_IMPLEMENTATION.matcher(clause).matches() || NON_CODE_LANGUAGE_ARTIFACT.matcher(clause).find()) return false;
        if (describedOrConditionalAction("", priorClauses)
                && !clause.matches("^(?:请)?(?:本次|现在|立即|这次).*")) return false;
        return !priorClauses.matches(".*(?:例如|示例|引用|原文|材料中|资料中|[“‘\\\"`]).*");
    }

    /** 禁整个代码交付才撤回隐含判断；禁示例代码等局部附加物不能取消核心实现。 */
    private static boolean declinesWholeCodeDelivery(String clause) {
        String scope = clause.replaceAll("(?:示例|样例|演示|参考|测试)(?:的)?" + CODE_ARTIFACT, "局部附加物")
                .replaceFirst("^(?:(?:请|本次|这次)\\s*)*(?:不要|不得|请勿|禁止|勿)", "不");
        return DECLINED_CODE_DELIVERY.matcher(scope).find();
    }

    /** 代码交付需要肯定的动作与交付物；“用 R 分析”“参考 Python 文档”不是脚本交付。 */
    private static boolean explicitCodeDelivery(String clause) {
        return !PENDING.matcher(clause).find() && !DECLINED_CODE_DELIVERY.matcher(clause.strip()).find() && (engineeringMatch(clause,
                "(?:提供|交付|给出|编写|输出|生成|附上|附带|包含|实现|需要|要求).{0,32}" + CODE_ARTIFACT
                        + "|\\b(?:provide|deliver|write|generate|include)\\b.{0,40}\\b(?:code|scripts?|programs?)\\b")
                || explicitJavaSourceDelivery(clause));
    }

    /** 明确交付 Java 源文件也属于代码交付；仅提到文件、介绍签名或根据源码写文档不建立工程义务。 */
    private static boolean explicitJavaSourceDelivery(String clause) {
        // 只接受句首肯定交付动作及有限修饰，防止“解释‘交付 Foo.java’”或“提供 Foo.java 的说明”被误认。
        String artifact = "[`\"“‘]?[a-z_$][a-z0-9_$]*\\.java[`\"”’]?"
                + "(?=$|[.!?]$|\\s*(?:(?:源文件|文件|源码)(?:和|及|、|$)|和|及|、|and\\b|files?\\b(?:[.!?]|$)))";
        return engineeringMatch(clause,
                "^(?:(?:请|帮我|本次|本轮|此次|这次|现在|立即|需要|要求|但|仍|同时|并)\\s*)*"
                        + "(?:交付|输出|提供|给出|生成|编写|附上|附带)\\s*"
                        + "(?:(?:完整|可直接运行|可运行|最终|一个|一份|名为|java|源码|源文件|文件)(?:的)?\\s*)*" + artifact
                        + "|^(?:please\\s+)?(?:provide|deliver|output|generate|write|include)\\s+"
                        + "(?:(?:the|a|complete|runnable|final)\\s+)*" + artifact);
    }

    /** 只识别是否交付代码的选择题，不把语言偏好或资料中的现状题提升为交付承诺。 */
    private static boolean codeDeliveryQuestion(String question) {
        return question != null && question.matches("(?s).*(?:是否|需要|要不要).{0,20}(?:代码|脚本|程序).*")
                && !question.matches("(?s).*(?:使用什么|用什么|哪种|现有|已有|当前|版本|语言偏好).*");
    }

    /** 否定后出现的交付动词不匹配，例如“不要求编写代码测试”；不跨过完整子句边界。 */
    private static boolean deliver(String clause, String object) {
        return engineeringMatch(clause, WRITE + "[^。；;\\n]{0,64}(?:" + object + ")");
    }

    /** 复用固定规则表达式，并拒绝位于否定、候选或资料引文中的交付动词命中。 */
    private static boolean match(String clause, String expression) {
        return match(clause, expression, false);
    }

    /** 有交付后果的动作共同核对既成状态与未来条件；原句及同句后续真正的命令继续保留。 */
    private static boolean engineeringMatch(String clause, String expression) {
        return match(clause, expression, true);
    }

    /** 逐个动作核对；拒绝一个描述性命中后继续检查重叠位置，避免吞掉后面的新增命令。 */
    private static boolean match(String clause, String expression, boolean engineering) {
        var matcher = PATTERNS.computeIfAbsent(expression,
                value -> Pattern.compile(value, Pattern.CASE_INSENSITIVE)).matcher(clause);
        int from = 0;
        while (from < clause.length() && matcher.find(from)) {
            from = matcher.start() + 1;
            // 同一动词被否定或判为背景后，不把它内部的“写”重新解释成另一个命令；后续独立动作仍继续检查。
            var verb = WRITING_VERB.matcher(clause).region(matcher.start(), clause.length());
            if (verb.lookingAt()) from = verb.end();
            String prefix = clause.substring(0, matcher.start());
            if (engineering && (quotedAction(clause, matcher.start())
                    || describedOrConditionalAction(clause.substring(matcher.start()), prefix))) continue;
            if (!prefix.matches(".*(?:不要求|不需要|不要|不得|不提供|不附|请勿|未要求|无需|禁止|无须|是否|例如|示例|原文|材料中|资料中)[^，。；;]{0,80}$")
                    && !prefix.matches(".*(?:不|未|勿)$")) return true;
        }
        return false;
    }

    /** 被解释或引用的动作仍是材料；真正位于引号外的肯定交付动作继续参与判断。 */
    private static boolean quotedAction(String clause, int position) {
        var ranges = QUOTED_TEXT.matcher(clause);
        while (ranges.find()) if (position > ranges.start() && position < ranges.end()) return true;
        return false;
    }

    /** 动作的既成状态和未来条件不是当前交付授权；写作与工程使用相同边界。 */
    private static boolean describedOrConditionalAction(String suffix, String prefix) {
        if (suffix.matches("^(?:开发|实现|新增|添加|增加|编写|构建|重构|迁移|修复|测试|撰写|起草|写出|写|生成|输出|提供|交付)(?:后的?|过的|完成的|完的|好的|的|了的).*")) return true;
        if (prefix.matches(".*(?:已经|已|曾经|此前已)$")) return true;
        return prefix.matches(".*(?:以后|今后|后续|将来|未来|如果|假如|一旦|每次|每当|若(?:需要|要|涉及|发生))[^。；;]{0,80}$")
                && !prefix.matches(".*(?:本次|现在|立即|这次)[^。；;]{0,25}$");
    }
}
