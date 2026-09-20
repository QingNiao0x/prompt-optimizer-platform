package com.promptoptimizer.context.api;

import com.promptoptimizer.common.web.RequestIdFilter;
import com.promptoptimizer.context.application.TemporaryDocumentIndexService;
import com.promptoptimizer.context.domain.DocumentProcessingPhase;
import com.promptoptimizer.context.domain.DocumentUploadStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 验证大型文档分片上传接口的请求格式、状态码和统一响应结构。
 */
@WebMvcTest(DocumentUploadController.class)
@Import({RequestIdFilter.class, com.promptoptimizer.identity.support.AuthenticatedMvcTestConfiguration.class})
class DocumentUploadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TemporaryDocumentIndexService documentIndexService;

    @Test
    void shouldCreateDocumentUploadSession() throws Exception {
        when(documentIndexService.create(any())).thenReturn(uploadStatus(DocumentProcessingPhase.UPLOADING));

        mockMvc.perform(post("/api/v1/context/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "path": "docs/大型报告.docx",
                                  "language": "docx",
                                  "sizeBytes": 10485760
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.data.documentId").value("document-123"))
                .andExpect(jsonPath("$.data.chunkSizeBytes").value(1_048_576));
    }

    @Test
    void shouldAcceptBinaryChunkWithoutJsonConversion() throws Exception {
        when(documentIndexService.appendChunk(any(), any(Integer.class), any(byte[].class)))
                .thenReturn(uploadStatus(DocumentProcessingPhase.UPLOADING));

        mockMvc.perform(put("/api/v1/context/documents/document-123/chunks/0")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[]{0x50, 0x4b, 0x03, 0x04}))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phase").value("UPLOADING"));
    }

    private DocumentUploadStatus uploadStatus(DocumentProcessingPhase phase) {
        return new DocumentUploadStatus(
                "document-123",
                "docs/大型报告.docx",
                "docx",
                phase,
                10_485_760,
                0,
                0,
                0,
                0,
                "",
                List.of(),
                "",
                Instant.parse("2026-09-12T12:00:00Z"),
                1_048_576
        );
    }
}
