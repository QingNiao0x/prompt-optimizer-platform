package com.promptoptimizer.context.service.impl;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @DateTime: 2026-08-15
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 验证 Word 文本提取和图片元数据提取，以及无效内容的安全失败。
 */
class BinaryContentExtractorTest {

    private final BinaryContentExtractor extractor = new BinaryContentExtractor();

    @Test
    void shouldExtractDocxParagraphText() throws Exception {
        XWPFDocument document = new XWPFDocument();
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.createRun().setText("这是用于提示词优化的项目说明文档。");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.write(output);
        document.close();

        BinaryContentExtractor.ExtractedText result = extractor.extract(
                "docs/说明.docx",
                "docx",
                output.toByteArray()
        );

        assertThat(result.content()).contains("提示词优化");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void shouldKeepDocumentTailWhenDocxTextExceedsLegacySnippetLimit() throws Exception {
        XWPFDocument document = new XWPFDocument();
        document.createParagraph().createRun().setText("文档开头：大型需求说明。");
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < 12_000; index++) {
            body.append("中间正文-").append(index).append('。');
        }
        document.createParagraph().createRun().setText(body.toString());
        document.createParagraph().createRun().setText("文档结尾标记：TAIL-ACCEPTANCE-2026");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.write(output);
        document.close();

        BinaryContentExtractor.ExtractedText result = extractor.extract(
                "docs/大型需求说明.docx",
                "docx",
                output.toByteArray()
        );

        assertThat(result.content())
                .contains("文档开头")
                .contains("TAIL-ACCEPTANCE-2026");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void shouldExtractPngMetadataWithoutSendingPixels() throws Exception {
        BufferedImage image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);

        BinaryContentExtractor.ExtractedText result = extractor.extract(
                "images/banner.png",
                "png",
                output.toByteArray()
        );

        assertThat(result.content()).contains("PNG");
        assertThat(result.content()).contains("120 x 80");
        assertThat(result.content()).contains("尚未进行 OCR");
    }

    @Test
    void shouldExtractPptxSlideText() throws Exception {
        XMLSlideShow presentation = new XMLSlideShow();
        XSLFSlide slide = presentation.createSlide();
        slide.createTextBox().setText("演讲稿标题：项目上下文优化");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        presentation.write(output);
        presentation.close();

        BinaryContentExtractor.ExtractedText result = extractor.extract(
                "slides/演讲稿.pptx",
                "pptx",
                output.toByteArray()
        );

        assertThat(result.content()).contains("项目上下文优化");
    }

    @Test
    void shouldExtractTextFromPdfTextLayer() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText("Academic paper abstract for prompt optimization");
                stream.endText();
            }
            document.save(output);
        }

        BinaryContentExtractor.ExtractedText result = extractor.extract(
                "papers/research.pdf",
                "pdf",
                output.toByteArray()
        );

        assertThat(result.content()).contains("Academic paper abstract");
    }

    @Test
    void shouldReadZipBasedWpsDocumentWithDocxParser() throws Exception {
        XWPFDocument document = new XWPFDocument();
        document.createParagraph().createRun().setText("WPS 兼容文档正文");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        document.write(output);
        document.close();

        BinaryContentExtractor.ExtractedText result = extractor.extract(
                "docs/report.wps",
                "wps",
                output.toByteArray()
        );

        assertThat(result.content()).contains("WPS 兼容文档正文");
    }

    @Test
    void shouldRejectInvalidBinaryContent() {
        assertThatThrownBy(() -> extractor.extract(
                "docs/broken.docx",
                "docx",
                "not-a-zip-file".getBytes(StandardCharsets.UTF_8)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectMalformedPdfWithActionableMessage() {
        assertThatThrownBy(() -> extractor.extract(
                "docs/file.pdf",
                "pdf",
                Base64.getEncoder().encode("binary".getBytes(StandardCharsets.UTF_8))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("扫描版 PDF 需要 OCR");
    }
}
