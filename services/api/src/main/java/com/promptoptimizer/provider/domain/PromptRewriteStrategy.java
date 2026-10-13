package com.promptoptimizer.provider.domain;

import com.promptoptimizer.enhancement.domain.EffectiveTaskView;

/**
 * 依据有效任务视图要求模型判断实质缺口，不用标题、关键词计数或长度冒充需求充分性。
 * 判断与增强合并在同一受控调用中，不增加独立在线裁判或重试预算。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PromptRewriteStrategy(String mode, String instruction) {
    private static final String GAP_GUIDANCE = """
            先理解本次交付物，再结合taskView的有效确认、适用资料和真正未决事项判断能否执行，不按中文标题、关键词数量或输入长短判断充分性。
            返回assessment：sufficiency为SUFFICIENT或NEEDS_COMPLETION，gaps最多6项，presentation为COMPACT或STRUCTURED。
            gap仅记录影响执行或作品质量的具体缺口，每项含kind、missing、completion、basis。kind可为EXECUTION_STEP、INPUT_OUTPUT、EDGE_CASE、QUALITY_CHECK、PROFESSIONAL_OPTION、USER_DECISION。
            basis写一句与本次目标、资料或明确要求对应的依据，不输出内部推理。没有实质缺口时SUFFICIENT且gaps=[]，保留有效原句及明确限制，只整理必要位置，不为四要素和验收新增章节。
            有缺口时只补相关内容：模糊需求给具体执行动作、候选方法、适用条件和结果检查；专业建议标明候选，不把语言、算法、口径或研究参数伪装成已选事实。仅真正需用户决定且改变结果的未知进入ambiguities，其余由执行者完成。
            NEEDS_COMPLETION没有USER_DECISION缺口时，ambiguities必须为空。用户委托设计的常规方法属于PROFESSIONAL_OPTION，不是用户必须先回答的选择；真实授权、资料冲突及用户明确保留的未决参数继续归入USER_DECISION。
            pendingDecisions中designDelegated=true仅授权提出拟议设计，scope仍为UNRESOLVED，既有参数不能写成已选；对已委托的拟议方法给出可实施主建议及条件，不重复索要设计答案。实际参数、独立对象和新条件仍按各自来源与状态处理。
            每个新增要求应对应真实缺口；原文已有的公平对照、方法条件、数值与格式不再次扩写。Plan新增决定只更新责任段并同步失效的旧表述；保留独立未知、对象、条件及来源。
            对充分需求，把有效原句分别放入其责任段，不同义扩写或重述成验收列表；不要在背景盘点缺项。对仅有宽泛专业目标的需求，缺少操作化方法与比较办法属于实质缺口，不能以“下游自己设计全部细节”为由判为充分。简单沟通任务可用常规表达直接完成，不为凑gaps制造问题。
            简单邮件、纯译文或严格格式任务优先COMPACT，四要素各写最短必要内容，不在背景、任务、输出重复同一句；复杂多目标任务按依赖和交付组织STRUCTURED。四要素是内部校验结构，不是强制下游作品章节。
            简单沟通的补全服务行动、受众与语气，不补造截止日期、汇总职责或工作进展，不擅自放宽行动要求；委婉催交仍须表达请对方发送，不改成无需着急或可不提交。未知称呼可用自然中性称呼，不制造姓名填空模板。
            SUFFICIENT时直接分配原文的完整句子到四个责任段，必要时仅拆分句子，不同义改写；同一完整要求只放一个段落。已有格式、长度和限制不要在ACCEPTANCE重新抄写，未要求且无独立检查价值时省略ACCEPTANCE。背景没有额外事实时只写受众或输入对象，不盘点未提供的资料。
            对宽泛研究目标，在用户原定交付物中给出可直接开展的推荐执行路径，说明选择依据、适用条件与可调整处，必要时再列替代方法；保留原定报告、方法正文、方案或模板的类型，不把建议独立改成另一交付物。推荐明确为拟议设计，不冒充用户确认或已实施。不能只列若干候选而将方法、评分、随机化全部退回用户。区分自然使用效果与同等信息下的方法收益；保留谁提问、谁回答及答案的用途，不把用户问答改成模型自行列计划。同信息对照两组获得并可使用同一原始任务与同一份实际确认信息；直接组不追问不等于不能使用已提供答案，不能通过修改回答、换任务或额外修订轮次伪造公平。
            taskView.userMeaningAnchors把受众和研究主题按有效输入分别定位：为某人撰写不是以该人为研究对象，不从受众推定受试者。沿用明确研究主题，实际招募建议另标为建议。
            研究设计中的“比较公平”须落实到信息流：自然使用比较可以测提问获取信息的收益；同信息比较先取得并封存同一案例的实际答案，再让各组获得逐字相同的信息。不要让某组实际得到新答案而另一组没有，却仅用一句“信息相同”代替流程；委托的评分、随机化与记录流程给出拟议主方案及条件，实际授权和用户保留选择仍提问。
            """;

    /** 使用共享交付类型与确认状态；具体缺口由同次模型判断给出。 */
    public static PromptRewriteStrategy forTask(EffectiveTaskView task) {
        return new PromptRewriteStrategy("ASSESS_EXECUTION_GAPS", GAP_GUIDANCE + task.meaning().evaluationGuidance()
                + "\n本次交付画像：" + task.intent().deliveryProfile()
                + "；有效执行要求来源数：" + task.executionRequirements().size()
                + "；未决来源条数：" + task.pendingDecisions().size()
                + "。计数只说明输入构成，不代表充分性。");
    }

    /** 兼容旧调用方；没有任务视图时不在本地猜测充分性。 */
    public static PromptRewriteStrategy forPrompt(String raw) {
        return new PromptRewriteStrategy("ASSESS_EXECUTION_GAPS", GAP_GUIDANCE);
    }
}
