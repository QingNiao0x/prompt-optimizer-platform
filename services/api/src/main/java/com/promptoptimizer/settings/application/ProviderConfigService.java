package com.promptoptimizer.settings.application;

import com.promptoptimizer.settings.api.ProviderConfigSaveRequest;
import com.promptoptimizer.settings.api.ProviderConfigUpdateRequest;
import com.promptoptimizer.settings.domain.ProviderConfigSummary;

import java.util.List;
import java.util.UUID;

/**
 * Provider 配置管理服务。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface ProviderConfigService {

    /**
     * 查询当前上下文下的配置摘要列表。
     */
    List<ProviderConfigSummary> list();

    /**
     * 新增配置并加密 API Key。
     */
    ProviderConfigSummary create(ProviderConfigSaveRequest request);

    /**
     * 更新配置。
     */
    ProviderConfigSummary update(UUID id, ProviderConfigUpdateRequest request);

    /**
     * 删除配置。
     */
    void delete(UUID id);
}
