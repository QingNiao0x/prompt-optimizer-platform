package com.promptoptimizer.provider.domain;

/**
 * 第四版单次生成内的来源状态与要求保真候选。
 * 只替换宽泛自检段，保留原补全指导、完整来源及任务记录；不追加独立审阅或修订调用。
 * 是否采用由同信息生成和作品对照决定，不因历史审阅候选失败直接视为通过。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class NaturalPromptSourceReviewInstructions {
    public static final String VERSION = "20";
    public static final String ENHANCE = refineFourthVersion();

    /**
     * 状态支持先于覆盖引用检查：引用存在不能证明实际断言受原始来源支持。
     * 仅替换唯一冻结段；基线漂移时显式失败，避免误改第四版或静默回退。
     */
    private static String refineFourthVersion() {
        String original = """
                返回前对照原始提示词、全部实际回答和适用上下文，检查目标与对象是否偏移、原要求是否遗漏或改变，
                条件是否矛盾、交付是否变成空模板，以及同一要求是否无意义重复；发现问题先修正正文，再填写最终覆盖引用。
                """;
        String replacement = """
                返回前先核对新增的用户、环境、既有情况和确认状态，是否有原始输入、适用来源或真实题答支持；
                输入缺口不能写成实际状态。无必要的无依据断言省去，任务需要时写成适用条件；
                假设、示例、拟议设计和执行者角色只在各自明确范围内生效，不提升为实际事实或执行授权。
                再按语义核对目标、对象、交付、格式、篇幅、否定、例外、独立条件和全部题答，保留委托设计与有效补充，
                不要求逐字复述委托，不把信息缺口变成新依赖。修正遗漏、改变、矛盾、空模板和无意义重复后，
                再填写既有任务记录及最终正文的覆盖引用；引用存在不能代替上述来源与语义核对。
                """;
        String fourth = NaturalPromptInstructions.ENHANCE;
        int first = fourth.indexOf(original);
        if (first < 0 || first != fourth.lastIndexOf(original)) {
            throw new IllegalStateException("第四版返回前检查的冻结段已变化，不能静默替换");
        }
        return fourth.replace(original, replacement);
    }

    private NaturalPromptSourceReviewInstructions() { }
}
