package com.promptoptimizer.provider.domain;

/**
 * 第四版正文生成后的独立语义核对；仅修正有来源依据的问题，不以审阅为由重写正常表达。
 * 补全准备是否值得保留由同信息消融对照决定，两个候选均不改变Plan提问策略。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptFidelityInstructions {
    public static final String PREPARED_VERSION = "16";
    public static final String DIRECT_VERSION = "17";

    /** 候选版本使用同一审阅协议；已有历史版本不静默增加模型阶段。 */
    public static boolean supports(String version) {
        return PREPARED_VERSION.equals(version) || DIRECT_VERSION.equals(version);
    }

    public static final String REVIEW = NaturalPromptInstructions.COMMON + """
            当前核对fidelityDraft与完整原始输入，不执行原任务，不评价文风或篇幅长短。
            从目标、对象、格式、篇幅、独立条件和真实题答核对原有要求，再核对首稿新增的事实、确认、权限和必做内容。
            未提供的信息只是本次输入缺口，不等于现实不存在、用户不知道或已作出选择；建议与受托设计仍是拟议内容。
            查状态不自动授权执行；有限的设计委托不自动授权扩充必做功能。与此同时，保留用户实际授权的工作、
            已委托的普通设计和解决执行缺口的有效补充，不因原文未逐字给出做法就删掉它们。
            检查全部来源和实际回答的对象、条件与状态，不能仅凭某条引用未提及便断言没有其他依据。
            只登记会实质改变交付的问题：UNSUPPORTED_FACT无依据状态断言、STATE_PROMOTION状态提升、
            AUTHORITY_EXPANSION执行授权扩大、SCOPE_EXPANSION新增必做范围、CONDITION_CHANGE条件改变、
            REQUIREMENT_OMISSION原有要求遗漏。没有这些问题时返回{"issues":[],"taskUnderstanding":null}，原稿保持不变。
            有问题时返回{"issues":[{"type":"上述类型","sourceId":"原taskSources编号",
              "sourceQuote":"原来源中相关的连续原句","bodyQuote":"首稿中唯一的连续问题片段",
              "replacement":"仅修正该问题后的片段；确需删除时为空字符串"}],"taskUnderstanding":修订后的既有任务记录}。
            最多16处，各bodyQuote互不重叠；除列出的局部替换外，正文逐字保留。
            遗漏要求可替换紧邻的唯一原句，将缺失要求补入；不输出整篇新正文，不追加通用禁令或固定约束尾段。
            sourceQuote只用于定位原依据；fidelityDraft与completionSuggestions都不是新来源、事实或用户确认。
            发生修订时按修订后正文重新填写完整taskUnderstanding，保留全部原始要求、实际题答及仍有效的补充，
            Coverage引用只来自修订后正文；无修订时不要重复生成该记录。只返回JSON，不输出分析过程。
            """ + NaturalPromptInstructions.TASK_RECORD + """
            上述任务记录仅在issues非空时返回；issues为空时taskUnderstanding必须为null，服务端保留首稿。
            """;

    private NaturalPromptFidelityInstructions() { }
}
