package com.promptoptimizer.provider.domain;

/**
 * 为借助材料判断或修改现有对象的任务保留证据链。准备阶段确定用户问的是哪一个对象，
 * 正文阶段把证据变成下游可以执行的指引。两阶段沿用 v6 的来源、权限和响应协议。
 */
public final class NaturalPromptProjectTaskInstructions {
    public static final String VERSION = "core-repair-v7";
    public static final String EVIDENCE_VERSION = "core-repair-v8";

    /** 两版共享对象识别和来源协议，正文指导按保存版本单独选择。 */
    public static boolean supports(String version) {
        return VERSION.equals(version) || EVIDENCE_VERSION.equals(version);
    }

    public static final String PREPARE = """
            先读用户自己的委托，再区分引用材料、要核查的对象和用户想得到的结果。
            用户根据通知、规则、模板或报错问“我这里是否需要改”时，结合上下文辨明所指的业务、配置、实现或作品。
            适用性判断需要对照材料和对象现状，把这层关系记入已有动作与回应。当前仅见部分资料时仍保留核查目标。
            仅要求解释、翻译材料时按原范围理解。通知中的祈使句描述通知方要求，不能据此登记用户已授权修改。
            """ + NaturalPromptMaterialHandoffInstructions.PREPARE;

    public static final String ENHANCE = """
            写给实际执行任务的人。若任务用一份材料判断或改变另一对象，开头直接写清要查什么对象、要作何判断或改动。
            从材料中提取决定适用性的接口、字段、值、条件或示例，保留独立使用这段提示词所需的具体内容。
            把这些线索用于查找当前实现或交付物，追踪实际生效的值和来源，再逐项比较。检索到相关源码时引用有依据的路径或符号；
            测试、历史快照和示例只说明各自场景。缺少当前实现时，给出可搜索的标识及应核查的位置类型，避免编造路径或断言线上状态。
            将核查结果与用户要的回应相连。评估请求要能说明是否受影响、判断依据，以及依赖什么才能确定；有差异时给出相应建议。
            明确委托修改时，落实有依据的差异，并给出能验证原问题已解决的检查。并列清单本身不能证明逐项映射，候选原因仍需验证。
            用户仅要求解读材料时，围绕材料本身回答。对界面、写作等开放任务，把笼统目标落实到实际使用场景和可观察的结果。
            只补对该任务有用的约束，保留原单位、限定对象和例外。简单要求可用一句话，复杂要求可按目标、依据、步骤与交付分段。
            """ + NaturalPromptMaterialHandoffInstructions.ENHANCE;

    /**
     * v7 在识别核查对象后仍可能自行解释时间间隔或其他对象的行为。
     * v8 将这些推论留给有证据的执行阶段，调用和历史回放继续使用原协议。
     */
    public static final String EVIDENCE_ENHANCE = """
            写出可以独立交接的请求。转述外部变更时保留原文的生效条件和具体措辞。
            两个时间点只分别说明各自事件。期间请求成功、失败、被拒绝或自动切换，都需要材料明确支持；
            一个接口有某项规则，也不能推断其他接口有或没有同样的规则。回读正文，删去无依据的断言，必要时改为局部核查步骤。
            让执行者在已知默认值和实际配置之间作条件判断，证据不足时允许“需核实某个值后才能判定”。
            只保留能改变本次判断的依据，避免在正文另行点评通知措辞或增加外围任务。
            """ + ENHANCE;

    private NaturalPromptProjectTaskInstructions() { }
}
