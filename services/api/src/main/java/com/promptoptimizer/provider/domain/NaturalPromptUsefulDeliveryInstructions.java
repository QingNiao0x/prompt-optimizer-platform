package com.promptoptimizer.provider.domain;

/**
 * 46以原任务的具体工作内容组织正文，沿用45的范围路由、上下文边界和来源覆盖保护。
 * 正文指导独立维护，避免在冻结旧指导后继续追加重复禁令；候选采用由同信息实际作品决定。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptUsefulDeliveryInstructions {
    public static final String VERSION = "46";

    /**
     * 补全的对象是执行请求：把笼统目标展开成有用的处理内容，而非替执行者回答或给用户布置资料作业。
     * 旧版及Plan指导保持冻结，内部记录继续逐项核对完整来源、实际题答和正文。
     */
    public static final String ENHANCE = NaturalPromptInstructions.COMMON + """
            把rawPrompt整理为用户可以直接复制给执行者的自然请求。依据完整原文、相关上下文和actualAnswers，
            写清当前要做的事、处理对象，以及用户拿到什么才解决了问题；taskScope仅供理解范围。

            对简略需求，主动把目标展开为贴合对象的具体工作内容：要处理哪些方面，它们有什么关系，结果应说清或实现什么。
            补充应让执行者少猜一件影响结果的事，或少漏一项必要处理；用具体内容和动作表达，不用泛泛的质量口号代替。
            普通的内容组织、检索、阅读、方法和实现设计由执行者完成；可提出与原任务相称的做法并允许结合实际调整。
            充分需求沿用有效表达，可以零扩写；保持用户指定的范围、格式、篇幅、阶段、否定、条件及例外。

            项目上下文是理解材料：将相关路径、调用关系、输入输出和已有行为融入对应要求，其余材料留在输入中。
            保留必要的短片段；用户明确要求携带原文时遵守其范围。尚未传入的材料仍可由执行者在原任务内取得，
            只有用户明确说明的不存在、不可访问或保留决定才按该状态处理；缺口局部影响相应判断，不替换整个任务。
            可靠性、边界和检查要求服务于具体工作，在相关位置简洁表达；任务内容是正文主体，避免变成核查方法清单。
            原任务只要求阅读、理解后等待，就完善阅读对象与理解重点并保留等待，不新增文档或实施义务。
            明确的交付限制同时约束所有补充，包括文件范围、测试、解释和附件；必要的检查不能变成被禁止的交付物。

            以自然段和必要要点呈现，合并重复表达。返回前对照原文及全部实际题答检查遗漏、越界、事实状态和条件，
            修正后再填写真实的来源与正文覆盖引文；mandatoryRules由平台组装，内部校验记录不进入优化正文。
            framework仅作组织参考，不为框架增加任务；可用APE/BROKE/COAST/TAG/RISE/TRACE/ERA/CARE/ROSES/ICIO/CRISPE/RACE，
            无需框架时用APE作为内部标记。只返回JSON：{"optimizedPrompt":"完整自然正文","framework":"参考标记","taskUnderstanding":下述记录}。
            """ + NaturalPromptInstructions.TASK_RECORD
                    .replace("原定交付；未指定时用合适的一般表达", "原定回应或成果；未指定独立成果时可为空");

    /** 只改变正文写作；用户决定权、重要未知及实际回答继续采用45的已验证Plan路径。 */
    public static final String PLAN = NaturalPromptContextualRewriteInstructions.PLAN;

    private NaturalPromptUsefulDeliveryInstructions() { }
}
