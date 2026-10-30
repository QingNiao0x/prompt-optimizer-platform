package com.promptoptimizer.context.service.impl;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

/** 使用合成 Office/PDF 文件检查结构关系，不把提取出任意文字当作完整兼容。 */
class ComplexDocumentCompatibilityTest {
    @TempDir Path directory;
    private final StreamingDocumentExtractor extractor = new StreamingDocumentExtractor(new BinaryContentExtractor());

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void preservesSparseCoordinatesMergedRangesHiddenDataAndFormulaCache(String format) throws Exception {
        Path file = directory.resolve("sparse." + format);
        try (Workbook book = format.equals("xlsx") ? new XSSFWorkbook() : new HSSFWorkbook()) {
            var sheet = book.createSheet("明细");
            var head = sheet.createRow(0); head.createCell(0).setCellValue("机构"); head.createCell(2).setCellValue("金额");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            var row = sheet.createRow(2); row.setZeroHeight(true);
            row.createCell(0).setCellValue("甲院"); row.createCell(2).setCellValue(123.45);
            var formula = row.createCell(4); formula.setCellFormula("C3*2");
            book.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            var noCache = row.createCell(5); noCache.setCellFormula("C3*3");
            var hidden = book.createSheet("隐藏附表"); hidden.createRow(0).createCell(0).setCellValue("隐藏资料仍保留");
            book.setSheetHidden(1, true);
            try (var out = Files.newOutputStream(file)) { book.write(out); }
        }
        for (String text : List.of(read(file, format).text,
                new BinaryContentExtractor().extract(file.getFileName().toString(), format, Files.readAllBytes(file)).content())) {
            assertThat(text).contains("A1=机构", "C1=金额", "A3=甲院", "C3=123.45", "E3=", "C3*2", "246.9",
                    "缓存", "未重算", "隐藏行", "隐藏工作表", "A1:B1", "隐藏资料仍保留");
            assertThat(text).doesNotContain("B1=金额", "B3=123.45");
        }
        if (format.equals("xlsx")) assertThat(read(file, format).text).contains("F3==C3*3 [无缓存值]");
    }

    @Test
    void excludesDeletedRevisionsAndFieldInstructionsButKeepsCurrentTextTablesAndFootnotes() throws Exception {
        Path file = directory.resolve("revisions.docx");
        zip(file, Map.of("word/document.xml", """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:r><w:t>当前</w:t></w:r><w:r><w:t>金额</w:t></w:r>
            <w:del><w:r><w:delText>旧值999</w:delText></w:r></w:del>
            <w:ins><w:r><w:t>123.45</w:t></w:r></w:ins>
            <w:r><w:instrText>INCLUDETEXT SECRET_EXTERNAL</w:instrText></w:r><w:r><w:t>字段显示值</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>甲院</w:t></w:r></w:p></w:tc>
            <w:tc><w:p><w:r><w:t>27件</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            <w:p><w:moveFrom><w:r><w:t>迁移前旧址</w:t></w:r></w:moveFrom><w:r><w:t>现行尾部</w:t></w:r></w:p>
            </w:body></w:document>""",
            "word/footnotes.xml", "<w:footnotes xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:p><w:r><w:t>脚注依据</w:t></w:r></w:p></w:footnotes>"));
        String text = read(file, "docx").text;
        assertThat(text).contains("当前金额123.45字段显示值", "甲院", "27件", "现行尾部", "脚注依据", "行1 列1", "行1 列2");
        assertThat(text).doesNotContain("旧值999", "SECRET_EXTERNAL", "迁移前旧址");
    }

    @Test
    void readsPresentationOrderGroupsTablesAndNotes() throws Exception {
        Path file = directory.resolve("reordered.pptx");
        try (var show = new XMLSlideShow()) {
            var first = show.createSlide(); first.createTextBox().setText("原第一页现在排第二");
            var second = show.createSlide(); second.createGroup().createTextBox().setText("实际第一页分组正文");
            var table = second.createTable(2, 2);
            table.getCell(0, 0).setText("机构"); table.getCell(0, 1).setText("金额");
            table.getCell(1, 0).setText("甲院"); table.getCell(1, 1).setText("123.45");
            show.getNotesSlide(second).createTextBox().setText("演讲备注需保留");
            show.setSlideOrder(second, 0);
            try (var out = Files.newOutputStream(file)) { show.write(out); }
        }
        for (String text : List.of(read(file, "pptx").text,
                new BinaryContentExtractor().extract("reordered.pptx", "pptx", Files.readAllBytes(file)).content())) {
            assertThat(text).contains("实际第一页分组正文", "原第一页现在排第二", "甲院", "123.45", "演讲备注需保留");
            assertThat(text.indexOf("实际第一页分组正文")).isLessThan(text.indexOf("原第一页现在排第二"));
        }
    }

    @Test
    void readsLegacyPresentationGroupsAndTables() throws Exception {
        Path file = directory.resolve("legacy.ppt");
        try (var show = new org.apache.poi.hslf.usermodel.HSLFSlideShow()) {
            var slide = show.createSlide();
            slide.createGroup().createTextBox().setText("旧版分组正文");
            var table = slide.createTable(2, 2);
            table.getCell(0, 0).setText("机构"); table.getCell(0, 1).setText("金额");
            table.getCell(1, 0).setText("甲院"); table.getCell(1, 1).setText("123.45");
            try (var out = Files.newOutputStream(file)) { show.write(out); }
        }
        for (String text : List.of(read(file, "ppt").text,
                new BinaryContentExtractor().extract("legacy.ppt", "ppt", Files.readAllBytes(file)).content())) {
            assertThat(text).contains("旧版分组正文", "行2 列1", "甲院", "行2 列2", "123.45");
        }
    }

    @Test
    void keepsMixedPdfTextAndMarksUnrecognizedPagePartial() throws Exception {
        Path file = directory.resolve("mixed.pdf");
        try (var document = new PDDocument()) {
            for (int i = 0; i < 3; i++) {
                var page = new PDPage(); document.addPage(page);
                try (var out = new PDPageContentStream(document, page)) {
                    if (i == 1) { out.addRect(40, 40, 60, 60); out.fill(); }
                    else { out.beginText(); out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        out.newLineAtOffset(40, 700); out.showText(i == 0 ? "HEAD_PDF" : "TAIL_PDF"); out.endText(); }
                }
            }
            document.save(file.toFile());
        }
        var result = read(file, "pdf");
        assertThat(result.text).contains("HEAD_PDF", "TAIL_PDF");
        assertThat(result.report.complete()).isFalse();
        assertThat(result.report.warnings()).anyMatch(w -> w.contains("第 2 页"));
    }

    @Test
    void doesNotResolveExternalXmlEntities() throws Exception {
        Path secret = directory.resolve("private.txt"); Files.writeString(secret, "ENTITY_MUST_NOT_APPEAR");
        Path file = directory.resolve("entity.docx");
        zip(file, Map.of("word/document.xml", "<!DOCTYPE document [<!ENTITY ext SYSTEM '" + secret.toUri() + "'>]>"
                + "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:p><w:r><w:t>&ext;</w:t></w:r></w:p></w:document>"));
        assertThatThrownBy(() -> read(file, "docx")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsOdfAndWpsTextAndFlagsUnrecognizedImage() throws Exception {
        Path odf = directory.resolve("sample.odt");
        zip(odf, Map.of("content.xml", """
            <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
            xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0">
            <office:body><text:p>中文<text:span>保持连续</text:span>和 tail</text:p><draw:image/></office:body></office:document-content>"""));
        var result = read(odf, "odt");
        assertThat(result.text).contains("中文保持连续和 tail");
        assertThat(result.report.complete()).isFalse();
        assertThat(result.report.warnings()).anyMatch(w -> w.contains("画面内容未识别"));
        Path wps = directory.resolve("sample.wps"); Files.copy(odf, wps, StandardCopyOption.REPLACE_EXISTING);
        assertThat(read(wps, "wps").text).contains("中文保持连续和 tail");
    }

    @Test
    void rejectsMissingAndExternalSlidesInsteadOfInventingCompleteReadingOrder() throws Exception {
        String presentation = "<p:presentation xmlns:p='http://schemas.openxmlformats.org/presentationml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><p:sldIdLst><p:sldId id='256' r:id='r1'/></p:sldIdLst></p:presentation>";
        for (String target : List.of("slides/missing.xml", "https://example.invalid/remote.xml")) {
            Path file = directory.resolve(target.startsWith("http") ? "external.pptx" : "missing.pptx");
            zip(file, Map.of("ppt/presentation.xml", presentation,
                    "ppt/_rels/presentation.xml.rels", "<Relationships><Relationship Id='r1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide' Target='" + target + "' "
                            + (target.startsWith("http") ? "TargetMode='External'" : "") + "/></Relationships>",
                    "ppt/slides/slide1.xml", "<a:p xmlns:a='http://schemas.openxmlformats.org/drawingml/2006/main'><a:r><a:t>不能把未被引用的孤立页当作完整文稿</a:t></a:r></a:p>"));
            assertThatThrownBy(() -> read(file, "pptx")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"altChunk", "OLEObject", "chart", "relIds"})
    void flagsUnreadEmbeddedObjectsWithoutDiscardingSurroundingText(String object) throws Exception {
        Path file = directory.resolve("embedded.docx");
        zip(file, Map.of("word/document.xml", "<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'>"
                + "<w:p><w:r><w:t>可读取的正文</w:t></w:r></w:p><w:" + object + "/>"
                + "<w:p><w:r><w:t>仍保留的尾部</w:t></w:r></w:p></w:document>"));
        var result = read(file, "docx");
        assertThat(result.text).contains("可读取的正文", "仍保留的尾部");
        assertThat(result.report.complete()).isFalse();
        assertThat(result.report.warnings()).anyMatch(w -> w.contains("嵌入对象"));
    }

    @Test
    void rejectsEncryptedPdfWithActionableMessage() throws Exception {
        Path file = directory.resolve("encrypted.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "synthetic-owner", "synthetic-reader", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            document.save(file.toFile());
        }
        assertThatThrownBy(() -> read(file, "pdf")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("解密");
    }

    @Test
    void propagatesMixedPdfCoverageThroughUploadIndexAndRetrieval() throws Exception {
        keepsMixedPdfTextAndMarksUnrecognizedPagePartial();
        var vectors = new SemanticVectorIndex(inputs -> { throw new AssertionError("本地验收不得调用模型"); },
                new com.promptoptimizer.context.service.SemanticVectorIndexOptions(false, 16, 48_000));
        var summaries = new MapReduceDocumentSummarizer(new FileContentSummarizer(), Optional.empty(),
                new com.promptoptimizer.context.service.MapReduceSummaryOptions(false, 8, 24, 48_000, 1_200, 1_800, 256, 32));
        var service = new TemporaryDocumentIndexService(extractor, vectors, summaries,
                () -> com.promptoptimizer.identity.support.TestActors.identity(com.promptoptimizer.identity.support.TestActors.USER_ID));
        try {
            byte[] bytes = Files.readAllBytes(directory.resolve("mixed.pdf"));
            var created = service.create(new com.promptoptimizer.context.dto.DocumentUploadCreateRequest("mixed.pdf", "pdf", bytes.length));
            service.appendChunk(created.documentId(), 0, bytes); service.completeUpload(created.documentId());
            var status = service.getStatus(created.documentId());
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (!status.readyForAnalysis() && status.phase() != com.promptoptimizer.context.domain.DocumentProcessingPhase.FAILED && System.nanoTime() < deadline) {
                Thread.sleep(20); status = service.getStatus(created.documentId());
            }
            assertThat(status.phase()).isEqualTo(com.promptoptimizer.context.domain.DocumentProcessingPhase.PARTIAL);
            assertThat(status.warnings()).anyMatch(w -> w.contains("第 2 页"));
            assertThat(service.retrieve(created.documentId(), "TAIL_PDF", 10000, 10).toString()).contains("HEAD_PDF", "TAIL_PDF");
        } finally { service.close(); }
    }

    /** 合并带来源标签的流式结果供断言使用；生产解析器继续逐块消费。 */
    private Result read(Path file, String language) {
        var sections = new ArrayList<String>();
        var report = extractor.extract(file, file.getFileName().toString(), language,
                (label, text) -> sections.add(label + "\n" + text), (a, b) -> {});
        return new Result(String.join("\n", sections), report);
    }
    private record Result(String text, StreamingDocumentExtractor.ExtractionReport report) {}
    /** 构造最小 XML 反例，测试包内关系和安全边界，不依赖外部资料。 */
    private void zip(Path file, Map<String, String> entries) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
    }
}
