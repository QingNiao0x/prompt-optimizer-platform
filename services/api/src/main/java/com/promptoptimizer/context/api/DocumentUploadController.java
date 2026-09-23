package com.promptoptimizer.context.api;

import com.promptoptimizer.common.api.ApiResponse;
import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.context.application.TemporaryDocumentIndexService;
import com.promptoptimizer.context.domain.DocumentUploadStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供大型文档分片上传、异步解析进度查询和临时索引清理接口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@RestController
@RequestMapping("/api/v1/context/documents")
public class DocumentUploadController {

    private final TemporaryDocumentIndexService documentIndexService;

    public DocumentUploadController(TemporaryDocumentIndexService documentIndexService) {
        this.documentIndexService = documentIndexService;
    }

    /** 创建一次临时文档上传会话，并返回分片上传所需的标识。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DocumentUploadStatus> create(
            @Valid @RequestBody DocumentUploadCreateRequest request,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(requestId(httpRequest), documentIndexService.create(request));
    }

    /** 按分片序号写入二进制内容；服务层负责序号、大小和会话状态校验。 */
    @PutMapping(
            value = "/{documentId}/chunks/{chunkIndex}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public ApiResponse<DocumentUploadStatus> uploadChunk(
            @PathVariable String documentId,
            @PathVariable int chunkIndex,
            @RequestBody byte[] content,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(
                requestId(httpRequest),
                documentIndexService.appendChunk(documentId, chunkIndex, content)
        );
    }

    /** 标记所有分片上传完成，启动后续解析与索引流程。 */
    @PostMapping("/{documentId}/complete")
    public ApiResponse<DocumentUploadStatus> complete(
            @PathVariable String documentId,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(requestId(httpRequest), documentIndexService.completeUpload(documentId));
    }

    /** 查询文档解析状态，供前端展示异步进度及错误。 */
    @GetMapping("/{documentId}")
    public ApiResponse<DocumentUploadStatus> status(
            @PathVariable String documentId,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(requestId(httpRequest), documentIndexService.getStatus(documentId));
    }

    /** 清理指定临时文档及其索引。 */
    @DeleteMapping("/{documentId}")
    public ApiResponse<Void> delete(
            @PathVariable String documentId,
            HttpServletRequest httpRequest
    ) {
        documentIndexService.delete(documentId);
        return ApiResponse.success(requestId(httpRequest), null);
    }

    private String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
    }
}
