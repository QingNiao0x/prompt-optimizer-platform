package com.promptoptimizer.provider.api;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.provider.application.PlatformModelCatalog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 返回管理员已发布的可选模型；响应不包含路由端点或 API Key。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/models")
public class AvailableModelController {

    private final PlatformModelCatalog catalog;

    public AvailableModelController(PlatformModelCatalog catalog) {
        this.catalog = catalog;
    }

    /** 返回当前已启用且服务端路由可用的模型。 */
    @GetMapping
    public ApiResponse<List<AvailableModel>> list(HttpServletRequest request) {
        return ApiResponse.success((String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE),
                catalog.available().stream()
                        .map(model -> new AvailableModel(model.publicId(), model.displayName(),
                                model.routeKey(), model.defaultModel()))
                        .toList());
    }
}
