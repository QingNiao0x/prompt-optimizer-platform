package com.promptoptimizer.textpolish.service;

import java.util.List;
import com.promptoptimizer.textpolish.domain.TextPolishModels.Resource;
import com.promptoptimizer.textpolish.domain.TextPolishModels.Selection;

/**
 * 单次编辑的不可变装配结果；在首次HTTP请求前冻结，协议修复沿用同一实例。
 * 选择描述实际加载的资源，不表示模型已正确执行编辑要求。
 * @author QingNiao
 * @since 0.1.0
 */
public record PreparedTextPolish(String instruction, Selection selection, List<Resource> resources) {
    /** 复制清单，防止装配后调用方修改资源快照。 */
    public PreparedTextPolish {
        resources = List.copyOf(resources);
    }

}
