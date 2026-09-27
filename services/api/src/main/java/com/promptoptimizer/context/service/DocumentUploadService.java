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
