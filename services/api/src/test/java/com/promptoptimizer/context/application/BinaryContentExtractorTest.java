package com.promptoptimizer.context.application;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
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
        assertThat(result.content()).contains("只提取图片元数据");
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
    void shouldReportUnsupportedLanguage() {
        assertThatThrownBy(() -> extractor.extract(
                "docs/file.pdf",
                "pdf",
                Base64.getEncoder().encode("binary".getBytes(StandardCharsets.UTF_8))
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
