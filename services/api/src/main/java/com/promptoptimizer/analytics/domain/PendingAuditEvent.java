package com.promptoptimizer.analytics.domain;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 等待可靠投递的 audit_event 事实快照，身份已由服务端确认。
 * details 仅保存采集服务白名单字段；文件 journal 不保存请求正文、凭据或认证会话号。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PendingAuditEvent(UUID id, UUID tenantId, UUID actorUserId,
                                AnalyticsEventType eventType, Map<String, Object> details,
                                OffsetDateTime occurredAt) {
    /** 保留位置字段的 JSON null、统一 UTC 时刻并复制元数据，使落盘重读与实时快照具有相同值语义。 */
    public PendingAuditEvent {
        Objects.requireNonNull(id, "event id");
        Objects.requireNonNull(tenantId, "tenant id");
        Objects.requireNonNull(actorUserId, "actor user id");
        Objects.requireNonNull(eventType, "event type");
        // 统计归属依据同一绝对时刻；不保留浏览器偏移表示，避免 JSON 重读规范化后快照比较失配。
        occurredAt = Objects.requireNonNull(occurredAt, "occurred at").withOffsetSameInstant(ZoneOffset.UTC);
        details = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(details, "details")));
    }
}
