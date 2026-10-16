package com.promptoptimizer.provider.domain;

/** 本地请求预算不足，未发送上游；只包含安全提示，不包含正文、密钥或来源内容。 */
public final class ProviderCapacityException extends ProviderException {
    /** 保留输入草稿，由用户缩小材料范围；不把本地容量不足伪装成上游502。 */
    public ProviderCapacityException() {
        super(ProviderFailureType.REQUEST_REJECTED,
                "本次需求与材料超过所选模型的安全请求预算，请减少非必要材料后重试。", false);
    }
}
