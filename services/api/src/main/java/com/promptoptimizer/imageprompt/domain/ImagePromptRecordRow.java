package com.promptoptimizer.imageprompt.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * image_prompt_record 的私有持久化投影。租户、工作区、用户共同限定读取和修改；SQL 排除 deleted_at。
 * payload 为版本化 JSON 对象，包含输入、来源元数据、分析、题答及模型信息，不保存图片字节或 URL 凭据。
 * optimizedPrompt 与 editRevision 是当前可复制正文及非负并发修订号，createdAt 为带时区的生成时间。
 * @author QingNiao
 * @since 0.1.0
 */
public record ImagePromptRecordRow(UUID id, UUID tenantId, UUID workspaceId, UUID userId,
                                   String payload, String optimizedPrompt, long editRevision, OffsetDateTime createdAt) { }
