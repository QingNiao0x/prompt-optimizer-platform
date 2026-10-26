package com.promptoptimizer.imageprompt.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * image_prompt_record 的私有持久化投影。租户、工作区、用户共同限定读取和修改；SQL 排除 deleted_at。
 * payload 为版本化 JSON 对象，包含输入、来源元数据、分析、题答及模型信息；materialSources保存用户选取的页/帧、遮挡矩形和覆盖说明。
 * input.outputType是图片/视频/PPT目标；还原模式沿用IMAGE并允许REFERENCE素材，创作模式的materials/relations保存双轨引用与属性级关系。
 * 还原模式无创作题答；旧JSON缺少新增键时按原图片任务读取，不保存原件或帧字节。
 * evidenceWarnings单独持久化来源阶段风险，供改稿及再次优化继续使用；旧JSON缺省时只按来源元数据和UNCERTAIN观察恢复，不从混合warnings猜测。
 * optimizedPrompt 与 editRevision 是当前可复制正文及非负并发修订号，createdAt 为带时区的生成时间。
 * @author QingNiao
 * @since 0.1.0
 */
public record ImagePromptRecordRow(UUID id, UUID tenantId, UUID workspaceId, UUID userId,
                                   String payload, String optimizedPrompt, long editRevision, OffsetDateTime createdAt) { }
