package com.promptoptimizer.context.service.impl;

import com.promptoptimizer.context.domain.*;
import com.promptoptimizer.context.domain.ContextVisionModels.*;
import com.promptoptimizer.context.dto.*;
import com.promptoptimizer.context.infrastructure.vision.ContextVisionProperties;
import com.promptoptimizer.context.service.*;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.identity.support.TestActors;
import com.promptoptimizer.provider.domain.*;
import com.promptoptimizer.provider.infrastructure.concurrency.ModelConcurrencyLimiter;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 通过真实分片、PDFBox、遮挡和索引接收器验证视觉工作流，不访问外部模型或创建账户。 */
class ContextVisualDocumentWorkflowTest {
    @Test void uploadOnlyPreparesPrivateReviewThenMixedPdfFeedsAnalyzerWithoutRepeatingRecognition() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var fixture = new Fixture((model, page, bytes, tokens) -> {
            calls.incrementAndGet();
            return recognition("Scanned tail threshold is 27 units. api_key=syntheticCredentialExample123");
        })) {
            String id = fixture.upload("mixed.pdf", "pdf", mixedPdf(), true);
            var review = fixture.await(id, DocumentProcessingPhase.AWAITING_CONFIRMATION);
            assertThat(calls).hasValue(0);
            assertThat(review.visionReview().pages()).hasSize(2);
            assertThat(review.visionReview().pages().get(1).suggested()).isTrue();
            assertThat(fixture.service.previewPage(id, 2)).startsWith((byte)137, (byte)80, (byte)78, (byte)71);
            assertThat(Files.exists(fixture.source(id))).isTrue();
            fixture.service.confirmVision(id, new Confirmation("fixture", true, List.of(new PageSelection(2, List.of()))));
            var ready = fixture.await(id, DocumentProcessingPhase.READY);
            assertThat(ready.pageCoverage()).extracting(value -> value.method().name()).containsExactly("TEXT_LAYER", "VISION");
            var selection = fixture.service.retrieve(id, "threshold 27", 60000, 10).orElseThrow();
            assertThat(selection.content()).contains("NATIVE FIRST", "27", "REDACTED").doesNotContain("syntheticCredentialExample123");
            var analyzer = new DefaultContextAnalyzer(new com.fasterxml.jackson.databind.ObjectMapper(), new BinaryContentExtractor(), new FileContentSummarizer(), fixture.service);
            analyzer.analyze(new ContextAnalysisRequest("fixture", List.of(new ContextFileInput("mixed.pdf", "", "pdf", id, (long) mixedPdf().length))), "threshold");
            fixture.service.retrieve(id, "27", 60000, 10);
            assertThat(calls).hasValue(1);
            verify(fixture.permit).close();
            assertThat(ready.warnings()).anyMatch(value -> value.contains("凭据"));
        }
    }
    @Test void unselectedScannedPageRemainsPartialAndSourceIsRemovedAfterLocalOnlyChoice() throws Exception {
        try (var fixture = new Fixture((a,b,c,d) -> { throw new AssertionError("No consent"); })) {
            String id = fixture.upload("mixed.pdf", "pdf", mixedPdf(), true);
            fixture.await(id, DocumentProcessingPhase.AWAITING_CONFIRMATION);
            fixture.service.confirmVision(id, new Confirmation("", false, List.of()));
            var partial = fixture.await(id, DocumentProcessingPhase.PARTIAL);
            assertThat(partial.pageCoverage().get(1).status().name()).isEqualTo("SKIPPED");
            assertThat(fixture.service.retrieve(id, "first", 60000, 10).orElseThrow().completelyParsed()).isFalse();
            for (int i = 0; i < 100 && Files.exists(fixture.source(id)); i++) Thread.sleep(10);
            assertThat(Files.exists(fixture.source(id))).isFalse();
            assertThatThrownBy(() -> fixture.service.previewPage(id, 1)).isInstanceOf(DocumentUploadException.class);
            verifyNoInteractions(fixture.permit);
        }
    }
    @Test void masksChangeActualOutboundPixelsAndInvalidConfirmationCannotInvokeProvider() throws Exception {
        AtomicReference<byte[]> sent = new AtomicReference<>();
        try (var fixture = new Fixture((model, page, bytes, tokens) -> { sent.set(bytes); return recognition("Visible safe area"); })) {
            String id = fixture.upload("mask.png", "png", png(), true);
            fixture.await(id, DocumentProcessingPhase.AWAITING_CONFIRMATION);
            assertThatThrownBy(() -> fixture.service.confirmVision(id, new Confirmation("fixture", false, List.of(new PageSelection(1, List.of())))))
                    .isInstanceOf(DocumentUploadException.class);
            assertThatThrownBy(() -> fixture.service.confirmVision(id, new Confirmation("fixture", true, List.of(new PageSelection(1, List.of(new Mask(0, 0, Double.NaN, 1)))))))
                    .isInstanceOf(DocumentUploadException.class);
            assertThat(sent).hasValue(null);
            fixture.service.confirmVision(id, new Confirmation("fixture", true, List.of(new PageSelection(1, List.of(new Mask(0, 0, 0.5, 0.5))))));
            fixture.await(id, DocumentProcessingPhase.READY);
            var image = ImageIO.read(new ByteArrayInputStream(sent.get()));
            assertThat(image.getRGB(5,5) & 0xffffff).isZero();
            assertThat(image.getRGB(90,90) & 0xffffff).isEqualTo(0xffffff);
            assertThatThrownBy(() -> fixture.service.confirmVision(id, new Confirmation("fixture", true, List.of(new PageSelection(1, List.of())))))
                    .isInstanceOf(DocumentUploadException.class);
        }
    }
    @Test void workspaceAndTenantIsolationProtectStatusPreviewConfirmAndRetrieval() throws Exception {
        try (var fixture = new Fixture((a,b,c,d) -> recognition("safe"))) {
            String id = fixture.upload("scope.png", "png", png(), true);
            fixture.await(id, DocumentProcessingPhase.AWAITING_CONFIRMATION);
            var owner = fixture.actor.get();
            for (var other : List.of(new ActorIdentity(owner.userId(), owner.tenantId(), UUID.randomUUID(), owner.email(), owner.displayName()),
                    new ActorIdentity(owner.userId(), UUID.randomUUID(), owner.workspaceId(), owner.email(), owner.displayName()), TestActors.identity(UUID.randomUUID()))) {
                fixture.actor.set(other);
                assertThatThrownBy(() -> fixture.service.getStatus(id)).isInstanceOf(DocumentUploadException.class);
                assertThatThrownBy(() -> fixture.service.previewPage(id, 1)).isInstanceOf(DocumentUploadException.class);
                assertThatThrownBy(() -> fixture.service.confirmVision(id, new Confirmation("fixture", true, List.of(new PageSelection(1, List.of()))))).isInstanceOf(DocumentUploadException.class);
                assertThat(fixture.service.retrieve(id, "", 60000, 10)).isEmpty();
            }
            fixture.actor.set(owner); fixture.service.delete(id);
            assertThat(Files.exists(fixture.source(id))).isFalse();
        }
    }
    @Test void nativeCredentialsBlockPixelsBeforeCallAndProviderFailureKeepsNativePage() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var fixture = new Fixture((a,b,c,d) -> { calls.incrementAndGet(); throw new ProviderException(ProviderFailureType.UPSTREAM_UNAVAILABLE, "Controlled upstream failure", false); })) {
            String id = fixture.upload("mixed.pdf", "pdf", mixedPdf(), true);
            fixture.await(id, DocumentProcessingPhase.AWAITING_CONFIRMATION);
            fixture.service.confirmVision(id, new Confirmation("fixture", true, List.of(new PageSelection(2, List.of()))));
            var partial = fixture.await(id, DocumentProcessingPhase.PARTIAL);
            assertThat(partial.pageCoverage().get(1).status().name()).isEqualTo("FAILED");
            assertThat(fixture.service.retrieve(id, "first", 60000, 10).orElseThrow().content()).contains("NATIVE FIRST");
            assertThat(calls).hasValue(1);
        }
        try (var fixture = new Fixture((a,b,c,d) -> { throw new AssertionError("Sensitive native page"); })) {
            byte[] pdf = nativePdf("password=SecretCredential123");
            String id = fixture.upload("secret.pdf", "pdf", pdf, true);
            var awaiting = fixture.await(id, DocumentProcessingPhase.AWAITING_CONFIRMATION);
            assertThat(awaiting.visionReview().pages().getFirst().blocked()).isTrue();
            assertThatThrownBy(() -> fixture.service.confirmVision(id, new Confirmation("fixture", true, List.of(new PageSelection(1, List.of()))))).isInstanceOf(DocumentUploadException.class);
        }
    }

    @Test void officeImagesRequireConsentAndUseActualMasksWhileNativeTextAndFormatStayIndexed() throws Exception {
        AtomicReference<byte[]> sent = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        try (var fixture = new Fixture((model, page, bytes, tokens) -> {
            sent.set(bytes); calls.incrementAndGet(); return recognition("图中确认阈值为27");
        })) {
            String id = fixture.upload("reference.docx", "docx", OfficeContextFixtures.wordWithImage("原始正文尾部保留", png()), true);
            var waiting = fixture.await(id, DocumentProcessingPhase.AWAITING_CONFIRMATION);
            assertThat(waiting.visionReview().pages()).hasSize(1);
            assertThat(waiting.visionReview().pages().getFirst().label()).contains("内嵌图片");
            assertThat(calls).hasValue(0);
            assertThat(fixture.service.previewPage(id, 1)).startsWith((byte) 137, (byte) 80);
            assertThatThrownBy(() -> fixture.service.confirmVision(id, new Confirmation("fixture", false,
                    List.of(new PageSelection(1, List.of()))))).isInstanceOf(DocumentUploadException.class);
            fixture.service.confirmVision(id, new Confirmation("fixture", true,
                    List.of(new PageSelection(1, List.of(new Mask(0, 0, .5, .5))))));
            var ready = fixture.await(id, DocumentProcessingPhase.READY);
            var pixels = ImageIO.read(new ByteArrayInputStream(sent.get()));
            assertThat(pixels.getRGB(5, 5) & 0xffffff).isZero();
            assertThat(pixels.getRGB(90, 90) & 0xffffff).isEqualTo(0xffffff);
            assertThat(calls).hasValue(1);
            assertThat(ready.pageCoverage().getFirst().label()).contains("内嵌图片");
            assertThat(fixture.service.retrieve(id, "阈值27", 60000, 10).orElseThrow().content())
                    .contains("原始正文尾部保留", "对齐=center", "首行缩进(twip)=480", "图中确认阈值为27");
        }
    }

    @Test void unselectedAndUnsupportedOfficeImagesStayPartialAndKeepNativeContent() throws Exception {
        try (var fixture = new Fixture((a, b, c, d) -> { throw new AssertionError("No image consent"); })) {
            for (byte[] image : List.of(png(), "unsupported-vector".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                String id = fixture.upload("reference.docx", "docx", OfficeContextFixtures.wordWithImage("可读取原文", image), true);
                var waiting = fixture.await(id, DocumentProcessingPhase.AWAITING_CONFIRMATION);
                if (image.length < 30) {
                    assertThat(waiting.visionReview().pages().getFirst().blocked()).isTrue();
                    assertThatThrownBy(() -> fixture.service.confirmVision(id, new Confirmation("fixture", true,
                            List.of(new PageSelection(1, List.of()))))).isInstanceOf(DocumentUploadException.class);
                }
                fixture.service.confirmVision(id, new Confirmation("", false, List.of()));
                var partial = fixture.await(id, DocumentProcessingPhase.PARTIAL);
                assertThat(partial.pageCoverage().getFirst().status()).isEqualTo(PageStatus.SKIPPED);
                assertThat(fixture.service.retrieve(id, "原文", 60000, 10).orElseThrow().content()).contains("可读取原文");
            }
        }
    }

    @Test void officeWithoutImagesContinuesLocalExtractionWithoutEmptyConfirmation() throws Exception {
        try (var fixture = new Fixture((a, b, c, d) -> { throw new AssertionError("No image exists"); })) {
            byte[] file = OfficeContextFixtures.packageBytes(Map.of("word/document.xml", OfficeContextFixtures.xml("""
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:p><w:r><w:t>无图片文档的完整正文</w:t></w:r></w:p></w:document>
                    """)));
            String id = fixture.upload("native.docx", "docx", file, true);
            fixture.await(id, DocumentProcessingPhase.READY);
            assertThat(fixture.service.retrieve(id, "正文", 60000, 10).orElseThrow().content()).contains("无图片文档的完整正文");
            verifyNoInteractions(fixture.permit);
        }
    }

    @Test void imageOnlyWordCanCompleteAfterConsentWithoutFabricatingNativeText() throws Exception {
        try (var fixture = new Fixture((a, b, c, d) -> recognition("仅图片中的正文"))) {
            String id = fixture.upload("image-only.docx", "docx", OfficeContextFixtures.wordWithImage("", png()), true);
            fixture.await(id, DocumentProcessingPhase.AWAITING_CONFIRMATION);
            fixture.service.confirmVision(id, new Confirmation("fixture", true, List.of(new PageSelection(1, List.of()))));
            fixture.await(id, DocumentProcessingPhase.READY);
            assertThat(fixture.service.retrieve(id, "正文", 60000, 10).orElseThrow().content()).contains("仅图片中的正文");
        }
    }

    @Test void officeInspectionDoesNotMislabelTemporaryRasterizerCapacityAsUnsupportedFormat(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        var rasterizer = new ContextPageRasterizer();
        var permits = (java.util.concurrent.Semaphore) ReflectionTestUtils.getField(rasterizer, "rendering");
        permits.acquire(2);
        try {
            Path source = directory.resolve("capacity.docx");
            Files.write(source, OfficeContextFixtures.wordWithImage("原文", png()));
            var visual = new ContextVisualDocumentExtractor(rasterizer,
                    (a, b, c, d) -> { throw new AssertionError("Inspection cannot invoke a model"); },
                    mock(ContextVisionModelCatalog.class), new ContextVisionProperties(), new StreamingDocumentExtractor(new BinaryContentExtractor()));
            assertThatThrownBy(() -> visual.inspect(source, "docx"))
                    .isInstanceOfSatisfying(DocumentUploadException.class,
                            failure -> assertThat(failure.getReason()).isEqualTo(DocumentUploadException.Reason.CAPACITY_EXCEEDED));
        } finally { permits.release(2); }
    }

    @Test void maskedPdfPageNeverReintroducesItsNativeTextIntoTheIndex() throws Exception {
        try (var fixture = new Fixture((a, b, c, d) -> recognition("遮挡后可见文字"))) {
            String id = fixture.upload("masked.pdf", "pdf", nativePdf("NATIVE_MASKED_TEXT_MUST_BE_EXCLUDED"), true);
            fixture.await(id, DocumentProcessingPhase.AWAITING_CONFIRMATION);
            fixture.service.confirmVision(id, new Confirmation("fixture", true,
                    List.of(new PageSelection(1, List.of(new Mask(0, 0, 1, 1))))));
            fixture.await(id, DocumentProcessingPhase.READY);
            assertThat(fixture.service.retrieve(id, "文字", 60000, 10).orElseThrow().content())
                    .contains("遮挡后可见文字").doesNotContain("NATIVE_MASKED_TEXT_MUST_BE_EXCLUDED");
        }
    }

    @Test void spreadsheetCoordinatesMergedCellsFormulaAndTailSurviveTemporaryIndexRetrieval() throws Exception {
        try (var fixture = new Fixture((a, b, c, d) -> { throw new AssertionError("Local spreadsheet"); });
             var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = book.createSheet("年度数据");
            sheet.createRow(0).createCell(0).setCellValue("年度汇总");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 2));
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(2015);
            row.createCell(1).setCellValue(27);
            row.createCell(2).setCellFormula("B2*2");
            sheet.createRow(120).createCell(3).setCellValue("尾部特殊口径保留");
            var output = new ByteArrayOutputStream(); book.write(output);
            String id = fixture.upload("analysis.xlsx", "xlsx", output.toByteArray(), false);
            fixture.await(id, DocumentProcessingPhase.READY);
            String content = fixture.service.retrieve(id, "年度 特殊口径", 60000, 10).orElseThrow().content();
            assertThat(content).contains("A1:C1", "B2", "C2", "B2*2", "缓存", "D121", "尾部特殊口径保留");
        }
    }
    private static Recognition recognition(String text) { return new Recognition(text, List.of(), "", List.of(), true, false); }
    static byte[] png() throws IOException {
        var image = new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(Color.WHITE); graphics.fillRect(0,0,100,100); graphics.dispose();
        var output = new ByteArrayOutputStream(); ImageIO.write(image,"png",output); return output.toByteArray();
    }
    static byte[] mixedPdf() throws IOException {
        try (var document = new PDDocument()) {
            var first = new PDPage(); document.addPage(first);
            try (var stream = new PDPageContentStream(document, first)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 16); stream.newLineAtOffset(40,700); stream.showText("NATIVE FIRST: preserve report delivery."); stream.endText();
            }
            var second = new PDPage(); document.addPage(second);
            try (var stream = new PDPageContentStream(document, second)) { stream.drawImage(LosslessFactory.createFromImage(document, ImageIO.read(new ByteArrayInputStream(png()))), 0,0,500,700); }
            var output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
        }
    }
    private byte[] nativePdf(String text) throws IOException {
        try (var document = new PDDocument()) {
            var page = new PDPage(); document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 16); stream.newLineAtOffset(40,700); stream.showText(text); stream.endText();
            }
            var output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
        }
    }
    private static final class Fixture implements AutoCloseable {
        final AtomicReference<ActorIdentity> actor = new AtomicReference<>(TestActors.identity(TestActors.USER_ID));
        final ModelConcurrencyLimiter.Permit permit = mock(ModelConcurrencyLimiter.Permit.class);
        final TemporaryDocumentIndexService service;
        Fixture(ContextVisionProvider provider) {
            var models = mock(ContextVisionModelCatalog.class);
            when(models.require("fixture")).thenReturn(new Model("fixture", "Fixture", true, 1, "Controlled", false));
            var visual = new ContextVisualDocumentExtractor(new ContextPageRasterizer(), provider, models, new ContextVisionProperties(),
                    new StreamingDocumentExtractor(new BinaryContentExtractor()));
            var concurrency = mock(ModelConcurrencyLimiter.class); when(concurrency.acquireUser(actor.get().userId())).thenReturn(permit);
            var vector = new SemanticVectorIndex(inputs -> { throw new AssertionError("disabled embedding"); }, new SemanticVectorIndexOptions(false,16,48000));
            var summary = new MapReduceDocumentSummarizer(new FileContentSummarizer(), Optional.empty(), new MapReduceSummaryOptions(false,8,24,48000,1200,1800,256,32));
            service = new TemporaryDocumentIndexService(new StreamingDocumentExtractor(new BinaryContentExtractor()), vector, summary, actor::get, visual, concurrency);
        }
        String upload(String name, String language, byte[] bytes, boolean vision) {
            var created = service.create(new DocumentUploadCreateRequest(name,language,bytes.length,vision));
            for (int offset=0,index=0; offset<bytes.length; offset+=created.chunkSizeBytes(),index++)
                service.appendChunk(created.documentId(),index,Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+created.chunkSizeBytes())));
            service.completeUpload(created.documentId()); return created.documentId();
        }
        DocumentUploadStatus await(String id, DocumentProcessingPhase expected) throws InterruptedException {
            for (int i=0;i<500;i++) {
                var status=service.getStatus(id);
                if (status.phase()==expected) return status;
                if (status.phase()==DocumentProcessingPhase.FAILED) throw new AssertionError(status.errorMessage());
                Thread.sleep(10);
            }
            throw new AssertionError("Expected phase " + expected);
        }
        Path source(String id) { return ((Path)ReflectionTestUtils.getField(service,"rootDirectory")).resolve(id).resolve("source.upload"); }
        @Override public void close() { service.close(); }
    }
}
