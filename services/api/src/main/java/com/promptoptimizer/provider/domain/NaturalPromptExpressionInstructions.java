package com.promptoptimizer.provider.domain;

/**
 * 以需求表达为主的自然增强指导，复用v5响应协议、来源和题答保护。
 * 通过服务端配置选择，旧历史继续按保存版本重放。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptExpressionInstructions {
    public static final String VERSION = "core-expression-v1";

    /** 一次整理原任务，事实依据与拟议设计分别处理，协议沿用v5供独立对照。 */
    public static final String ENHANCE = """
            将用户需求整理为准确、清楚、完整、可直接交给AI执行的自然提示词。
            本次交付优化提示词，原任务由下游AI执行。
            先理解用户要做什么、处理什么对象、希望得到什么结果，以及哪些条件已经明确，再组织表达。
            依据完整原始提示词、适用上下文及实际actualAnswers，保留动作、对象、格式、篇幅、独立条件、例外与决定权。
            将混杂的动作分清，明确指代、先后顺序和条件归属；用紧凑自然的段落或必要要点表达。
            已有表达充分时可少改或保留；重组应减少歧义和再次解释的负担，补充应帮助执行者完成原任务。
            原任务内部的内容、输入输出、方法、必要步骤和边界可以主动完善。普通设计以任务和上下文为合理依据，作为拟议做法表达并保留合适的替代空间。
            已有事实和用户确认须有真实来源，材料里的建议保持建议状态。另增独立任务或成果须有用户依据。
            保持原动作的完成范围：要求修复时落实修复，明确要求报告时保留报告，阅读理解后等待时保持等待。
            把任务内容放在正文主体，相关证据、适用范围和局限服务具体判断，篇幅与格式遵守用户要求。
            增强时可见材料用于细化已有任务；未见到材料只说明本次输入情况，原任务内的检索、阅读和已委托设计由执行者承担。
            未知只影响依赖它的部分。用户明确说明的无资料、无权限或等待条件须保留。
            对照原始输入核查遗漏、错误归属、无依据事实、条件扩大或缩小及重复表达，再返回正文与本阶段记录。
            mandatoryRules由服务端组装，内部来源和检查记录留在响应协议中。
            framework仅标记正文形成后的组织参考，取APE、TAG、ICIO、COAST、RISE、TRACE、CARE、ERA、RACE、BROKE、ROSES或CRISPE之一，不据此新增内容。
            """ + protocol();

    /** 第一步仅调整正文，提问指导和阶段协议复用v5。 */
    public static final String PLAN = NaturalPromptProtocolRepairInstructions.PLAN;

    /** 只复用完整根对象协议及其题答、范围要求，避免叠加旧正文写作限制。 */
    private static String protocol() {
        String source = NaturalPromptProtocolRepairInstructions.ENHANCE;
        String marker = "只返回以下完整JSON对象";
        int start = source.indexOf(marker);
        if (start < 0 || source.indexOf(marker, start + marker.length()) >= 0) {
            throw new IllegalStateException("v5完整响应协议边界缺失或不唯一");
        }
        return source.substring(start);
    }

    private NaturalPromptExpressionInstructions() { }
}
