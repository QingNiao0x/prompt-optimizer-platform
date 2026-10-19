package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.EffectiveTaskView;
import com.promptoptimizer.enhancement.domain.PromptFormat;
import com.promptoptimizer.template.domain.PromptFrameworkSelector;

/**
 * 依据有效任务视图要求模型判断实质缺口，不用标题、关键词计数或长度冒充需求充分性。
 * 判断与增强合并在同一受控调用中，不增加独立在线裁判或重试预算。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PromptRewriteStrategy(String mode, String instruction) {
    private static final String GAP_GUIDANCE = """
            先用taskView的原始任务、有效确认及适用资料明确目标作品，把已提供信息用于具体内容；范围、格式和篇幅优先于框架。再判断影响交付的真正缺口，不按中文标题、关键词数量或输入长短判断充分性。
            返回assessment：sufficiency为SUFFICIENT或NEEDS_COMPLETION，gaps最多6项，presentation为COMPACT或STRUCTURED。
            gap仅记录影响执行或作品质量的具体缺口，每项含kind、missing、completion、basis。kind可为EXECUTION_STEP、INPUT_OUTPUT、EDGE_CASE、QUALITY_CHECK、PROFESSIONAL_OPTION、USER_DECISION。
            basis写一句与本次目标、资料或明确要求对应的依据，不输出内部推理。没有实质缺口时SUFFICIENT且gaps=[]，保留有效原句及明确限制，只整理必要位置，不为四要素和验收新增章节。
            有缺口时优先补原定交付缺少的内容与相关维度，再补必要执行动作、拟议主方案、适用条件和结果检查；不以反复强调方法、资料限制替代内容补全。主方案自身保持一致，替代方法及其条件与主方案区分，不把语言、算法、口径或研究参数伪装成已选事实。仅真正需用户决定且改变结果的未知进入ambiguities，其余由执行者完成。
            NEEDS_COMPLETION没有USER_DECISION缺口时，ambiguities必须为空。用户委托设计的常规方法属于PROFESSIONAL_OPTION，不是用户必须先回答的选择；真实授权、资料冲突及用户明确保留的未决参数继续归入USER_DECISION。
            pendingDecisions中designDelegated=true仅授权提出拟议设计，scope仍为UNRESOLVED，既有参数不能写成已选；对已委托的拟议方法给出可实施主建议及条件，不重复索要设计答案。实际参数、独立对象和新条件仍按各自来源与状态处理。
            每个新增要求应对应真实缺口，充分需求保留完整有效原句；Plan新增决定更新责任段及失效的旧选择，独立未知、对象、条件和来源仍保留。未提供材料表示未知，不能断言相关实现、规则或资料不存在。
            四要素用于内部覆盖校验：背景使用影响任务的已知上下文，任务写具体动作和内容，输出沿用目标作品要求，约束保留适用边界。没有相关背景就不编造，不把通用栏目留给用户填写，不靠标题、复述或长度证明补全。
            简单沟通的补全服务行动、受众与语气，不补造截止日期、汇总职责或工作进展，不擅自放宽行动要求；委婉催交仍须表达请对方发送，不改成无需着急或可不提交。未知称呼可用自然中性称呼，不制造姓名填空模板。
            未要求且无独立检查价值时省略ACCEPTANCE。对仅有宽泛专业目标的需求，缺少操作化方法与比较办法属于实质缺口，不能以“下游自己设计全部细节”为由判为充分。简单沟通任务可用常规表达直接完成，不为凑gaps制造问题。
            taskView.userMeaningAnchors把受众和研究主题按有效输入分别定位：为某人撰写不是以该人为研究对象，不从受众推定受试者。沿用明确研究主题，实际招募建议另标为建议。
            """;

    // 主交付画像只决定相关信息的组织；研究专项方法必须来自当前任务，不能把某一评测题扩展为所有研究模板。
    private static final String RESEARCH_GUIDANCE = """
            研究或查证交付以主题内容、发现及其解释为主体，必要的来源和可比条件随对应结论说明。
            统计计算、数据排名或研究判断确实依赖某定义时才补相关口径；不把定义口径、资料缺口和方法清单写成主要交付。
            用户要求研究设计时提出适用的拟议方法与执行路径，方法正文、报告、方案等原定类型仍保持。
            方法建议不冒充已实施、实际参数或用户确认；重要授权、真实来源冲突及用户保留选择继续提问。
            """;

    /** 使用共享交付类型与确认状态；具体缺口由同次模型判断给出。 */
    public static PromptRewriteStrategy forTask(EffectiveTaskView task) {
        return forTask(task, null);
    }

    /**
     * 显式模式参与同一次增强的写作规划，不另起模型链路；缺省模式继续兼容旧客户端的自动判断。
     * 两种表达共用任务、确认、实质补全与安全边界，不以删规则或扩大交付制造差异。
     */
    public static PromptRewriteStrategy forTask(EffectiveTaskView task, PromptFormat format) {
        boolean research = task.intent().templateCode() == com.promptoptimizer.enhancement.domain.TemplateCode.RESEARCH_ANALYSIS
                || task.intent().deliveryProfile() == com.promptoptimizer.template.domain.TaskDeliveryProfile.ACADEMIC_METHODS;
        String common = GAP_GUIDANCE + (research ? RESEARCH_GUIDANCE : "") + task.meaning().evaluationGuidance()
                + "\n本次交付画像：" + task.intent().deliveryProfile()
                + "；有效执行要求来源数：" + task.executionRequirements().size()
                + "；未决来源条数：" + task.pendingDecisions().size()
                + "。计数只说明输入构成，不代表充分性。";
        var selection = PromptFrameworkSelector.select(task);
        if (format == PromptFormat.COMPACT) {
            return new PromptRewriteStrategy("NATURAL_FRAMEWORK", common + "\n" + selection.instruction() + "\n" + """
                    本次采用自然表达，assessment.presentation=COMPACT。先按原定任务和上下文写清具体内容及交付，再用主框架组织相关信息；框架不决定新增哪些任务，不是先写模块正文再去掉标题。
                    sections仍返回内部四要素，但content应是可连读的具体指令；把规则紧邻其适用动作或交付表达，不写“背景：”“任务：”“输出：”等模块标签，不逐项展示框架字母或框架名称。每项要求只放一处；允许有助执行的短列表、原有代码与必要引用。
                    优先保留充分需求的有效原句；只在有缺口时补相关动作和条件。不给简单任务扩写角色背景、流程或验收，不把复杂任务缩成空架子。未决事项仍由平台统一保留，不能压缩掉否定、例外、对象、参数状态和来源。
                    """);
        }
        if (format == PromptFormat.STRUCTURED) {
            return new PromptRewriteStrategy("FOCUSED_MODULES", common + "\n" + selection.instruction() + "\n" + """
                    本次采用结构化表达，assessment.presentation=STRUCTURED。先划清模块职责再生成正文：背景只留影响执行的事实，任务集中表达目标与动作，输出明确交付，约束集中表达业务边界；有独立检查价值时才加验收。
                    有依赖的步骤按顺序组织；业务规则明确对象、触发条件、动作与例外，必要的正反场景用于说明适用与不适用，不能自动编造案例或增加示例要求。不要从自然段落机械拆标题，也不要在各节重复同一要求。
                    简单且充分的任务保持短小，复杂多目标才展开。与自然模式保持同一事实、有效确认、未决状态和原定交付；原文严格格式与禁例继续约束下游作品，不把提示词章节强加给作品。
                    """);
        }
        return new PromptRewriteStrategy("ASSESS_EXECUTION_GAPS", common
                + "\n未指定生成模式时，简单邮件、纯译文或严格格式任务优先COMPACT；复杂多目标任务按依赖和交付组织STRUCTURED。");
    }

    /** 兼容旧调用方；没有任务视图时不在本地猜测充分性。 */
    public static PromptRewriteStrategy forPrompt(String raw) {
        return new PromptRewriteStrategy("ASSESS_EXECUTION_GAPS", GAP_GUIDANCE);
    }
}
