package com.promptoptimizer.provider.domain;

/**
 * 区分优化时可见的请求与下游执行现场，避免把未附资料当作现实缺失并降级原交付。
 * 沿用31范围守门、第四版协议及完整来源/题答保护，只改变职责解释，不替用户补造事实。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptExecutionBoundaryInstructions {
    public static final String VERSION = "34";

    private static final String COMMON = replaceUnique(NaturalPromptInstructions.COMMON,
            "资料不足只限制依赖它的部分，继续保留原定报告、代码、方案等交付，不改成补材料问卷。\n",
            """
            当前编排的是执行委托，不是在执行现场判断资料是否齐全。本次未附的数据、文件、工具或参数，
            只表示优化时未见，不能推断它们不存在、尚未确定或必须由用户补齐，更不能写成新增背景事实。
            将检索、读取适用材料、选择合适方法和核查依据落实为执行者工作，继续交付用户原定成品。
            执行时实际获得的资料才决定哪些实证判断可做；届时确实无法支持的部分局部说明，不能预先降为方案或空模板。
            """);

    private static final String TASK_RECORD = replaceUnique(NaturalPromptInstructions.TASK_RECORD,
            "未出现的条件不杜撰；additions、openDecisions无对应内容时为空数组。\n",
            """
            inputReadiness只评估当前能否形成有效执行请求，不判断下游是否已有资料或具备工具。
            requirements只记录原有要求和明确来源事实；未提及的信息不写成现实未知、缺失、待确认或占位符要求。
            additions.gap说明指令表达或处理关系的实际缺口，不以本次未附资料推断执行现场缺资料。
            openDecisions记录有来源支持的重要未知、用户保留取舍和来源冲突，不因本次未附材料或未列方法便建立未决事项。
            用户明确陈述的未知、不存在、待定和禁止推测保持原状态；没有对应补充或用户取舍时数组为空。
            """);

    /** 同次生成仅更换职责解释，不增加分析、修订或评审阶段。 */
    public static final String ENHANCE = enhance();
    /** Plan继续判断重要用户决定，不将未见执行材料写成现实缺失。 */
    public static final String PLAN = NaturalPromptScopedInstructions.PLAN.replace(NaturalPromptInstructions.COMMON,
            replaceUnique(COMMON, "将检索、读取适用材料、选择合适方法和核查依据落实为执行者工作，继续交付用户原定成品。\n",
                    "Plan仍须询问执行者不能合理确定、且答案实质改变结果的用户独有信息、保留选择或授权；提问不等于断言现实资料缺失。\n"))
            + "\ninputReadiness仅描述本次请求能否被整理；summary不将未附资料写成现实缺失。重要用户决定仍按问题的实际影响判断。\n";
    /** 准备阶段只识别已有委托，不预断下游资料和工具可用性。 */
    public static final String PREPARE = NaturalPromptScopedInstructions.PREPARE.replace(NaturalPromptInstructions.COMMON, COMMON);

    /** 替换冻结段而非叠加同义禁令，避免旧资料缺口条款与新职责同时竞争正文。 */
    private static String enhance() {
        String source = replaceUnique(NaturalPromptInstructions.ENHANCE, NaturalPromptInstructions.COMMON, COMMON);
        source = replaceUnique(source, """
                仅在任务确实依赖统计计算、数据排名或研究判断时，补充必要的比较定义与证据要求，
                简要放在相关判断附近；资料不足时说明受影响的结论，仍完成其余内容。不要将口径定义变成整份交付的主体。
                """, """
                让执行者直接回答用户关心的内容、比较或发现，并用适用材料支持；必要依据和适用限制随对应结果简要说明。
                完成用户委托的查询、分析或方法选择，不将它们反推成用户必须先填写的定义清单。
                """);
        source = replaceUnique(source, """
                用户独有的实际参数、明确保留的取舍、权限和来源冲突继续保持未决；只限制依赖它的部分，
                不因尚未提供普通执行细节而要求用户先决定全部方法。保持全部否定、例外、并列要求和条件。
                """, """
                用户明确的实际参数、保留取舍、权限、来源冲突及未知状态保持原样，不替用户选现实值。
                未描述的事实仍未描述；普通设计与表达由执行者合理完成。保持全部否定、例外、并列要求和条件。
                """);
        return replaceUnique(source, NaturalPromptInstructions.TASK_RECORD, TASK_RECORD);
    }

    /** 冻结段必须唯一存在，防止兼容演进时静默丢掉职责差异。 */
    private static String replaceUnique(String source, String original, String replacement) {
        int first = source.indexOf(original);
        if (first < 0 || first != source.lastIndexOf(original)) throw new IllegalStateException("执行职责指导的冻结段已变化");
        return source.replace(original, replacement);
    }

    private NaturalPromptExecutionBoundaryInstructions() { }
}
