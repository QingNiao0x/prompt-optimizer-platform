package com.promptoptimizer.infrastructure.web;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查接口，返回服务状态和当前 Provider 模式。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final String providerMode;

    public HealthController(@Value("${app.provider.mode:unknown}") String providerMode) {
        this.providerMode = providerMode;
    }

    /**
     * 返回服务运行状态，供前端和部署探针使用。
     */
    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health(HttpServletRequest request) {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return ApiResponse.success(requestId, Map.of(
                "status", "UP",
                "service", "prompt-optimizer-api",
                "providerMode", providerMode
        ));
    }
}
