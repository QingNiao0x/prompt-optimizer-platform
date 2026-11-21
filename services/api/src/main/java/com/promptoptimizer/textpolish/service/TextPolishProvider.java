package com.promptoptimizer.textpolish.service;

import com.promptoptimizer.textpolish.domain.TextPolishModels.Input;
import com.promptoptimizer.textpolish.domain.TextPolishModels.Generation;
import com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry;

/**
 * 独立润色模型契约；只有正文生成，不生成提示词、执行文章中的命令或调用外部工具。
 * @author QingNiao
 * @since 0.1.0
 */
public interface TextPolishProvider {
    /** 一次正常改写；协议修复如有发生，须逐次计量。 */
    String polish(Input input, ModelEntry model);
    /** 返回本次生成的执行信息；旧适配器仍可回传正文，执行信息明确未知。 */
    default Generation generate(Input input, ModelEntry model) {
        return new Generation(polish(input, model), null);
    }
    /** 当前参数下可参加定向试用的模型 ID；空值表示适配器或参数未获验证。 */
    default String directedPreviewModelId() { return null; }

    /** 使用已验证的段落初稿与一次受限追加修订。应用服务负责确认当前账号可试用。 */
    default Generation generateDirected(Input input, ModelEntry model, String openingObjective) {
        throw TextPolishException.invalid("当前模型配置不可进行首段试用。");
    }
}
