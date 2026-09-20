package com.promptoptimizer.settings.api;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.settings.application.ProviderConfigService;
import com.promptoptimizer.settings.domain.ProviderConfigSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.UUID;

/**
 * Provider 配置管理接口，API Key 只允许写入，不提供读取明文。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@Profile("!local-mock")
@RequestMapping("/api/v1/provider-configs")
public class ProviderConfigController {

    private final ProviderConfigService providerConfigService;

    public ProviderConfigController(ProviderConfigService providerConfigService) {
        this.providerConfigService = providerConfigService;
    }

    /**
     * 查询配置摘要列表，不包含明文 API Key。
     */
    @GetMapping
    public ApiResponse<List<ProviderConfigSummary>> list(HttpServletRequest request) {
        return ApiResponse.success(requestId(request), providerConfigService.list());
    }

    /**
     * 新增 Provider 配置。
     */
    @PostMapping
    public ApiResponse<ProviderConfigSummary> create(
            @Valid @RequestBody ProviderConfigSaveRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.success(requestId(request), providerConfigService.create(body));
    }

    /**
     * 更新 Provider 配置。
     */
    @PatchMapping("/{id}")
    public ApiResponse<ProviderConfigSummary> update(
            @PathVariable UUID id,
            @Valid @RequestBody ProviderConfigUpdateRequest body,
            HttpServletRequest request
    ) {
        return ApiResponse.success(requestId(request), providerConfigService.update(id, body));
    }

    /**
     * 删除 Provider 配置及加密后的密钥。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id, HttpServletRequest request) {
        providerConfigService.delete(id);
        return ApiResponse.success(requestId(request), null);
    }

    /**
     * 从请求中读取由过滤器生成的请求标识。
     */
    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
}
