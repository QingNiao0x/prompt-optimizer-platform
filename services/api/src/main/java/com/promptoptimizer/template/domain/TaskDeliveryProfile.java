package com.promptoptimizer.template.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 根据用户要求交付的作品调整通用模板，不增加公开模板代码或假定专业业务事实。
 * 身份、附件主题和明确排除的工作不能替代本次交付目标。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public enum TaskDeliveryProfile {
    GENERAL("围绕用户目标提供所需交付物，不增加未要求的说明、报告、代码或执行步骤。",
            "完整满足明确目标、事实和约束；未决条件保留，不把假设写成事实。"),
    TRANSLATION("按指定语言、术语、语气及段落输出译文；限定只输出译文时不附解释。",
            "译文忠实、术语一致、语气适合受众，保持要求的格式，不扩写原文事实。"),
    NEWS_RELEASE("按指定受众、篇幅和发布场景交付新闻稿；只输出用户要求的标题、正文或其他部分。",
            "核心信息准确，发布状态和功能边界清楚；满足篇幅与结构，不编造引语、数字或上线承诺。"),
    USER_GUIDE("围绕已提供的功能和目标用户编写操作指南，说明步骤与必要边界，不编造产品能力。",
            "操作顺序连贯，术语易懂，与已知界面及功能一致；只交付所要求的指南内容。"),
    TEACHING("按目标学生、课时、材料与学习目标组织教学内容，仅提供要求的课程、练习或答案。",
            "课时合计准确，知识与答案正确，难度适合学生，教学材料和交付范围符合要求。"),
    INSTITUTIONAL_REPORT("根据报告用途、受众及已提供材料组织报告，区分已完成工作、问题与下一步计划。",
            "事实和数据可核对，结论有依据，结构适合报告用途，不虚构成效或行政要求。"),
    ACADEMIC_METHODS("只交付指定论文部分或研究方案，明确研究对象、方法、变量与评估步骤，不补写未要求章节或实际结果。",
            "方法与研究问题一致，定义和比较条件可复核；不编造数据、结果、文献或已完成研究状态。");

    private final String outputGuidance;
    private final String acceptanceGuidance;

    TaskDeliveryProfile(String outputGuidance, String acceptanceGuidance) {
        this.outputGuidance = outputGuidance;
        this.acceptanceGuidance = acceptanceGuidance;
    }

    /** 只识别明确的作品目标，不以“研究生”等受众名词判定科研任务。 */
    public static TaskDeliveryProfile identify(String rawPrompt) {
        String prompt = rawPrompt == null ? "" : rawPrompt.toLowerCase(Locale.ROOT);
        if (goal(prompt, "翻译|译文|译成|translate", true)) return TRANSLATION;
        if (goal(prompt, "新闻稿|新闻通稿|press release", false)) return NEWS_RELEASE;
        if (goal(prompt, "用户指南|使用指南|操作指南|使用手册|用户手册", false)) return USER_GUIDE;
        if (goal(prompt, "教案|教学设计|教学方案|课程设计|课堂活动", false)) return TEACHING;
        if (goal(prompt, "工作报告|工作总结|年度报告|机关报告|调研报告|汇报材料", false)) return INSTITUTIONAL_REPORT;
        if (goal(prompt, "论文(?:的)?方法(?:部分|提纲|章节)|方法提纲|研究方案|研究设计", false)) return ACADEMIC_METHODS;
        return GENERAL;
    }

    /** 模板仅指导作品类型，具体格式、字数和只输出约束仍以原始需求与绑定答案为准。 */
    public String outputGuidance() { return outputGuidance; }

    /** 返回对应作品的质量核查要点，不为非软件任务追加软件测试验收。 */
    public String acceptanceGuidance() { return acceptanceGuidance; }

    /** 匹配肯定交付动词；正文引用、禁止输出和材料标题本身不能证明用户要求该作品。 */
    private static boolean goal(String prompt, String object, boolean translation) {
        String verb = translation ? "(?:翻译|译成|translate)" : "(?:撰写|编写|起草|写|生成|输出|提供|交付|拟定|制作|设计|制定)";
        String expression = translation ? verb : verb + "[^。；;\\n]{0,32}(?:" + object + ")";
        return Pattern.compile("(?<!不)(?<!不得)(?<!不要)(?<!无需)" + expression)
                .matcher(prompt).find();
    }
}
