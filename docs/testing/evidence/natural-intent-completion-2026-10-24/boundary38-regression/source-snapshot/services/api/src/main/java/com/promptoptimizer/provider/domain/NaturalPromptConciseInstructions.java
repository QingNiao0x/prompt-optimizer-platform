package com.promptoptimizer.provider.domain;

/**
 * 以第四版的来源与覆盖协议为基础，按成品中的执行缺口选择少量有效补充。
 * 第十三、十四版仅收敛正文指导；Plan、任务记录、解析与安全检查继续沿用第四版。
 * 是否替换默认版本须由同条件提示词及实际作品对照决定，不按用户职业或主题分流。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptConciseInstructions {
    /** 成品反推与补充取舍候选；独立版本用于冻结对照及保留历史重放行为。 */
    public static final String VERSION = "13";

    /** 仅局部替换第四版补全段，保留其余有效内容指导，用于检验整体压缩带来的补全退化。 */
    public static final String FOCUSED_VERSION = "14";

    /** 同一改进的两次冻结候选，只由服务端版本选择，不根据输入内容选择强弱策略。 */
    public static boolean supports(String version) {
        return VERSION.equals(version) || FOCUSED_VERSION.equals(version);
    }

    public static final String ENHANCE = NaturalPromptInstructions.COMMON + """
            将原始提示词完善成自然、紧凑、可直接交给执行者的请求。首句说清动作、对象和目标作品，
            沿用原定范围、格式、篇幅、受众和条件；已有具体信息直接用于对应内容，保留有效原句。

            从执行者要交付的成品回看：现有表达会让他在哪些关键内容、必要动作或完成标准上继续猜测？
            只补直接影响本次交付的缺口，把补充写成针对当前对象的具体要求，让执行者知道要做什么、交付什么。
            用户已委托的普通设计，可提出适用的内容重点或可采用的做法，并保留按实际材料调整的空间。
            每项新增要求都应让执行者少猜一件重要的事，或让成品具体多完成一项有用内容；
            删除后不影响交付的补充省去。“完整、清晰、专业、可执行”等评价词不能替代具体内容。
            充分需求可以原样保留，additions可为空；简短需求可以依据目标作品合理设计关键内容，
            不要求用户先补齐普通执行细节。补充在用户原定交付、篇幅与资源内取舍，不扩展为额外作品。

            拟议设计保持为建议；实际事实、用户保留的取舍和授权以原输入及actualAnswers为准。
            资料不足只限制依赖它的部分，其余原定交付继续完成，不改为问卷或通用填空模板。
            比较定义与证据要求只服务确实依赖它的判断，简要放在相关内容附近。
            缺口与收益记在现有taskUnderstanding.additions，正文只写当前执行请求。

            返回前对照全部原始来源及实际回答，修正目标偏移、要求遗漏、条件矛盾、空模板和重复；
            保留所有否定、例外、并列要求与未决条件，再按最终正文填写覆盖引用。
            简单任务自然成段，独立内容需要时列点，有依赖才列步骤，不强制模块标题。
            mandatoryRules由平台统一组装，不在正文复写平台规则或内部记录。
            示例需求由原始提示词及实际题答决定，遵守禁例、事实边界与严格输出格式。
            framework仅作组织参考，从APE/BROKE/COAST/TAG/RISE/TRACE/ERA/CARE/ROSES/ICIO/CRISPE/RACE选择，
            无法可靠匹配用APE，不因框架增加字段、任务或追问，不展示框架名。
            只返回JSON：{"optimizedPrompt":"完整自然正文","framework":"参考标记","taskUnderstanding":下述记录}。
            """ + NaturalPromptInstructions.TASK_RECORD;

    public static final String FOCUSED_ENHANCE = refineFourthVersion();

    /**
     * 只替换冻结第四版的补全段，避免整体缩写把有效的内容重点和适用条件一并削弱。
     * 静态原段必须唯一存在；若基线发生变化则显式失败，不能静默退回未经对照的指导。
     */
    private static String refineFourthVersion() {
        String original = """
                信息已经充分时保留有效原句，只补影响交付的缺口。补充写成具体可执行的要求，
                不把“明确背景、任务、输出、约束”等栏目留给用户填写，也不将原任务改成分析方案或收集需求的任务。
                开放任务补充有帮助的内容重点、对象的主要特点、实际做法和预期发现；让这些内容构成正文主体。
                """;
        String replacement = """
                信息已经充分时保留有效原句，补充可以为空。需要补全时，先从目标作品和已有材料确定
                执行者还需要猜测哪些关键内容、必要动作或完成标准，再将它们写成针对当前对象的具体要求。
                已委托的普通设计给出适用的内容重点和可采用的做法，保留按材料调整的空间；
                不用“自行合理设计”“补充必要内容”等笼统转述代替补全，也不把拟议做法写成已确认事实。
                逐项取舍：这项补充让执行者少猜什么，或让原定成品具体改善什么？删除后不影响交付的省去；
                所有补充仍在原定范围、格式、篇幅和资源内完成，不增加额外作品或前置问卷。
                不把“明确背景、任务、输出、约束”等栏目留给用户填写，也不将原任务改成分析方案或收集需求的任务。
                开放任务补充有帮助的内容重点、对象的主要特点、实际做法和预期发现；让这些内容构成正文主体。
                """;
        String fourth = NaturalPromptInstructions.ENHANCE;
        int first = fourth.indexOf(original);
        if (first < 0 || first != fourth.lastIndexOf(original)) {
            throw new IllegalStateException("第四版补全指导的冻结段已变化，不能静默替换");
        }
        return fourth.replace(original, replacement);
    }

    private NaturalPromptConciseInstructions() { }
}
