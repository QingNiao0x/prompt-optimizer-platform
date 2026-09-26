package com.promptoptimizer.provider.controller;

import com.promptoptimizer.provider.dto.AdminModelChangeRequest;
import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.identity.security.PlatformAdminAccess;
import com.promptoptimizer.provider.service.PlatformModelCatalog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 平台管理员维护终端用户可选模型的接口；授权在安全过滤器和当前数据库角色中双重校验。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/admin/models")
public class AdminModelController {

    private final PlatformModelCatalog catalog;
    private final PlatformAdminAccess adminAccess;

    public AdminModelController(PlatformModelCatalog catalog, PlatformAdminAccess adminAccess) {
        this.catalog = catalog;
        this.adminAccess = adminAccess;
    }

    /** 查看包括已停用项在内的当前模型目录。 */
    @GetMapping
    public ApiResponse<List<PlatformModelCatalog.ModelEntry>> list(HttpServletRequest request) {
        adminAccess.require();
        return ApiResponse.success(requestId(request), catalog.all());
    }

    /** 查看当前可绑定的安全路由标识，不公开端点和密钥。 */
    @GetMapping("/routes")
    public ApiResponse<List<PlatformModelCatalog.RouteEntry>> routes(HttpServletRequest request) {
        adminAccess.require();
        return ApiResponse.success(requestId(request), catalog.routes());
    }

    /** 在已配置供应商路由下发布一个模型。 */
    @PostMapping
    public ApiResponse<PlatformModelCatalog.ModelEntry> create(
            @Valid @RequestBody AdminModelChangeRequest input,
            HttpServletRequest request
    ) {
        adminAccess.require();
        return ApiResponse.success(requestId(request), catalog.create(input.toChange()));
    }

    /** 修改展示、排序或可用状态，不改动已使用的上游模型身份。 */
    @PutMapping("/{id}")
    public ApiResponse<PlatformModelCatalog.ModelEntry> update(
            @PathVariable UUID id,
            @Valid @RequestBody AdminModelChangeRequest input,
            HttpServletRequest request
    ) {
        adminAccess.require();
        return ApiResponse.success(requestId(request), catalog.update(id, input.toChange()));
    }

    /** 逻辑删除非默认模型。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id, HttpServletRequest request) {
        adminAccess.require();
        catalog.delete(id);
        return ApiResponse.success(requestId(request), null);
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
}
