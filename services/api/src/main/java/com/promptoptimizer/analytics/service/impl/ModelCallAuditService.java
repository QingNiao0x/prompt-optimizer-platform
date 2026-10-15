package com.promptoptimizer.analytics.service.impl;

import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import com.promptoptimizer.analytics.infrastructure.AuditEventDelivery;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallObservation;
import com.promptoptimizer.common.logging.ModelCallLogger.TokenUsage;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * 将匿名租户级模型对账事实交给已有持久 journal 和独立数据库事务。
 * 与账号操作采集分开，不引入账号、IP、会话号或原始业务对象 ID。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class ModelCallAuditService implements ModelCallAuditContext.Sink {
    private final AuditEventDelivery delivery;

    /** 复用已有幂等恢复链路，不在模型业务事务内写入统计事实。 */
    public ModelCallAuditService(AuditEventDelivery delivery) { this.delivery = delivery; }

    /** 每次调用尝试有独立 UUID；失败或重试不撤销上游已报告的消耗。 */
    @Override
    public void record(UUID tenantId, ModelCallObservation call) {
        var details = new LinkedHashMap<String, Object>();
        details.put("schemaVersion", 1);
        details.put("requestId", call.requestId());
        details.put("workflowId", call.workflowId());
        details.put("operation", call.operation());
        details.put("providerRoute", call.providerRoute());
        details.put("model", call.model());
        details.put("attempt", call.attempt());
        details.put("mock", call.mock());
        details.put("successful", call.successful());
        details.put("failureType", call.failureType());
        details.put("validationReason", call.validationReason());
        details.put("validationField", call.validationField());
        details.put("upstreamStatus", call.upstreamStatus());
        details.put("willRetry", call.willRetry());
        details.put("transportState", call.transportState());
        details.put("durationMs", call.durationMs());
        TokenUsage usage = call.usage();
        details.put("usageState", usage == null ? "UNKNOWN" : usage.usageState());
        details.put("cacheState", usage == null ? "UNKNOWN" : usage.cacheState());
        // JSON null 表示供应商未报告；不能把缺失证据伪装成没有消耗。
        details.put("inputTokens", nonnegative(usage == null ? null : usage.inputTokens()));
        details.put("outputTokens", nonnegative(usage == null ? null : usage.outputTokens()));
        details.put("totalTokens", nonnegative(usage == null ? null : usage.totalTokens()));
        details.put("cacheHitTokens", nonnegative(usage == null ? null : usage.cacheHitTokens()));
        details.put("cacheMissTokens", nonnegative(usage == null ? null : usage.cacheMissTokens()));
        delivery.accept(new PendingAuditEvent(call.eventId(), tenantId, null,
                AnalyticsEventType.MODEL_CALL_RECORDED, details, call.startedAt()), false);
    }

    /** 保留合法数字；非法用量用状态标记，不持久化负数。 */
    private Long nonnegative(Long value) { return value != null && value >= 0 ? value : null; }
}
