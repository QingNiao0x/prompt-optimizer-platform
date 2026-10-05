package com.promptoptimizer.template.domain;

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
    USER_GUIDE("围绕已核实功能和目标用户编写操作指南，说明步骤与必要边界；资料未说明的能力标为未核实，不推断为不存在。",
            "操作顺序连贯，术语易懂，与已知界面及功能一致；区分已知存在、已知不存在与未核实，只交付要求的指南内容。"),
    TEACHING("按目标学生、课时、材料与学习目标组织教学内容，仅提供要求的课程、练习或答案。",
            "课时合计准确，知识与答案正确，难度适合学生，教学材料和交付范围符合要求。"),
    INSTITUTIONAL_REPORT("根据报告用途、受众及已提供材料组织报告，区分已完成工作、问题与下一步计划。",
            "事实和数据可核对，结论有依据，结构适合报告用途，不虚构成效或行政要求。"),
    ACADEMIC_METHODS("只交付指定论文部分或研究方案，明确相关对象、方法、变量与评估步骤；未决评分尺度、分母等只保留为待定，不默认取值，不补写实际结果。",
            "方法与研究问题一致，定义和比较条件可复核；已定与未决参数分开，不编造数据、结果、文献或已完成研究状态。"),
    ACADEMIC_WRITING("围绕指定学科、论文阶段和章节组织学术文本；遵守已给写作范围、引用格式与资料边界，不替未完成研究编造结果。",
            "论点与证据对应，概念、引用及研究状态可核对；遵守章节、篇幅和学术诚信要求。"),
    DATA_ANALYSIS("按已给来源、字段、统计口径与比较范围交付分析方案、表结构或要求的分析结果；缺失和未核实不改成零值，不默认未知口径；只有要求时才提供代码。",
            "指标、分母、缺失值和时间边界一致；建议或待定参数不冒充已选方法，实际结论能回到输入数据。"),
    MATERIAL_SYNTHESIS("根据提供的材料交付所需摘要、对照表或提取清单，区分已知事实、资料分歧及未决条件，不增加未要求的实现任务。",
            "重要信息不遗漏，出处与材料内容相符；不把未见信息写成不存在，不把例子写成事实。"),
    MEETING_MINUTES("依据会议记录整理议题、明确决定和行动项；负责人、期限未指定时标明未指定，不替与会者作出决定。",
            "讨论、决定与待办分开，行动项可核对；不虚构责任人、期限或会议结论。"),
    LEGAL_MATERIAL("按指定用途整理法律或法务材料、规则对照与未决问题；保留来源、适用主体、生效时间及不同版本，不编造法条或专业结论。",
            "对象、条件、取值及来源对应，冲突和未签署状态保留；只交付所要求的材料整理或方案，不冒充已生效的法律意见。"),
    MEDICAL_MATERIAL("依据提供的医疗材料整理步骤、字段对照和检查清单；保留不同机构的数据含义、缺失状态及权限边界，不将材料整理扩展为诊断或未授权处理。",
            "机构、字段、状态、分母与日期边界一致，缺失不当作零值；不推断其他机构的数据或映射，未知信息明确保留。"),
    SOFTWARE_IMPLEMENTATION("围绕明确的软件目标交付实现或验证方案，具体指导沿用所选开发、修复、重构或测试模板。",
            "已有行为和接口兼容，明确范围与验证证据，不把拟实现能力写成已完成。");

    private final String outputGuidance;
    private final String acceptanceGuidance;

    TaskDeliveryProfile(String outputGuidance, String acceptanceGuidance) {
        this.outputGuidance = outputGuidance;
        this.acceptanceGuidance = acceptanceGuidance;
    }

    /** 只识别明确的作品目标，不以“研究生”等受众名词判定科研任务。 */
    public static TaskDeliveryProfile identify(String rawPrompt) {
        return TaskIntentResolver.identifyDelivery(rawPrompt);
    }

    /** 模板仅指导作品类型，具体格式、字数和只输出约束仍以原始需求与绑定答案为准。 */
    public String outputGuidance() { return outputGuidance; }

    /** 返回对应作品的质量核查要点，不为非软件任务追加软件测试验收。 */
    public String acceptanceGuidance() { return acceptanceGuidance; }

    /** 软件画像不与新闻、指南等作品指导互换；公开软件模板仍优先。 */
    public boolean softwareTask() {
        return this == SOFTWARE_IMPLEMENTATION;
    }

    /** 交付画像限定追问价值，不替用户选择未知的专业口径、数据来源或业务授权。 */
    public String planningGuidance() {
        return switch (this) {
            case TRANSLATION -> "目标语言、受众、语气和段落已明确时沿用。普通词语译法、标点及常规语气由执行者处理，"
                    + "不再询问已确定段落数或是否增加解释；只有必须使用但缺失的正式名称、专业术语冲突等会改变结果的事项需要确认。";
            case TEACHING -> "学生、已有知识、课时、学习目标和交付物已明确时，教师可自行设计适龄例题、呈现方式及解释深度。"
                    + "不让用户逐题选择常规细节；涉及超出学段的内容、特殊学习需要或材料限制且尚未知时才询问。";
            case NEWS_RELEASE, INSTITUTIONAL_REPORT, MEETING_MINUTES, USER_GUIDE ->
                    "普通标题、清单排版和段落组织由执行者处理，不制造格式问卷。未提供的名称或日期不要编造；"
                            + "只有完成用户明确要求的成品必须具备且没有允许替代表达的事实才询问，材料缺口与业务决定分开。";
            case ACADEMIC_METHODS, ACADEMIC_WRITING, DATA_ANALYSIS ->
                    "沿用已确定的研究问题、对象、时间、口径及交付范围。仅询问会改变研究结论或公平比较的未决参数；"
                            + "常规章节和表格排版自行组织，不将只写方案改成实际实验、计算或结果。";
            default -> "只确认会改变目标、范围、业务行为、数据口径或执行前提的关键缺口；已知事实和常规组织方式不要重问。";
        };
    }

    /** 示例仅在既有开关启用时交付，不为研究和写作虚构真实结果。 */
    public String exampleGuidance() {
        return switch (this) {
            case TRANSLATION -> "需要示例时仅提供与原文相关的术语或格式示例，遵守只输出译文的限制。";
            case ACADEMIC_METHODS, ACADEMIC_WRITING, DATA_ANALYSIS, MEDICAL_MATERIAL, LEGAL_MATERIAL ->
                    "仅在用户要求且资料允许时提供空表、格式或计算步骤示例，示例不得冒充真实数据或结论。";
            default -> "只有示例有助于当前交付且不违反输出限制时，提供一个相关最小示例。";
        };
    }
}
