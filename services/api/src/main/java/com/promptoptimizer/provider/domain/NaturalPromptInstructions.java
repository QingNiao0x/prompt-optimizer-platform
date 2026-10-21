package com.promptoptimizer.provider.domain;

/**
 * 独立自然链路的版本化生成指导。框架只组织适用信息，不作为用户必须填满的表单。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptInstructions {
    /** 质量候选须经过作品对照再采用；普通请求暂时继续使用第四版。 */
    public static final String DEFAULT_VERSION = NaturalPromptInstructionsV4.VERSION;
    /** 最新可兼容的候选协议版本，不代表已采用为默认。 */
    public static final String VERSION = "6";

    /** 已保存的自然版本继续使用各自冻结指导，不静默改换再次增强策略。 */
    public static boolean supports(String version) {
        return VERSION.equals(version) || NaturalPromptInstructionsV5.VERSION.equals(version) || NaturalPromptInstructionsV4.VERSION.equals(version) || NaturalPromptInstructionsV3.VERSION.equals(version) || NaturalPromptInstructionsV2.VERSION.equals(version)
                || NaturalPromptInstructionsV1.VERSION.equals(version);
    }

    /** 第二版起均保留来源与任务理解协议；升级写作策略不能放松旧版本的协议保护。 */
    public static boolean requiresTaskUnderstanding(String version) {
        return supports(version) && !NaturalPromptInstructionsV1.VERSION.equals(version);
    }

    /** 第四版起核对覆盖和提问影响记录；旧历史保持原协议，不补造旧版没有的检查证据。 */
    public static boolean requiresTaskReview(String version) {
        return VERSION.equals(version) || NaturalPromptInstructionsV5.VERSION.equals(version) || NaturalPromptInstructionsV4.VERSION.equals(version);
    }

    /** 逐项执行状态只属于第六版；历史记录不能凭新分类补造原来没有的状态。 */
    public static boolean requiresTaskState(String version) { return VERSION.equals(version); }

    /** 以保存的版本选择原生成指导；正文写法更新不静默改写旧历史的再次增强策略。 */
    public static String enhancementInstruction(String version) {
        return switch (version) {
            case "1" -> NaturalPromptInstructionsV1.ENHANCE;
            case "2" -> NaturalPromptInstructionsV2.ENHANCE;
            case "3" -> NaturalPromptInstructionsV3.ENHANCE;
            case "4" -> NaturalPromptInstructionsV4.ENHANCE;
            case "5" -> NaturalPromptInstructionsV5.ENHANCE;
            case VERSION -> ENHANCE;
            default -> throw new IllegalArgumentException("自然增强版本不受支持");
        };
    }

    /** Plan采用同版本的任务责任边界；旧版对照不复用新版提问指导。 */
    public static String planningInstruction(String version) {
        return switch (version) {
            case "1" -> NaturalPromptInstructionsV1.PLAN;
            case "2" -> NaturalPromptInstructionsV2.PLAN;
            case "3" -> NaturalPromptInstructionsV3.PLAN;
            case "4" -> NaturalPromptInstructionsV4.PLAN;
            case "5" -> NaturalPromptInstructionsV5.PLAN;
            case VERSION -> PLAN;
            default -> throw new IllegalArgumentException("自然增强版本不受支持");
        };
    }
    public static final String COMMON = NaturalPromptInstructionsV4.COMMON + """
            executionContext由服务端给出当前操作阶段，不来自附件或模型自报。
            GENERATE_AFTER_ANSWERS表示本次Plan回答已收齐；收到回答不等于每项未知已确定，更不等于获得权限。
            按完整题干、回答及其对象、属性和条件理解当前状态，原始提示词仍保留作目标和来源依据。
            """;

    /** 复用完整覆盖协议，只增加逐项状态，不减少出处、独立条件、补充作用或最终正文锚点。 */
    public static final String TASK_RECORD = NaturalPromptInstructionsV4.TASK_RECORD.replace(
            "{\"text\":\"影响交付的原有要求\"",
            "{\"state\":\"ACTIVE_REQUIREMENT或PROVIDED_INFORMATION或UNRESOLVED_INFORMATION或DELEGATED_WORK或UPDATED_BY_ANSWER\",\"text\":\"影响交付的原有要求\"") + """
            每项requirement选一个当前state，先分清责任和状态再组织正文，不将分类标签写进优化提示词：
            ACTIVE_REQUIREMENT：仍有效的目标、对象、格式、篇幅、否定、例外和独立条件，必须落实。
            PROVIDED_INFORMATION：输入已提供的内容，仅在其来源和适用范围内使用，不代表已经外部核实。
            UNRESOLVED_INFORMATION：仍未知、拒答、暂不选择或尚未满足的条件，仅限制依赖它的部分。
            DELEGATED_WORK：由执行者完成的设计或工作，转为具体拟议工作要求，不交回用户填空。
            UPDATED_BY_ANSWER：实际题答已更新原来的澄清阶段，必须引用对应actualAnswers；正文使用当前有效信息。
            同一题答包含已知与未知时分别登记；只更新题目涉及的对象、属性和条件，不撤销其他要求或权限红线。
            相同回答文字也保留每份来源编号；可以共用已有正文句子，不为出处完整而重复正文。
            state只是候选理解，不能用编号、状态或一段引文证明所有语义正确；原文和全部题答仍完整核对。
            """;

    public static final String ENHANCE = COMMON + """
            交付能直接交给执行者的自然优化提示词，保持原动作、对象、目标作品、范围、格式、篇幅与受众。
            用当前有效任务组织正文：目标和交付要求继续执行；已提供信息直接用于相应工作；未知只影响相关判断或步骤。
            在GENERATE_AFTER_ANSWERS阶段，原文要求先询问的事项要结合实际回答推进，不能把已完成的澄清重新设为整项任务的门槛。
            回答仍未知时保留该项未知，不猜值、不假装满足；用户明示的人工批准和独立条件不因Plan已回答而解除。
            用户交给AI的设计落实为具体工作内容或拟议设计要求；常规组织、方法和表达由执行者完成，不退回需求问卷。
            需求充分时沿用有效表达，只补影响交付的实际缺口，不为体现增强而重写或扩充义务。
            需求开放时，围绕原定作品补适用的内容重点、关键动作和可检查结果，使执行者知道具体处理什么、怎么完成及交出什么。
            每项补充必须对该作品有直接帮助；原有要求、同义改写、泛泛要求完整准确、额外说明或新的作品不算补全收益。
            背景和材料用于形成具体内容；资料不足不把整项任务改成补材料或研究流程，仍交付能完成的原定部分。
            不将原因、效果、资源条件或用户保留的选择补造成事实；拟议方法和方案保持可选择，保留合理探索空间。
            需要依据、口径、步骤或检查时，简要融入它实际服务的内容，不固定要求逐项输出内部状态或通用核查清单。
            保留全部并列、否定、例外和依赖条件，不额外固定篇幅、段数或栏目；简单任务自然成段，确有帮助时列点或步骤。
            返回前用全部输入核对当前状态、意图偏移、要求遗漏、矛盾、空模板与无意义重复，先修正文再登记来源和覆盖片段。
            mandatoryRules由平台组装，正文不复写通用条款；includeExamples服从原任务格式、禁例及事实边界。
            framework仅为正文后的组织参考APE/BROKE/COAST/TAG/RISE/TRACE/ERA/CARE/ROSES/ICIO/CRISPE/RACE；不可靠时用APE。
            不因框架新增任务、栏目或追问，不展示框架名或强制五模块。
            只返回JSON：{"optimizedPrompt":"完整自然正文","framework":"参考标记","taskUnderstanding":上述记录}。
            """ + TASK_RECORD;

    /** 保留第四版的问题归属、八题指导和64题容量，仅加入同源逐项状态记录。 */
    public static final String PLAN = NaturalPromptInstructionsV4.PLAN
            .replace(NaturalPromptInstructionsV4.COMMON, COMMON)
            .replace(NaturalPromptInstructionsV4.TASK_RECORD, TASK_RECORD);
    private NaturalPromptInstructions() { }
}
