package com.promptoptimizer.analytics.dto;

import com.promptoptimizer.analytics.domain.ClientAnalyticsEventType;
import jakarta.validation.constraints.NotNull;

/**
 * 浏览器遥测请求，只接收白名单事件类型，不接收用户标识、IP、设备或自由文本。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ClientAnalyticsEventRequest(
        @NotNull ClientAnalyticsEventType eventType
) {
}
