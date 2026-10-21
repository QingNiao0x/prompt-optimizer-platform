package com.promptoptimizer.template.domain;

import com.promptoptimizer.enhancement.domain.EffectiveTaskView;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.enhancement.domain.TemplateCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 复用主交付画像选择一个主框架，只在同类目标内以当前肯定指令细分组织重点。
 * 不读取附件或未选候选，不判断事实真伪，不改变Plan职责或增加模型调用。
 * 有限中英文表达不能覆盖全部语义，无法可靠识别时使用APE或ICIO保守组织。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class PromptFrameworkSelector {
    private static final Pattern STRICT_JSON = pattern("(?:只|仅|严格)(?:输出|返回|交付)?(?:合法|有效|一个)?\\s*json|\\b(?:only|strict)\\s+(?:valid\\s+)?json\\b");
    private static final Pattern IMPROVEMENT = pattern("(?:制定|设计|编写|提出|完善).{0,35}(?:改进计划|改进方案|质量改进|评测流程|复验流程)|\\b(?:design|create|develop)\\b.{0,45}\\b(?:improvement|evaluation) (?:plan|process)\\b");
    private static final Pattern BUSINESS = pattern("(?:整理|明确|设计|核对|审查).{0,35}(?:业务规则|退款规则|审批规则|触发条件|适用范围)|\\b(?:define|review|organize)\\b.{0,40}\\bbusiness rules\\b");
    private static final Pattern PROCESS = pattern("(?:排错|排查|操作流程|操作步骤|测试计划|验证流程|sop)|\\b(?:troubleshoot|procedure|step-by-step)\\b");
    private static final Pattern TRANSFORMATION = pattern("(?:提取|抽取|转换|映射).{0,45}(?:字段|日期|姓名|格式|csv|记录|数据)|(?:编写|整理|生成).{0,25}(?:接口|api).{0,12}(?:文档|说明)|\\b(?:extract|convert|transform)\\b.{0,45}\\b(?:fields|dates|csv|records|data)\\b");
    private static final Pattern PLAN = pattern("(?:制定|设计|规划|提出|编写).{0,30}(?:迁移方案|架构方案|实施方案|研究方案|研究设计|阶段计划|实施计划)|\\b(?:design|draft|create)\\b.{0,40}\\b(?:migration|architecture|implementation) (?:plan|proposal)\\b");
    private static final Pattern CREATIVE = pattern("(?:创作|构思|设计|写|生成|提供).{0,40}(?:创意|宣传文案|广告文案|故事|小说|口号|候选标题)|\\b(?:create|brainstorm|write)\\b.{0,40}\\b(?:slogans|stories|creative|copywriting)\\b");
    private static final Pattern WRITING = pattern("(?:写|撰写|起草|改写|润色).{0,35}(?:邮件|短信|消息|通知|文章|文稿)|\\b(?:write|draft|rewrite)\\b.{0,35}\\b(?:email|message|letter|article)\\b");
    private static final Pattern EXPLANATION = pattern("(?:解释|说明|讲解|改写).{0,60}|\\b(?:explain|rephrase)\\b.{0,80}");
    private static final Pattern LEARNING = pattern("(?:面向|向|为).{0,25}(?:初学者|学生|新手).{0,25}(?:解释|讲解|教学|培训)|(?:入门|进阶|学习目标).{0,20}(?:说明|讲解|课程)|\\b(?:teach|train)\\b.{0,40}\\b(?:beginners|students)\\b");
    private static final Pattern TASK_LIST = pattern("(?:列出|整理|安排).{0,30}(?:任务清单|待办|工作清单)|\\b(?:list|organize)\\b.{0,30}\\b(?:tasks|to-do|checklist)\\b");
    private static final Pattern MATERIAL = pattern("(?:根据|依据|基于).{0,25}(?:附件|材料|文档|原文|记录|数据)|\\b(?:based on|using)\\b.{0,30}\\b(?:documents|materials|records|data)\\b");
    private static final Pattern MATERIAL_ACTION = pattern("(?:总结|概括|归纳|摘要|改写|润色)|\\b(?:summarize|summarise|paraphrase)\\b");
    private static final Pattern QUOTED = Pattern.compile("[“‘\"「『].*?[”’\"」』]");
    private static final Pattern EXCLUDED = pattern("^(?:请)?(?:不要|不得|禁止|不能|无需|不必|不需要|不要求|尚未|尚待|未决定|如果|若|假如|以后|未来|后续|候选|例如|示例|参考资料|资料|材料|原文)|^(?:do not|don't|never|if|later|example|reference|source(?: text)?|materials?|next (?:time|stage|phase))\\b");
    private static final Pattern NON_CURRENT_PREFIX = pattern("(?:不|不要|不得|禁止|无需|不必|不需要|不要求|尚未|未决定|以后|未来|后续|如果|假如|是否|暂不|后再|再考虑"
            + "|\\b(?:do not|don't|never|not|later|if|whether|next (?:time|stage|phase))\\b)[^。；;，,\\n.!?]{0,35}$");
    private static final String COMMON = "只采用该主框架的适用要素，框架名称不写入可复制正文。按原任务及已有信息填入具体动作、必要规则和交付，重叠职责合并表达；"
            + "无依据的角色、背景、示例或实验不凑字段。四要素仍用于内部覆盖校验；保留交付层级、有效确认、否定、例外与未决状态，充分需求保留有效原句。";

    private PromptFrameworkSelector() { }

    /** 使用既有TaskIntentResolver产出的主画像，组织偏好不能把资料主题变为新目标。 */
    public static PromptFrameworkSelection select(EffectiveTaskView task) {
        if (task == null) return selection(null, PromptFramework.APE, "缺少可识别交付，保守整理行动与期望");
        var intent = task.intent();
        var profile = intent.deliveryProfile();
        String active = currentInstructions(task);
        if (profile == TaskDeliveryProfile.TRANSLATION || matches(STRICT_JSON, active))
            return selection(task, PromptFramework.ICIO, "译文或严格格式优先分清输入与输出边界");
        if (matches(IMPROVEMENT, active) && (profile == TaskDeliveryProfile.GENERAL
                || profile == TaskDeliveryProfile.ACADEMIC_METHODS || profile == TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION))
            return selection(task, PromptFramework.BROKE, "任务要求改进或评测流程，需对应证据、目标和检查结果");
        if (profile == TaskDeliveryProfile.LEGAL_MATERIAL || profile == TaskDeliveryProfile.MEDICAL_MATERIAL
                || matches(BUSINESS, active) && (profile == TaskDeliveryProfile.GENERAL || profile == TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION
                || profile == TaskDeliveryProfile.MATERIAL_SYNTHESIS))
            return selection(task, PromptFramework.COAST, "业务对象的条件、状态与作用范围是组织重点");
        if (intent.templateCode() == TemplateCode.BUG_FIX || intent.templateCode() == TemplateCode.TESTING
                || profile == TaskDeliveryProfile.USER_GUIDE && matches(PROCESS, active))
            return selection(task, PromptFramework.RISE, "排查、操作或测试交付需要依据输入安排必要步骤");
        if (matches(TRANSFORMATION, active) && (profile == TaskDeliveryProfile.GENERAL
                || profile == TaskDeliveryProfile.MATERIAL_SYNTHESIS || profile == TaskDeliveryProfile.USER_GUIDE
                || profile == TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION))
            return selection(task, PromptFramework.TRACE, "提取、转换或接口说明需明确输入输出的对应关系");
        if (intent.templateCode() == TemplateCode.REFACTORING || profile == TaskDeliveryProfile.ACADEMIC_METHODS
                || matches(PLAN, active) && (profile == TaskDeliveryProfile.GENERAL || profile == TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION))
            return selection(task, PromptFramework.ROSES, "规划或研究方案需区分现状、目标状态、条件和步骤");
        if (profile == TaskDeliveryProfile.TEACHING
                || profile == TaskDeliveryProfile.GENERAL && matches(LEARNING, active))
            return selection(task, PromptFramework.RISE, "教学需依据已知受众与输入安排学习步骤和预期结果");
        if (profile == TaskDeliveryProfile.MATERIAL_SYNTHESIS || profile == TaskDeliveryProfile.MEETING_MINUTES
                || profile == TaskDeliveryProfile.GENERAL && matches(MATERIAL, active) && matches(MATERIAL_ACTION, active))
            return selection(task, PromptFramework.CARE, "以材料为依据整理交付，区分来源和期望结果");
        if (profile == TaskDeliveryProfile.GENERAL && matches(CREATIVE, active))
            return selection(task, PromptFramework.CRISPE, "创意交付需保留风格与候选表达空间");
        if (profile == TaskDeliveryProfile.NEWS_RELEASE || profile == TaskDeliveryProfile.INSTITUTIONAL_REPORT
                || profile == TaskDeliveryProfile.ACADEMIC_WRITING || profile == TaskDeliveryProfile.USER_GUIDE
                || profile == TaskDeliveryProfile.GENERAL && matches(WRITING, active))
            return selection(task, profile == TaskDeliveryProfile.GENERAL ? PromptFramework.APE : PromptFramework.RACE,
                    "写作行动对应已知受众、用途和交付期望，沿用原有语气与格式");
        if (profile == TaskDeliveryProfile.PROJECT_UNDERSTANDING || profile == TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION
                || profile == TaskDeliveryProfile.DATA_ANALYSIS)
            return selection(task, PromptFramework.RACE, "理解、实现或分析需把行动与适用上下文和交付相连");
        if (matches(EXPLANATION, active)) return selection(task, PromptFramework.ERA, "简明解释或改写先限定交付期望");
        if (matches(TASK_LIST, active)) return selection(task, PromptFramework.TAG, "单一任务清单突出动作与目标");
        if (matches(MATERIAL, active)) return selection(task, PromptFramework.ICIO, "有明确输入材料时分清指令、输入和输出");
        return selection(task, PromptFramework.APE, "未识别专门组织需求，保守明确行动、目的与期望");
    }

    /** 当前框架指导进入同次生成；重要未知仍由共享任务视图保留，不因框架自行选值。 */
    private static PromptFrameworkSelection selection(EffectiveTaskView task, PromptFramework framework, String reason) {
        String boundary = "";
        if (task != null && !task.pendingDecisions().isEmpty())
            boundary += "重要未决参数继续保持原状态，仅限制依赖内容，不能为满足框架自行选值。";
        // 内部分段类型用于协议校验，不作为模型必须逐节填满的正文大纲。
        String audit = "本次主框架：" + framework.name() + "；框架职责：" + framework.focus()
                + "；选择依据：" + reason + "。";
        return new PromptFrameworkSelection(framework, reason,
                audit + framework.guidance() + framework.boundary() + COMMON + boundary, framework.sectionOrder());
    }

    /**
     * 仅细分组织重点，不识别新交付。引用、资料节和未来/否定句不触发策略；完整原文仍交给原链路。
     * 绑定答案只取TASK/OUTPUT的执行投影，不从问题里的未选候选或附件主题推断风格。
     */
    private static String currentInstructions(EffectiveTaskView task) {
        var result = new ArrayList<String>();
        boolean fenced = false, reference = false;
        List<String> inputs = new ArrayList<>(List.of(task.effectivePrompt()));
        task.executionRequirements().stream().filter(requirement -> requirement.destination() == PromptSectionType.TASK
                || requirement.destination() == PromptSectionType.OUTPUT).map(EffectiveTaskView.ExecutionRequirement::statement).forEach(inputs::add);
        for (String input : inputs) {
            for (String line : input.split("\\R")) {
                String text = line.strip();
                if (text.startsWith("```") || text.startsWith("~~~")) { fenced = !fenced; continue; }
                if (fenced || text.startsWith(">") || text.startsWith("|")) continue;
                String heading = text.replaceAll("^[#*\\s]+|[*\\s]+$", "");
                if (heading.toLowerCase(Locale.ROOT).matches("(?:背景|项目背景|示例|参考资料|原文|材料|资料|已知资料|background|examples?|reference|source(?: text)?|materials?)[：:]?")) { reference = true; continue; }
                if (heading.toLowerCase(Locale.ROOT).matches("(?:任务|任务目标|目标|需求|输出|交付|输出要求|期望输出|task|goal|request|output|deliverable)[：:]?")) { reference = false; continue; }
                if (reference) continue;
                text = QUOTED.matcher(text).replaceAll("");
                for (String part : text.split("[。；;，,]")) {
                    String clause = part.replaceFirst("^[-*\\d.、\\s]+", "").strip();
                    if (!clause.isEmpty() && !EXCLUDED.matcher(clause).find()) result.add(clause);
                }
            }
            // 每个独立绑定执行投影都有自己的来源范围，不能继承原文末尾的资料节或代码围栏状态。
            fenced = false; reference = false;
        }
        return String.join("\n", result).toLowerCase(Locale.ROOT);
    }

    /** 静态编译匹配式，避免每次请求重复编译或缓存用户正文。 */
    private static Pattern pattern(String expression) { return Pattern.compile(expression, Pattern.CASE_INSENSITIVE); }

    /** 动作前的否定、未来或疑问同样限制命中，不把英文句号后的禁止动作视为本次目标。 */
    private static boolean matches(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            int start = text.lastIndexOf('\n', matcher.start()) + 1;
            if (!NON_CURRENT_PREFIX.matcher(text.substring(start, matcher.start())).find()) return true;
        }
        return false;
    }
}
