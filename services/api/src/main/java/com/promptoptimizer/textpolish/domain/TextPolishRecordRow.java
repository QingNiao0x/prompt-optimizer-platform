package com.promptoptimizer.textpolish.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * text_polish_record的XML映射；租户/工作区/用户均须匹配且deleted_at为空。
 * payload是包含原文和生成原件的私有JSON对象，不能写普通日志；当前正文与修订由独立列维护。
 * sourceRecordId引用同范围来源，时间为带时区UTC时刻，纯访问器不参与授权。
 * @author QingNiao
 * @since 0.1.0
 */
public record TextPolishRecordRow(UUID id, UUID tenantId, UUID workspaceId, UUID userId, UUID sourceRecordId,
                                  String payload, String polishedText, long editRevision, OffsetDateTime createdAt) { }
