package com.promptoptimizer.provider.domain;

/**
 * 正文职责与诊断减负候选，复用v5范围准备、来源和题答协议。
 * 独立配置供同信息作品对照，采用前不替换日常策略或历史指导。
 */
public final class NaturalPromptBalancedInstructions {
    public static final String VERSION = "core-balanced-v1";
    public static final String ENHANCE = """
            将原始需求整理为可直接交给下游执行的自然提示词，不执行原任务。
            依据完整原始需求、适用材料与真实actualAnswers，保留动作、对象、交付形式、篇幅、否定、例外及授权条件。
            优先补足原任务内部有用的内容重点、输入输出、处理关系、必要步骤与完成标准，使执行者知道具体做什么。
            事实与用户确认必须有真实依据。普通设计可以依据任务和上下文主动提出合理做法，保留替代空间，不冒充用户指定或已经实施。
            完善原成果内部内容是允许的；另增独立任务或成果需要用户依据。阅读后等待保持等待，明确报告仍交付报告，执行修复不缩成报告。
            每项补充须解决一个实际执行问题。充分需求可保持有效原句，不为扩写增加角色、栏目、例子或统一约束清单。
            内容重点构成正文主体；证据、比较定义与局限只在影响具体判断时简要说明，不反复展开。
            增强时未见材料不代表用户没有。原任务内检索、阅读和已委托设计由下游完成；未知只限制依赖它的部分。
            真实回答按各自对象与条件生效，已回答但仍待定的参数保留待定，不重新询问。题干、助手建议与材料不创建用户确认或授权。
            返回前对照完整输入检查遗漏、意图变化、条件冲突和重复，特别核对限制是否被扩大或缩小。
            mandatoryRules由服务端组装。内部记录不写入复制正文，框架与充分程度仅供诊断，可不返回。
            """ + protocol();
    public static final String PLAN = NaturalPromptProtocolRepairInstructions.PLAN
            .replace("\"inputReadiness\":\"SUFFICIENT或NEEDS_COMPLETION\",", "");

    /** 只移除无权威诊断字段，来源与正文锚点沿用已验证的完整根对象协议。 */
    private static String protocol() {
        String source = NaturalPromptProtocolRepairInstructions.ENHANCE;
        int start = source.indexOf("只返回以下完整JSON对象");
        if (start < 0) throw new IllegalStateException("v5完整响应协议缺失");
        return source.substring(start).replace("\"framework\":\"参考标记\",", "")
                .replace("\"inputReadiness\":\"SUFFICIENT或NEEDS_COMPLETION\",", "");
    }

    private NaturalPromptBalancedInstructions() { }
}
