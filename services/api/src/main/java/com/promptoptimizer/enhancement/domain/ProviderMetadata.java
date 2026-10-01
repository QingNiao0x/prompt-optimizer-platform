package com.promptoptimizer.enhancement.domain;

/**
 * 本次调用的供应商、路由 ID 与模型版本快照；展示版本来自平台目录，不推断上游内部版本。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ProviderMetadata(
        String provider,
        String model,
        boolean mock,
        String modelVersion
) {
    /** 兼容旧历史和未配置版本的调用；空版本不能用当前目录名称倒填历史。 */
    public ProviderMetadata {
        modelVersion = modelVersion == null ? "" : modelVersion.trim();
    }

    /** model 是调用标识，不能当作用户可见的具体版本。 */
    public ProviderMetadata(String provider, String model, boolean mock) {
        this(provider, model, mock, "");
    }
}
