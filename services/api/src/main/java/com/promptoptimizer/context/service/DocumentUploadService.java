package com.promptoptimizer.context.service;

import com.promptoptimizer.context.domain.DocumentUploadStatus;
import com.promptoptimizer.context.dto.DocumentUploadCreateRequest;

/**
 * 临时文档分片上传的应用服务边界。全文检索另由 {@link DocumentIndexLookup} 提供。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface DocumentUploadService {

    /** 共用模型管理的视觉准入目录；未装配视觉能力的旧适配器不自动开放外发。 */
    default java.util.List<com.promptoptimizer.context.domain.ContextVisionModels.Model> visionModels() { return java.util.List.of(); }

    /** 读取等待确认会话的私有 PNG 预览，不生成公网地址。 */
    default byte[] previewPage(String documentId, int page) { throw new UnsupportedOperationException("未启用视觉审阅"); }

    /** 明确确认后才启动选中页识别；空页集合表示仅继续本地解析。 */
    default DocumentUploadStatus confirmVision(String documentId, com.promptoptimizer.context.domain.ContextVisionModels.Confirmation confirmation) {
        throw new UnsupportedOperationException("未启用视觉审阅");
    }

    /** 创建一次临时文档上传会话。 */
    DocumentUploadStatus create(DocumentUploadCreateRequest request);

    /** 按分片序号写入二进制内容。 */
    DocumentUploadStatus appendChunk(String documentId, int chunkIndex, byte[] content);

    /** 标记分片上传完成并启动解析。 */
    DocumentUploadStatus completeUpload(String documentId);

    /** 查询文档解析状态。 */
    DocumentUploadStatus getStatus(String documentId);

    /** 清理指定临时文档及其索引。 */
    void delete(String documentId);
}
