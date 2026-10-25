package com.promptoptimizer.textpolish.service;

import com.promptoptimizer.textpolish.domain.TextPolishModels.Input;
import com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 演示只回传原文，明确标记mock；不能把确定性回显宣传为真实润色效果。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
@ConditionalOnProperty(prefix = "app.provider", name = "mode", havingValue = "mock", matchIfMissing = true)
public class MockTextPolishProvider implements TextPolishProvider {
    /** 不产生外部调用、事实或改写效果。 */
    @Override public String polish(Input input, ModelEntry model) { return input.sourceText(); }
}
