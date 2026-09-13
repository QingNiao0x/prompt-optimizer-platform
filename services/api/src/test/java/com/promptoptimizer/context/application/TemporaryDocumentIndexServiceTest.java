package com.promptoptimizer.context.application;

import com.promptoptimizer.context.api.DocumentUploadCreateRequest;
import com.promptoptimizer.context.domain.DocumentProcessingPhase;
import com.promptoptimizer.context.domain.DocumentSelection;
import com.promptoptimizer.context.domain.DocumentUploadStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 验证大型文档分片上传、全文索引、尾部检索和安全路径拦截。
 */
class TemporaryDocumentIndexServiceTest {

    @Test
    void shouldRetrieveTailContentFromChunkedFullTextIndex() throws Exception {
        TemporaryDocumentIndexService service = createService();
        try {
            StringBuilder document = new StringBuilder("文档开头：大型报告。\n");
            for (int index = 0; index < 20_000; index++) {
                document.append("正文记录-").append(index).append("：常规说明。\n");
            }
            document.append("文档结尾：TAIL-ACCEPTANCE-2026 必须支持断点续传。");
            byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);

            DocumentUploadStatus created = service.create(new DocumentUploadCreateRequest(
                    "docs/大型报告.txt",
                    "text",
                    bytes.length
            ));
            uploadAllChunks(service, created.documentId(), bytes, created.chunkSizeBytes());
            service.completeUpload(created.documentId());

            DocumentUploadStatus completed = awaitTerminalStatus(service, created.documentId());
            DocumentSelection selection = service.retrieve(
                    created.documentId(),
                    "请提取 TAIL-ACCEPTANCE-2026 对应的验收要求",
                    60_000,
                    10
            ).orElseThrow();

            assertThat(completed.phase()).isEqualTo(DocumentProcessingPhase.READY);
            assertThat(completed.chunkCount()).isGreaterThan(10);
            assertThat(selection.content()).contains("TAIL-ACCEPTANCE-2026");
            assertThat(selection.totalChunks()).isEqualTo(completed.chunkCount());
            assertThat(selection.selectedChunks()).isLessThan(selection.totalChunks());
            assertThat(selection.completelyParsed()).isTrue();
        } finally {
            service.close();
        }
    }

    @Test
    void shouldUseDistributedChunksWhenGenericQueryDoesNotMatchDocumentText() throws Exception {
        TemporaryDocumentIndexService service = createService();
        try {
            StringBuilder document = new StringBuilder("常规正文".repeat(16_000));
            document.replace(18_000, 18_018, "QUARTER-MARKER-26");
            document.replace(45_000, 45_023, "THREE-QUARTER-MARKER");
            byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);

            DocumentUploadStatus created = service.create(new DocumentUploadCreateRequest(
                    "docs/大型论文.txt",
                    "text",
                    bytes.length
            ));
            uploadAllChunks(service, created.documentId(), bytes, created.chunkSizeBytes());
            service.completeUpload(created.documentId());
            awaitTerminalStatus(service, created.documentId());

            DocumentSelection selection = service.retrieve(
                    created.documentId(),
                    "请根据全文生成一份演讲稿",
                    30_000,
                    5
            ).orElseThrow();

            assertThat(selection.content())
                    .contains("QUARTER-MARKER-26")
                    .contains("THREE-QUARTER-MARKER");
            assertThat(selection.selectedChunks()).isEqualTo(5);
        } finally {
            service.close();
        }
    }

    @Test
    void shouldRejectProtectedFileBeforeCreatingUpload() {
        TemporaryDocumentIndexService service = createService();
        try {
            assertThatThrownBy(() -> service.create(new DocumentUploadCreateRequest(
                    ".env",
                    "text",
                    10
            ))).isInstanceOf(DocumentUploadException.class)
                    .hasMessageContaining("受保护文件");
        } finally {
            service.close();
        }
    }

    private TemporaryDocumentIndexService createService() {
        BinaryContentExtractor binaryExtractor = new BinaryContentExtractor();
        return new TemporaryDocumentIndexService(
                new StreamingDocumentExtractor(binaryExtractor),
                new FileContentSummarizer()
        );
    }

    private void uploadAllChunks(
            TemporaryDocumentIndexService service,
            String documentId,
            byte[] bytes,
            int chunkSize
    ) {
        int chunkIndex = 0;
        for (int offset = 0; offset < bytes.length; offset += chunkSize) {
            int length = Math.min(chunkSize, bytes.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(bytes, offset, chunk, 0, length);
            service.appendChunk(documentId, chunkIndex++, chunk);
        }
    }

    private DocumentUploadStatus awaitTerminalStatus(
            TemporaryDocumentIndexService service,
            String documentId
    ) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            DocumentUploadStatus status = service.getStatus(documentId);
            if (status.readyForAnalysis() || status.phase() == DocumentProcessingPhase.FAILED) {
                return status;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("文档索引未在测试时限内完成");
    }
}
