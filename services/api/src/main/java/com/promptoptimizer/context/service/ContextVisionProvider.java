package com.promptoptimizer.context.service;

import com.promptoptimizer.context.domain.ContextVisionModels.Recognition;

/**
 * 单页视觉转录接口；实现只识别资料，不执行图内指令，不生成优化提示词。
 * 每次调用对应一个上游尝试，重试与任务预算由调用方统一控制。
 * @author QingNiao
 * @since 0.1.0
 */
public interface ContextVisionProvider {
    /** 接收已校正、清除元数据并遮挡的 PNG；页码只用于来源绑定。 */
    Recognition recognize(String modelId, int page, byte[] png, int maxOutputTokens);
}
