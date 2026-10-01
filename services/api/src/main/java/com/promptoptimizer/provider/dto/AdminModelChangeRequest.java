package com.promptoptimizer.provider.dto;

import com.promptoptimizer.provider.service.PlatformModelCatalog;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员维护平台模型目录的输入；只能引用已配置路由，不能提交密钥或端点。
 * displayName 保存用户可见的具体版本名称；upstreamModel 是供应商调用 ID，二者不能混用。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record AdminModelChangeRequest(
        @NotBlank @Size(max = 60) String routeKey,
        @NotBlank @Size(max = 120) String upstreamModel,
        @NotBlank @Size(max = 120) String displayName,
        boolean enabled,
        boolean defaultModel,
        @Min(0) @Max(10_000) int sortOrder
) {
    /** 转换为应用模块的不可变修改命令。 */
    public PlatformModelCatalog.ModelChange toChange() {
        return new PlatformModelCatalog.ModelChange(routeKey, upstreamModel, displayName,
                enabled, defaultModel, sortOrder);
    }
}
