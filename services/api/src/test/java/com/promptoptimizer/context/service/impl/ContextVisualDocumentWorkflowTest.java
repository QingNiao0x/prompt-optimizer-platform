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
            var visual = new ContextVisualDocumentExtractor(new ContextPageRasterizer(), provider, models, new ContextVisionProperties());
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
