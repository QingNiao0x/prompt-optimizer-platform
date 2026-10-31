package com.promptoptimizer.context.service.impl;

import com.promptoptimizer.context.service.DocumentSummaryModel;
import com.promptoptimizer.context.service.DocumentUploadException;
import com.promptoptimizer.context.service.MapReduceSummaryOptions;
import com.promptoptimizer.context.service.SemanticVectorIndexOptions;
import com.promptoptimizer.context.service.TextEmbeddingModel;
import com.promptoptimizer.context.dto.DocumentUploadCreateRequest;
import com.promptoptimizer.context.controller.DocumentUploadController;
import com.promptoptimizer.common.exception.GlobalExceptionHandler;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.ContextFileInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import com.promptoptimizer.context.domain.DocumentProcessingPhase;
import com.promptoptimizer.context.domain.DocumentSelection;
import com.promptoptimizer.context.domain.DocumentUploadStatus;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallLogger;
import com.promptoptimizer.common.logging.ModelCallObservation;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 验证大型文档分片上传、全文索引、尾部检索和安全路径拦截。
 */
class TemporaryDocumentIndexServiceTest {

    @Test
    void completingTheSameUploadAgainDoesNotRestartExtraction() throws Exception {
        try (var cleanup = new ServiceCleanup(createService())) {
            var service = cleanup.service();
            byte[] bytes = "重复完成只能处理一次，保留全文末尾。".getBytes(StandardCharsets.UTF_8);
            var created = service.create(new DocumentUploadCreateRequest("retry.txt", "text", bytes.length));
            uploadAllChunks(service, created.documentId(), bytes, created.chunkSizeBytes());
            service.completeUpload(created.documentId());
            var ready = awaitTerminalStatus(service, created.documentId());
            var retried = service.completeUpload(created.documentId());
            assertThat(retried.phase()).isEqualTo(DocumentProcessingPhase.READY);
            assertThat(retried.chunkCount()).isEqualTo(ready.chunkCount());
            assertThat(service.retrieve(created.documentId(), "全文末尾", 1000, 2).orElseThrow().content())
                    .contains("全文末尾");
        }
    }

    @Test
    void pollingOneDocumentDoesNotWaitForAnotherExpiredSessionLock() throws Exception {
        var service = createService();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            var old = service.create(new DocumentUploadCreateRequest("expired.txt", "text", 4));
            var active = service.create(new DocumentUploadCreateRequest("active.txt", "text", 4));
            var field = TemporaryDocumentIndexService.class.getDeclaredField("sessions");
            field.setAccessible(true);
            Object expired = ((java.util.Map<?, ?>) field.get(service)).get(old.documentId());
            var expiry = expired.getClass().getDeclaredField("expiresAt");
            expiry.setAccessible(true);
            expiry.set(expired, java.time.Instant.now().minusSeconds(1));
            var monitor = expired.getClass().getDeclaredField("monitor");
            monitor.setAccessible(true);
            pool.submit(() -> {
                synchronized (monitorValue(monitor, expired)) {
                    locked.countDown();
                    try { release.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                }
            });
            assertThat(locked.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var poll = pool.submit(() -> service.getStatus(active.documentId()));
            assertThat(poll.get(500, java.util.concurrent.TimeUnit.MILLISECONDS).documentId()).isEqualTo(active.documentId());
            assertThatThrownBy(() -> service.getStatus(old.documentId())).isInstanceOf(DocumentUploadException.class);
        } finally {
            release.countDown();
            pool.shutdownNow();
            service.close();
        }
    }

    private static Object monitorValue(java.lang.reflect.Field field, Object target) {
        try { return field.get(target); } catch (IllegalAccessException failure) { throw new AssertionError(failure); }
    }

    private record ServiceCleanup(TemporaryDocumentIndexService service) implements AutoCloseable {
        @Override public void close() { service.close(); }
    }

    @Test
    void outOfOrderParallelChunksPreserveAllBytesAndDoNotDoubleCountRetries() throws Exception {
        var service = createService();
        var workers = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            byte[] content = new byte[TemporaryDocumentIndexService.UPLOAD_CHUNK_BYTES * 3 + 19];
            new java.util.Random(20261031).nextBytes(content);
            var created = service.create(new DocumentUploadCreateRequest("parallel.txt", "text", content.length));
            List<java.util.concurrent.Future<?>> writes = new ArrayList<>();
            for (int index : new int[]{3, 1, 0, 2, 1}) {
                int from = index * created.chunkSizeBytes();
                byte[] chunk = java.util.Arrays.copyOfRange(content, from, Math.min(content.length, from + created.chunkSizeBytes()));
                writes.add(workers.submit(() -> service.appendChunk(created.documentId(), index, chunk)));
            }
            for (var write : writes) write.get(5, java.util.concurrent.TimeUnit.SECONDS);
            var root = TemporaryDocumentIndexService.class.getDeclaredField("rootDirectory");
            root.setAccessible(true);
            var file = ((java.nio.file.Path) root.get(service)).resolve(created.documentId()).resolve("source.upload");
            assertThat(java.nio.file.Files.readAllBytes(file)).isEqualTo(content);
            assertThat(service.getStatus(created.documentId()).uploadedBytes()).isEqualTo(content.length);
        } finally { workers.shutdownNow(); service.close(); }
    }

    @Test
    void concurrentBrowsersCannotOversubscribeTheSourceReservation() throws Exception {
        var service = createService();
        var workers = java.util.concurrent.Executors.newFixedThreadPool(20);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 20; i++) {
                int index = i;
                tasks.add(workers.submit(() -> {
                    start.await();
                    try {
                        service.create(new DocumentUploadCreateRequest("capacity-" + index + ".txt", "text",
                                TemporaryDocumentIndexService.MAX_DOCUMENT_BYTES));
                        return true;
                    } catch (DocumentUploadException expected) { return false; }
                }));
            }
            start.countDown();
            int admitted = 0;
            for (var task : tasks) if (task.get(5, java.util.concurrent.TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(10);
        } finally { start.countDown(); workers.shutdownNow(); service.close(); }
    }

    @Test
    void asynchronousEmbeddingKeepsOnlyTrustedTenantAuditScope() throws Exception {
        UUID tenant = TestActors.identity(TestActors.USER_ID).tenantId();
        var facts = new java.util.concurrent.ConcurrentLinkedQueue<ModelCallObservation>();
        TextEmbeddingModel model = inputs -> {
            assertThat(ModelCallAuditContext.capture().tenantId()).isEqualTo(tenant);
            try (var attempt = ModelCallLogger.beginAttempt()) {
                ModelCallLogger.completed("context.embedding", "fixture", "fixture-embedding", "SERVER_CONFIGURED",
                        false, 1, inputs.size(), 1, new ModelCallLogger.TokenUsage(8L, null, 8L));
            }
            return new TextEmbeddingModel.EmbeddingBatch("fixture-embedding", inputs.stream().map(value -> new float[]{1, 0}).toList());
        };
        TemporaryDocumentIndexService service = createService(new SemanticVectorIndex(model,
                new SemanticVectorIndexOptions(true, 16, 48000)));
        try {
            byte[] bytes = "模型对账测试文档，不会发送到外部供应商。".repeat(20).getBytes(StandardCharsets.UTF_8);
            var created = service.create(new DocumentUploadCreateRequest("ledger-fixture.txt", "text", bytes.length));
            uploadAllChunks(service, created.documentId(), bytes, created.chunkSizeBytes());
            try (var scope = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(tenant, (id, call) -> {
                assertThat(id).isEqualTo(tenant);
                facts.add(call);
            }))) { service.completeUpload(created.documentId()); }
            assertThat(awaitTerminalStatus(service, created.documentId()).phase()).isEqualTo(DocumentProcessingPhase.READY);
            assertThat(facts).isNotEmpty().allSatisfy(call -> {
                assertThat(call.operation()).isEqualTo("context.embedding");
                assertThat(call.workflowId()).matches("[a-f0-9]{20}");
                assertThat(call.usage().totalTokens()).isEqualTo(8);
            });
            assertThat(ModelCallAuditContext.capture()).isNull();
        } finally { service.close(); }
    }

    @Test
    void shouldNotCalculateQueryVectorsBeforeTheDocumentIsReady() {
        SemanticVectorIndex vectors = org.mockito.Mockito.mock(SemanticVectorIndex.class);
        TemporaryDocumentIndexService service = createService(vectors);
        try {
            String id = service.create(new DocumentUploadCreateRequest("plan.txt", "text", 4)).documentId();
            assertThat(service.retrieve(id, "计划", 1000, 2)).isEmpty();
            org.mockito.Mockito.verifyNoInteractions(vectors);
        } finally {
            service.close();
        }
    }

    @Test
    void shouldRejectAnotherUserAtEveryDocumentEntryPoint() throws Exception {
        AtomicReference<UUID> actorId = new AtomicReference<>(TestActors.USER_ID);
        TemporaryDocumentIndexService service = createService(actorId);
        try (AutoCloseable cleanup = service::close) {
            byte[] bytes = "仅属用户甲的业务规则".getBytes(StandardCharsets.UTF_8);
            String documentId = service.create(new DocumentUploadCreateRequest("plan.txt", "text", bytes.length)).documentId();
            actorId.set(UUID.fromString("00000000-0000-0000-0000-000000000104"));
            assertThatThrownBy(() -> service.getStatus(documentId)).isInstanceOf(DocumentUploadException.class);
            assertThatThrownBy(() -> service.appendChunk(documentId, 0, bytes)).isInstanceOf(DocumentUploadException.class);
            assertThatThrownBy(() -> service.completeUpload(documentId)).isInstanceOf(DocumentUploadException.class);
            assertThatThrownBy(() -> service.delete(documentId)).isInstanceOf(DocumentUploadException.class);
            assertThat(service.retrieve(documentId, "业务规则", 1000, 1)).isEmpty();
            actorId.set(TestActors.USER_ID);
            assertThat(service.getStatus(documentId).documentId()).isEqualTo(documentId);
            service.delete(documentId);
        }
    }

    @Test
    void shouldReturnNotFoundAcrossUsersThroughDocumentApi() throws Exception {
        AtomicReference<UUID> actorId = new AtomicReference<>(TestActors.USER_ID);
        TemporaryDocumentIndexService service = createService(actorId);
        try (AutoCloseable cleanup = service::close) {
            String documentId = service.create(new DocumentUploadCreateRequest("plan.txt", "text", 4)).documentId();
            var mvc = MockMvcBuilders.standaloneSetup(new DocumentUploadController(service))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            actorId.set(UUID.fromString("00000000-0000-0000-0000-000000000104"));
            mvc.perform(get("/api/v1/context/documents/{id}", documentId)).andExpect(status().isNotFound());
            mvc.perform(put("/api/v1/context/documents/{id}/chunks/0", documentId)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[] {1, 2, 3, 4}))
                    .andExpect(status().isNotFound());
            mvc.perform(delete("/api/v1/context/documents/{id}", documentId)).andExpect(status().isNotFound());
            actorId.set(TestActors.USER_ID);
            assertThat(service.getStatus(documentId).documentId()).isEqualTo(documentId);
        }
    }

    @Test
    void shouldPreserveWordsAcrossStreamingReadBoundaries() throws Exception {
        TemporaryDocumentIndexService service = createService();
        try (AutoCloseable cleanup = service::close) {
            String rule = "心脑血管疾病死亡率采用Arriaga分解";
            byte[] bytes = ("常规记录".repeat(3_999) + rule + "其余材料".repeat(5_000)).getBytes(StandardCharsets.UTF_8);
            var created = service.create(new DocumentUploadCreateRequest("边界.txt", "text", bytes.length));
            uploadAllChunks(service, created.documentId(), bytes, created.chunkSizeBytes());
            service.completeUpload(created.documentId());
            awaitTerminalStatus(service, created.documentId());
            assertThat(service.retrieve(created.documentId(), rule, 60_000, 10).orElseThrow().content().contains(rule)).isTrue();
        }
    }

    @Test
    void shouldAnalyzeMixedFolderThroughRealUploadExtractionIndexAndContextAnalyzer() throws Exception {
        TemporaryDocumentIndexService service = createService();
        try (AutoCloseable cleanup = service::close) {
            List<ContextFileInput> references = new ArrayList<>();
            List<byte[]> payloads = List.of(
                    "数据说明：2015—2025年浙江心脑血管疾病死亡登记数据，使用CSV。".getBytes(StandardCharsets.UTF_8),
                    wordDocument(), pdfDocument(true));
            List<String> paths = List.of("资料/数据.txt", "资料/子目录/方案.docx", "资料/附录.pdf");
            List<String> languages = List.of("text", "docx", "pdf");
            for (int i = 0; i < payloads.size(); i++) {
                byte[] bytes = payloads.get(i);
                var created = service.create(new DocumentUploadCreateRequest(paths.get(i), languages.get(i), bytes.length));
                uploadAllChunks(service, created.documentId(), bytes, created.chunkSizeBytes());
                service.completeUpload(created.documentId());
                var completed = awaitTerminalStatus(service, created.documentId());
                assertThat(completed.phase()).isEqualTo(DocumentProcessingPhase.READY);
                assertThat(completed.warnings()).isEmpty();
                references.add(new ContextFileInput(paths.get(i), "", languages.get(i), created.documentId(), (long) bytes.length));
            }
            var analyzer = new DefaultContextAnalyzer(new ObjectMapper(), new BinaryContentExtractor(),
                    new FileContentSummarizer(), service);
            var snapshot = analyzer.analyze(new ContextAnalysisRequest("", references), "心脑血管疾病死亡率分析，采用Arriaga，附录验收标准");
            assertThat(snapshot.fileSnippets()).extracting("path").containsExactlyInAnyOrderElementsOf(paths);
            String selected = snapshot.fileSnippets().stream().map(snippet -> snippet.content()).reduce("", String::concat);
            assertThat(selected).contains("浙江", "CSV", "心脑血管疾病死亡率", "Arriaga", "ACCEPTANCE: compare annual mortality");
            assertThat(snapshot.fileCoverage()).hasSize(3).allSatisfy(coverage ->
                    assertThat(coverage.extractionStatus()).isEqualTo("COMPLETE"));
            assertThat(snapshot.warnings()).isEmpty();
        }
    }

    @Test
    void shouldReportScannedPdfWithoutTextAndIncompleteUploadsExplicitly() throws Exception {
        TemporaryDocumentIndexService service = createService();
        try (AutoCloseable cleanup = service::close) {
            byte[] bytes = pdfDocument(false);
            var created = service.create(new DocumentUploadCreateRequest("扫描.pdf", "pdf", bytes.length));
            assertThatThrownBy(() -> service.completeUpload(created.documentId())).isInstanceOf(DocumentUploadException.class);
            uploadAllChunks(service, created.documentId(), bytes, created.chunkSizeBytes());
            service.completeUpload(created.documentId());
            var result = awaitTerminalStatus(service, created.documentId());
            assertThat(result.phase()).isEqualTo(DocumentProcessingPhase.FAILED);
            assertThat(result.errorMessage()).contains("OCR");
            assertThat(service.retrieve(created.documentId(), "任意内容", 6_000, 1)).isEmpty();
        }
    }

    /** 用最小 OOXML 包验证真实 ZIP/XML 解析链路和中文跨 run 内容。 */
    private byte[] wordDocument() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body><w:p><w:r><w:t>心脑</w:t></w:r><w:r><w:t>血管疾病死亡率采用Arriaga分解。</w:t></w:r>"
                    + "</w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    /** 生成文字层 PDF 或无文字层 PDF；不依赖机器上安装的中文字体。 */
    private byte[] pdfDocument(boolean withText) throws Exception {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            if (withText) {
                try (PDPageContentStream text = new PDPageContentStream(pdf, page)) {
                    text.beginText();
                    text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    text.newLineAtOffset(40, 700);
                    text.showText("ACCEPTANCE: compare annual mortality");
                    text.endText();
                }
            }
            pdf.save(output);
            return output.toByteArray();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../report.txt", "docs/../../report.txt", "C:report.txt", "/report.txt",
            "docs\\..\\report.txt", "docs/.env", "docs/id_rsa", "config/application-prod.yml"})
    void shouldRejectUnsafeOrProtectedPaths(String path) throws Exception {
        TemporaryDocumentIndexService service = createService();
        try (AutoCloseable cleanup = service::close) {
            assertThatThrownBy(() -> service.create(new DocumentUploadCreateRequest(path, "text", 10)))
                    .isInstanceOf(DocumentUploadException.class);
        }
    }

    @Test
    void shouldMatchChineseBusinessRuleBeyondFirstFortyTermsOfAChunk() throws Exception {
        TemporaryDocumentIndexService service = createService();
        try (AutoCloseable cleanup = service::close) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 3_000; i++) text.append("unique").append(i).append(" 说明材料\n");
            text.append("退款时限必须为三个工作日。\n");
            for (int i = 3_000; i < 12_000; i++) text.append("unique").append(i).append(" 说明材料\n");
            byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
            var created = service.create(new DocumentUploadCreateRequest("方案.txt", "text", bytes.length));
            uploadAllChunks(service, created.documentId(), bytes, created.chunkSizeBytes());
            service.completeUpload(created.documentId());
            assertThat(awaitTerminalStatus(service, created.documentId()).phase()).isEqualTo(DocumentProcessingPhase.READY);
            var selection = service.retrieve(created.documentId(), "退款时限", 12_000, 2).orElseThrow();
            assertThat(selection.content().contains("退款时限必须为三个工作日")).isTrue();
        }
    }

    @Test
    void shouldRejectOneHundredMiBAtCurrentFiftyMiBLimit() throws Exception {
        TemporaryDocumentIndexService service = createService();
        try (AutoCloseable cleanup = service::close) {
            assertThatThrownBy(() -> service.create(new DocumentUploadCreateRequest("large.txt", "text", 100L * 1024 * 1024)))
                    .isInstanceOf(DocumentUploadException.class).hasMessageContaining("50 MB");
        }
    }

    @Test
    void shouldRetrieveChineseTailAtActualFiftyMiBUploadLimit() throws Exception {
        TemporaryDocumentIndexService service = createService();
        try (AutoCloseable cleanup = service::close) {
            var created = service.create(new DocumentUploadCreateRequest("large.txt", "text",
                    TemporaryDocumentIndexService.MAX_DOCUMENT_BYTES));
            byte[] chunk = new byte[created.chunkSizeBytes()];
            java.util.Arrays.fill(chunk, (byte) 'a');
            for (int i = 79; i < chunk.length; i += 80) chunk[i] = '\n';
            for (int i = 0; i < 50; i++) {
                if (i == 49) {
                    byte[] tail = "\n尾部专项验收：必须保留全文最后的业务规则。".getBytes(StandardCharsets.UTF_8);
                    System.arraycopy(tail, 0, chunk, chunk.length - tail.length, tail.length);
                }
                service.appendChunk(created.documentId(), i, chunk);
            }
            service.completeUpload(created.documentId());
            assertThat(awaitTerminalStatus(service, created.documentId()).phase()).isEqualTo(DocumentProcessingPhase.READY);
            var result = service.retrieve(created.documentId(), "尾部专项验收", 60_000, 10).orElseThrow();
            assertThat(result.content()).contains("必须保留全文最后的业务规则");
            assertThat(result.completelyParsed()).isTrue();
            assertThat(result.sourceBytes()).isEqualTo(50L * 1024 * 1024);
        }
    }

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
    void shouldRetrieveSemanticallyRelatedChunkWithoutLiteralKeywordMatch() throws Exception {
        TextEmbeddingModel embeddingModel = inputs -> new TextEmbeddingModel.EmbeddingBatch(
                "test-semantic-model",
                inputs.stream()
                        .map(value -> value.contains("车辆保养章节") || value.contains("SEMANTIC-VEHICLE-TARGET")
                                ? new float[]{1F, 0F}
                                : new float[]{0F, 1F})
                        .toList()
        );
        SemanticVectorIndex semanticIndex = new SemanticVectorIndex(
                embeddingModel,
                new SemanticVectorIndexOptions(true, 4, 24_000)
        );
        TemporaryDocumentIndexService service = createService(semanticIndex);
        try {
            StringBuilder document = new StringBuilder();
            for (int section = 0; section < 12; section++) {
                document.append("普通资料段落-").append(section).append('：')
                        .append("这是与当前任务无关的常规记录。".repeat(450))
                        .append('\n');
                if (section == 3) {
                    document.append("SEMANTIC-VEHICLE-TARGET：定期检查制动系统并更换磨损部件。\n");
                }
            }
            byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);

            DocumentUploadStatus created = service.create(new DocumentUploadCreateRequest(
                    "docs/设备说明.txt",
                    "text",
                    bytes.length
            ));
            uploadAllChunks(service, created.documentId(), bytes, created.chunkSizeBytes());
            service.completeUpload(created.documentId());
            awaitTerminalStatus(service, created.documentId());

            DocumentSelection selection = service.retrieve(
                    created.documentId(),
                    "请找到车辆保养章节",
                    24_000,
                    4
            ).orElseThrow();

            assertThat(selection.content()).contains("SEMANTIC-VEHICLE-TARGET");
            assertThat(selection.warnings()).doesNotContain("语义检索暂时不可用，本次已使用关键词检索。");
        } finally {
            service.close();
        }
    }

    @Test
    void shouldStoreMapReduceSummaryAfterIndexingCompletes() throws Exception {
        TextEmbeddingModel unusedEmbeddingModel = inputs -> {
            throw new AssertionError("语义检索关闭时不应调用向量模型");
        };
        SemanticVectorIndex semanticIndex = new SemanticVectorIndex(
                unusedEmbeddingModel,
                new SemanticVectorIndexOptions(false, 16, 48_000)
        );
        DocumentSummaryModel summaryModel = request -> new DocumentSummaryModel.SummaryResult(
                request.stage() == DocumentSummaryModel.SummaryStage.MAP
                        ? "当前批次说明业务目标和验收要求。"
                        : "全文分层摘要：文档说明业务目标、实施步骤和验收要求。",
                "test-summary-model"
        );
        MapReduceDocumentSummarizer mapReduceSummarizer = new MapReduceDocumentSummarizer(
                new FileContentSummarizer(),
                Optional.of(summaryModel),
                new MapReduceSummaryOptions(true, 2, 8, 48_000, 1_200, 1_800, 32, 8)
        );
        TemporaryDocumentIndexService service = createService(semanticIndex, mapReduceSummarizer);
        try {
            byte[] bytes = "业务说明：实现批量处理。\n验收要求：所有章节都应参与摘要。\n"
                    .repeat(2_000)
                    .getBytes(StandardCharsets.UTF_8);
            DocumentUploadStatus created = service.create(new DocumentUploadCreateRequest(
                    "docs/业务需求.txt",
                    "text",
                    bytes.length
            ));
            uploadAllChunks(service, created.documentId(), bytes, created.chunkSizeBytes());
            service.completeUpload(created.documentId());

            DocumentUploadStatus completed = awaitTerminalStatus(service, created.documentId());

            assertThat(completed.phase()).isEqualTo(DocumentProcessingPhase.READY);
            assertThat(completed.summary()).contains("全文分层摘要").contains("验收要求");
            assertThat(completed.progressPercent()).isEqualTo(100);
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
        return createService(new AtomicReference<>(TestActors.USER_ID));
    }

    private TemporaryDocumentIndexService createService(AtomicReference<UUID> actorId) {
        TextEmbeddingModel unusedModel = inputs -> {
            throw new AssertionError("语义检索关闭时不应调用向量模型");
        };
        SemanticVectorIndex semanticIndex = new SemanticVectorIndex(
                unusedModel,
                new SemanticVectorIndexOptions(false, 16, 48_000)
        );
        MapReduceDocumentSummarizer disabledSummarizer = new MapReduceDocumentSummarizer(
                new FileContentSummarizer(),
                Optional.empty(),
                new MapReduceSummaryOptions(false, 8, 24, 48_000, 1_200, 1_800, 256, 32)
        );
        return createService(semanticIndex, disabledSummarizer, actorId);
    }

    private TemporaryDocumentIndexService createService(SemanticVectorIndex semanticVectorIndex) {
        MapReduceDocumentSummarizer disabledSummarizer = new MapReduceDocumentSummarizer(
                new FileContentSummarizer(),
                Optional.empty(),
                new MapReduceSummaryOptions(false, 8, 24, 48_000, 1_200, 1_800, 256, 32)
        );
        return createService(semanticVectorIndex, disabledSummarizer);
    }

    private TemporaryDocumentIndexService createService(
            SemanticVectorIndex semanticVectorIndex,
            MapReduceDocumentSummarizer documentSummarizer
    ) {
        return createService(semanticVectorIndex, documentSummarizer, new AtomicReference<>(TestActors.USER_ID));
    }

    private TemporaryDocumentIndexService createService(
            SemanticVectorIndex semanticVectorIndex,
            MapReduceDocumentSummarizer documentSummarizer,
            AtomicReference<UUID> actorId
    ) {
        BinaryContentExtractor binaryExtractor = new BinaryContentExtractor();
        return new TemporaryDocumentIndexService(
                new StreamingDocumentExtractor(binaryExtractor),
                semanticVectorIndex,
                documentSummarizer,
                () -> TestActors.identity(actorId.get())
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
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
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
