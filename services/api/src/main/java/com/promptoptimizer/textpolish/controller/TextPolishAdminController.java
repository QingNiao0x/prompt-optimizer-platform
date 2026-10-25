package com.promptoptimizer.textpolish.controller;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.security.PlatformAdminAccess;
import com.promptoptimizer.textpolish.domain.TextPolishModels.ModelPolicy;
import com.promptoptimizer.textpolish.service.TextPolishModelService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 现有平台模型管理的润色设置入口；安全过滤器与当前数据库管理员角色双重授权。
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/admin/models/text-polish")
public class TextPolishAdminController {
    private final PlatformAdminAccess access;
    private final TextPolishModelService models;
    public TextPolishAdminController(PlatformAdminAccess access, TextPolishModelService models) { this.access = access; this.models = models; }
    /** 返回独立模块策略，无供应商密钥。 */
    @GetMapping public ApiResponse<ModelPolicy> get(HttpServletRequest r) { access.require(); return ApiResponse.success((String)r.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), models.policy()); }
    /** 原子保存启用清单和模块默认模型。 */
    @PutMapping public ApiResponse<ModelPolicy> update(@Valid @RequestBody ModelPolicy input, HttpServletRequest r) { access.require(); return ApiResponse.success((String)r.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), models.configure(input)); }
}
