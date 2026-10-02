package com.promptoptimizer.analytics.dto;

import com.promptoptimizer.analytics.domain.ClientAnalyticsEventType;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 浏览器遥测请求，稳定 eventId 和原发生时刻支持离线重放。
 * expectedUserId/expectedLoginSessionId 仅用于比较当前服务端上下文，不能指定事件所有者或所在地。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record ClientAnalyticsEventRequest(
        @NotNull ClientAnalyticsEventType eventType,
        UUID eventId,
        OffsetDateTime occurredAt,
        UUID expectedUserId,
        UUID expectedLoginSessionId
) {
}
