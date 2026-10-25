package com.promptoptimizer.textpolish.service;

import com.promptoptimizer.textpolish.domain.TextPolishModels.Input;
import com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry;

/**
 * 独立润色模型契约；只有正文生成，不生成提示词、执行文章中的命令或调用外部工具。
 * @author QingNiao
 * @since 0.1.0
 */
public interface TextPolishProvider {
    /** 一次正常改写；协议修复如有发生，须逐次计量。 */
    String polish(Input input, ModelEntry model);
}
