package com.promptoptimizer.context.application;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 以段落、页面、幻灯片或工作表为单位流式提取大型文档，避免先拼接完整正文。
 */
@Component
public class StreamingDocumentExtractor {

    private static final long MAX_EXTRACTED_CHARACTERS = 100_000_000L;
    private static final long MAX_ARCHIVE_EXPANDED_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 10_000;
    private static final int TEXT_BLOCK_CHARACTERS = 16_000;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private final BinaryContentExtractor binaryContentExtractor;

    public StreamingDocumentExtractor(BinaryContentExtractor binaryContentExtractor) {
        this.binaryContentExtractor = binaryContentExtractor;
    }

    /**
     * 解析上传文件并逐段交给索引器。超过解压保护上限时返回部分完成状态，不静默伪装成全文。
     */
    public ExtractionReport extract(
            Path source,
            String logicalPath,
            String language,
            SectionConsumer consumer,
            ProgressListener progressListener
    ) {
        String normalizedLanguage = language == null ? "text" : language.toLowerCase(Locale.ROOT);
        BoundedSectionSink sink = new BoundedSectionSink(consumer);
        try {
            switch (normalizedLanguage) {
                case "docx" -> extractZipXml(
                        source,
                        name -> name.equals("word/document.xml")
                                || name.matches("word/(?:header|footer)\\d+\\.xml")
                                || name.equals("word/footnotes.xml")
                                || name.equals("word/endnotes.xml"),
                        "Word",
                        sink,
                        progressListener
                );
                case "pptx" -> extractZipXml(
                        source,
                        name -> name.matches("ppt/slides/slide\\d+\\.xml"),
                        "幻灯片",
                        sink,
                        progressListener
                );
                case "odt", "odp", "ods" -> extractZipXml(
                        source,
                        name -> name.equals("content.xml"),
                        packageLabel(normalizedLanguage),
                        sink,
                        progressListener
                );
                case "doc" -> extractLegacyWord(source, sink, progressListener);
                case "ppt" -> extractLegacyPresentation(source, sink, progressListener);
                case "xls", "xlsx" -> extractSpreadsheet(source, sink, progressListener);
                case "pdf" -> extractPdf(source, sink, progressListener);
                case "wps" -> extractWpsDocument(source, sink, progressListener);
                case "dps" -> extractWpsPresentation(source, sink, progressListener);
                case "et" -> extractWpsSpreadsheet(source, sink, progressListener);
                case "jpeg", "jpg", "png", "gif", "webp", "bmp" ->
                        extractImage(source, logicalPath, normalizedLanguage, sink, progressListener);
                default -> extractText(source, sink, progressListener);
            }
        } catch (IOException | XMLStreamException | RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("无法解析文件：" + logicalPath, exception);
        }
        if (sink.characters() == 0) {
            String message = "pdf".equals(normalizedLanguage)
                    ? "未从 PDF 文字层提取到内容，扫描版 PDF 需要 OCR"
                    : "文件中没有可提取的文本内容";
            throw new IllegalArgumentException(message);
        }
        List<String> warnings = sink.limitReached()
                ? List.of("提取内容超过 100,000,000 字符，已按安全上限停止；当前结果为部分解析。")
                : List.of();
        return new ExtractionReport(
                sink.sections(),
                sink.characters(),
                !sink.limitReached(),
                warnings
        );
    }

    private void extractText(
            Path source,
            BoundedSectionSink sink,
            ProgressListener progressListener
    ) throws IOException {
        long fileSize = Files.size(source);
        long processedCharacters = 0;
        int blockNumber = 1;
        StringBuilder block = new StringBuilder(TEXT_BLOCK_CHARACTERS);
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String line;
            while (!sink.limitReached() && (line = reader.readLine()) != null) {
                block.append(line).append('\n');
                processedCharacters += line.length() + 1L;
                if (block.length() >= TEXT_BLOCK_CHARACTERS) {
                    sink.accept("文本块 " + blockNumber++, block.toString());
                    block.setLength(0);
                    progressListener.onProgress(
                            Math.min(fileSize, processedCharacters),
                            Math.max(fileSize, 1)
                    );
                }
            }
        }
        if (!block.isEmpty() && !sink.limitReached()) {
            sink.accept("文本块 " + blockNumber, block.toString());
        }
        progressListener.onProgress(fileSize, Math.max(fileSize, 1));
    }

    private void extractPdf(
            Path source,
            BoundedSectionSink sink,
            ProgressListener progressListener
    ) throws IOException {
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            int pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= pageCount && !sink.limitReached(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                sink.accept("PDF 第 " + page + " 页", stripper.getText(document));
                progressListener.onProgress(page, Math.max(pageCount, 1));
            }
        }
    }

    private void extractSpreadsheet(
            Path source,
            BoundedSectionSink sink,
            ProgressListener progressListener
    ) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(source.toFile())) {
            DataFormatter formatter = new DataFormatter();
            int sheetCount = workbook.getNumberOfSheets();
            for (int sheetIndex = 0; sheetIndex < sheetCount && !sink.limitReached(); sheetIndex++) {
                var sheet = workbook.getSheetAt(sheetIndex);
                StringBuilder rows = new StringBuilder(TEXT_BLOCK_CHARACTERS);
                int blockNumber = 1;
                for (var row : sheet) {
                    boolean firstCell = true;
                    for (var cell : row) {
                        if (!firstCell) {
                            rows.append('\t');
                        }
                        rows.append(formatter.formatCellValue(cell));
                        firstCell = false;
                    }
                    rows.append('\n');
                    if (rows.length() >= TEXT_BLOCK_CHARACTERS) {
                        sink.accept(
                                "工作表 “" + workbook.getSheetName(sheetIndex) + "” 第 " + blockNumber++ + " 段",
                                rows.toString()
                        );
                        rows.setLength(0);
                    }
                    if (sink.limitReached()) {
                        break;
                    }
                }
                if (!rows.isEmpty() && !sink.limitReached()) {
                    sink.accept("工作表 “" + workbook.getSheetName(sheetIndex) + "” 第 " + blockNumber + " 段", rows.toString());
                }
                progressListener.onProgress(sheetIndex + 1L, Math.max(sheetCount, 1));
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("无法解析表格文件", exception);
        }
    }

    private void extractLegacyWord(
            Path source,
            BoundedSectionSink sink,
            ProgressListener progressListener
    ) throws IOException {
        try (InputStream input = Files.newInputStream(source);
             HWPFDocument document = new HWPFDocument(input);
             WordExtractor extractor = new WordExtractor(document)) {
            String[] paragraphs = extractor.getParagraphText();
            for (int index = 0; index < paragraphs.length && !sink.limitReached(); index++) {
                sink.accept("Word 段落 " + (index + 1), paragraphs[index]);
                progressListener.onProgress(index + 1L, Math.max(paragraphs.length, 1));
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("无法解析 .doc 文件", exception);
        }
    }

    private void extractLegacyPresentation(
            Path source,
            BoundedSectionSink sink,
            ProgressListener progressListener
    ) throws IOException {
        try (InputStream input = Files.newInputStream(source);
             HSLFSlideShow presentation = new HSLFSlideShow(input)) {
            List<HSLFSlide> slides = presentation.getSlides();
            for (int index = 0; index < slides.size() && !sink.limitReached(); index++) {
                StringBuilder text = new StringBuilder();
                for (HSLFShape shape : slides.get(index).getShapes()) {
                    if (shape instanceof HSLFTextShape textShape && textShape.getText() != null) {
                        text.append(textShape.getText()).append('\n');
                    }
                }
                sink.accept("幻灯片 " + (index + 1), text.toString());
                progressListener.onProgress(index + 1L, Math.max(slides.size(), 1));
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("无法解析 .ppt 文件", exception);
        }
    }

    private void extractWpsDocument(
            Path source,
            BoundedSectionSink sink,
            ProgressListener progressListener
    ) throws IOException, XMLStreamException {
        if (isZipPackage(source)) {
            extractZipXml(
                    source,
                    name -> name.equals("word/document.xml") || name.equals("content.xml"),
                    "WPS 文档",
                    sink,
                    progressListener
            );
            return;
        }
        extractLegacyWord(source, sink, progressListener);
    }

    private void extractWpsPresentation(
            Path source,
            BoundedSectionSink sink,
            ProgressListener progressListener
    ) throws IOException, XMLStreamException {
        if (isZipPackage(source)) {
            extractZipXml(
                    source,
                    name -> name.matches("ppt/slides/slide\\d+\\.xml") || name.equals("content.xml"),
                    "WPS 幻灯片",
                    sink,
                    progressListener
            );
            return;
        }
        extractLegacyPresentation(source, sink, progressListener);
    }

    private void extractWpsSpreadsheet(
            Path source,
            BoundedSectionSink sink,
            ProgressListener progressListener
    ) throws IOException, XMLStreamException {
        try {
            extractSpreadsheet(source, sink, progressListener);
        } catch (IllegalArgumentException exception) {
            if (!isZipPackage(source)) {
                throw exception;
            }
            extractZipXml(source, name -> name.equals("content.xml"), "WPS 表格", sink, progressListener);
        }
    }

    private void extractImage(
            Path source,
            String logicalPath,
            String language,
            BoundedSectionSink sink,
            ProgressListener progressListener
    ) throws IOException {
        BinaryContentExtractor.ExtractedText extracted = binaryContentExtractor.extract(
                logicalPath,
                language,
                Files.readAllBytes(source)
        );
        sink.accept("图片元数据", extracted.content());
        progressListener.onProgress(1, 1);
    }

    private void extractZipXml(
            Path source,
            Predicate<String> entryFilter,
            String labelPrefix,
            BoundedSectionSink sink,
            ProgressListener progressListener
    ) throws IOException, XMLStreamException {
        try (ZipFile zipFile = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = xmlEntries(zipFile, entryFilter);
            validateArchive(entries);
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("文档压缩包中未找到可读取的正文");
            }
            for (int index = 0; index < entries.size() && !sink.limitReached(); index++) {
                ZipEntry entry = entries.get(index);
                try (InputStream input = zipFile.getInputStream(entry)) {
                    extractXmlBlocks(input, labelPrefix + " " + (index + 1), sink);
                }
                progressListener.onProgress(index + 1L, entries.size());
            }
        }
    }

    private List<? extends ZipEntry> xmlEntries(
            ZipFile zipFile,
            Predicate<String> entryFilter
    ) {
        List<ZipEntry> entries = new ArrayList<>();
        Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
        while (enumeration.hasMoreElements()) {
            ZipEntry entry = enumeration.nextElement();
            String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (!entry.isDirectory() && entryFilter.test(name)) {
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparingInt(entry -> numericOrder(entry.getName())));
        return entries;
    }

    private void validateArchive(List<? extends ZipEntry> entries) {
        if (entries.size() > MAX_ARCHIVE_ENTRIES) {
            throw new IllegalArgumentException("文档内部文件数量超过安全上限");
        }
        long expandedBytes = 0;
        for (ZipEntry entry : entries) {
            long size = Math.max(0, entry.getSize());
            expandedBytes += size;
            if (expandedBytes > MAX_ARCHIVE_EXPANDED_BYTES) {
                throw new IllegalArgumentException("文档解压后的正文超过 256 MB 安全上限");
            }
        }
    }

    private void extractXmlBlocks(
            InputStream input,
            String label,
            BoundedSectionSink sink
    ) throws XMLStreamException, IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        XMLStreamReader reader = factory.createXMLStreamReader(input, StandardCharsets.UTF_8.name());
        StringBuilder block = new StringBuilder();
        int blockNumber = 1;
        try {
            while (reader.hasNext() && !sink.limitReached()) {
                int event = reader.next();
                if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    String text = reader.getText();
                    if (text != null && !text.isBlank()) {
                        block.append(text.strip()).append(' ');
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String localName = reader.getLocalName();
                    if (("p".equals(localName) || "tr".equals(localName) || "h".equals(localName))
                            && !block.isEmpty()) {
                        sink.accept(label + " · 段 " + blockNumber++, block.toString());
                        block.setLength(0);
                    }
                }
            }
            if (!block.isEmpty() && !sink.limitReached()) {
                sink.accept(label + " · 段 " + blockNumber, block.toString());
            }
        } finally {
            reader.close();
        }
    }

    private boolean isZipPackage(Path source) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            return input.read() == 'P' && input.read() == 'K';
        }
    }

    private int numericOrder(String name) {
        Matcher matcher = NUMBER_PATTERN.matcher(name);
        int result = 0;
        while (matcher.find()) {
            try {
                result = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return Integer.MAX_VALUE;
            }
        }
        return result;
    }

    private String packageLabel(String language) {
        return switch (language) {
            case "odp" -> "演示文稿";
            case "ods" -> "工作表";
            default -> "文档";
        };
    }

    /**
     * 文档段落接收器。实现方应立即消费内容，不能把全部正文长期保存在内存中。
     */
    @FunctionalInterface
    public interface SectionConsumer {
        void accept(String label, String content) throws IOException;
    }

    /**
     * 提取进度回调。processed 与 total 使用当前格式最可靠的页、工作表或字节单位。
     */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long processed, long total);
    }

    public record ExtractionReport(
            int sectionCount,
            long extractedCharacters,
            boolean complete,
            List<String> warnings
    ) {
        public ExtractionReport {
            warnings = List.copyOf(warnings);
        }
    }

    private static final class BoundedSectionSink {

        private final SectionConsumer consumer;
        private long characters;
        private int sections;
        private boolean limitReached;

        private BoundedSectionSink(SectionConsumer consumer) {
            this.consumer = consumer;
        }

        private void accept(String label, String content) throws IOException {
            if (content == null || content.isBlank() || limitReached) {
                return;
            }
            long remaining = MAX_EXTRACTED_CHARACTERS - characters;
            if (remaining <= 0) {
                limitReached = true;
                return;
            }
            String normalized = content.strip();
            String accepted = normalized.length() > remaining
                    ? normalized.substring(0, Math.toIntExact(remaining))
                    : normalized;
            consumer.accept(label, accepted);
            characters += accepted.length();
            sections++;
            if (accepted.length() < normalized.length()) {
                limitReached = true;
            }
        }

        private long characters() {
            return characters;
        }

        private int sections() {
            return sections;
        }

        private boolean limitReached() {
            return limitReached;
        }
    }
}
