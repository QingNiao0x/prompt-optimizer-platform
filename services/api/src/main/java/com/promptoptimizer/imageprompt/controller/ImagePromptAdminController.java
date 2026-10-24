package com.promptoptimizer.imageprompt.controller;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.security.PlatformAdminAccess;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.Model;
import com.promptoptimizer.imageprompt.dto.ImagePromptRequests.Capability;
import com.promptoptimizer.imageprompt.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 管理员在现有模型目录上配置图像能力；过滤器角色与当前数据库权限双重校验。
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/admin/image-prompt-models")
public class ImagePromptAdminController {
    private final PlatformAdminAccess access;
    private final ImagePromptModelService models;
    public ImagePromptAdminController(PlatformAdminAccess access, ImagePromptModelService models) { this.access = access; this.models = models; }
    /** 读取统一模型目录的视觉配置，包含已停用项，便于启用前先登记能力。 */
    @GetMapping
    public ApiResponse<List<Model>> list(HttpServletRequest request) { access.require(); return ApiResponse.success(requestId(request), models.adminModels()); }
    /** 同时复核当前管理员权限和能力依据，仅登记视觉输入准入。 */
    @PutMapping
    public ApiResponse<Void> configure(@RequestParam String modelId, @Valid @RequestBody Capability input, HttpServletRequest request) {
        access.require();
        if (new SensitiveValueScanner().containsCredential(input.evidence())) throw ImagePromptException.invalid("能力依据不得包含凭据。");
        models.configure(modelId, input.enabled(), input.maxImages(), input.evidence());
        return ApiResponse.success(requestId(request), null);
    }
    private String requestId(HttpServletRequest request) { return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE); }
}
