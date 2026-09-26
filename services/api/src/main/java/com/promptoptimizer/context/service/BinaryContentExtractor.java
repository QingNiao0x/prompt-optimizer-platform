package com.promptoptimizer.context.service;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.color.ColorSpace;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @DateTime: 2026-08-15
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 把前端上传的 Office、PDF、WPS/OpenDocument 和图片转换为可参与提示词上下文的文本摘要。
 */
@Component
public class BinaryContentExtractor {

    /**
     * 当前支持解析的二进制文件语言标识。
     */
    public static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "doc", "docx", "ppt", "pptx", "pdf", "xls", "xlsx", "et", "dps", "wps",
            "odt", "ods", "odp", "jpeg", "png", "gif", "webp", "bmp"
    );

    /**
     * Base64 兼容接口的提取保护上限。大型文件走分片上传和临时全文索引，不受该值限制。
     */
    private static final int MAX_EXTRACTED_CHARS = 5_000_000;

    /**
     * 判断语言标识是否属于当前支持的二进制文件。
     */
    public boolean supports(String language) {
        return language != null && SUPPORTED_LANGUAGES.contains(language.toLowerCase(Locale.ROOT));
    }

    /**
     * 解析二进制内容并返回文本摘要；解析失败时抛出 IllegalArgumentException。
     */
    public ExtractedText extract(String path, String language, byte[] content) {
        String normalizedLanguage = language.toLowerCase(Locale.ROOT);
        String text = switch (normalizedLanguage) {
            case "docx" -> extractDocx(content);
            case "doc" -> extractDoc(content);
            case "pptx" -> extractPptx(content);
            case "ppt" -> extractPpt(content);
            case "pdf" -> extractPdf(content);
            case "xls", "xlsx" -> extractSpreadsheet(content);
            case "et" -> extractWpsSpreadsheet(path, content);
            case "dps" -> extractWpsPresentation(path, content);
            case "wps" -> extractWpsDocument(path, content);
            case "odt", "ods", "odp" -> extractPackageText(path, content);
            case "jpeg", "png", "gif", "webp", "bmp" -> extractImage(normalizedLanguage, content);
            default -> throw new IllegalArgumentException("不支持的二进制文件类型：" + language);
        };
        if (text == null || text.isBlank()) {
            String message = "pdf".equals(normalizedLanguage)
                    ? "未能从 PDF 文字层提取到有效内容，扫描版 PDF 需要 OCR：" + path
                    : "未能从文件中提取到有效内容：" + path;
            throw new IllegalArgumentException(message);
        }
        boolean truncated = text.length() > MAX_EXTRACTED_CHARS;
        if (truncated) {
            text = text.substring(0, MAX_EXTRACTED_CHARS);
        }
        return new ExtractedText(text, truncated);
    }

    /**
     * 提取 .docx 的段落文本。
     */
    private String extractDocx(byte[] content) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法解析 .docx 文件", exception);
        }
    }

    /**
     * 提取旧版 .doc 的段落文本。
     */
    private String extractDoc(byte[] content) {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(content));
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法解析 .doc 文件", exception);
        }
    }

    /**
     * 提取 PowerPoint 新版幻灯片中的标题、正文和表格文字。
     */
    private String extractPptx(byte[] content) {
        try (XMLSlideShow presentation = new XMLSlideShow(new ByteArrayInputStream(content))) {
            StringBuilder result = new StringBuilder();
            int slideNumber = 1;
            for (XSLFSlide slide : presentation.getSlides()) {
                result.append("## 幻灯片 ").append(slideNumber++).append('\n');
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        appendBlock(result, textShape.getText());
                    }
                }
            }
            return result.toString();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法解析 .pptx 文件", exception);
        }
    }

    /**
     * 提取 PowerPoint 旧版幻灯片中的文本。
     */
    private String extractPpt(byte[] content) {
        try (HSLFSlideShow presentation = new HSLFSlideShow(new ByteArrayInputStream(content))) {
            StringBuilder result = new StringBuilder();
            int slideNumber = 1;
            for (HSLFSlide slide : presentation.getSlides()) {
                result.append("## 幻灯片 ").append(slideNumber++).append('\n');
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        appendBlock(result, textShape.getText());
                    }
                }
            }
            return result.toString();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法解析 .ppt 文件", exception);
        }
    }

    /**
     * 提取 PDF 的页面文本。扫描版 PDF 没有文字层时会返回空内容并提示用户使用 OCR。
     */
    private String extractPdf(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法解析 .pdf 文件，扫描版 PDF 需要 OCR", exception);
        }
    }

    /**
     * 提取 Excel 内容作为结构化纯文本，避免把工作簿二进制直接交给模型。
     */
    private String extractSpreadsheet(byte[] content) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter();
            StringBuilder result = new StringBuilder();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                result.append("## 工作表：").append(workbook.getSheetName(sheetIndex)).append('\n');
                var sheet = workbook.getSheetAt(sheetIndex);
                for (var row : sheet) {
                    boolean firstCell = true;
                    for (var cell : row) {
                        if (!firstCell) {
                            result.append('\t');
                        }
                        result.append(formatter.formatCellValue(cell));
                        firstCell = false;
                    }
                    result.append('\n');
                    if (result.length() >= MAX_EXTRACTED_CHARS) {
                        return result.toString();
                    }
                }
            }
            return result.toString();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法解析表格文件", exception);
        }
    }

    private String extractWpsDocument(String path, byte[] content) {
        try {
            return isZipPackage(content) ? extractDocx(content) : extractDoc(content);
        } catch (IllegalArgumentException exception) {
            return extractPackageText(path, content);
        }
    }

    private String extractWpsSpreadsheet(String path, byte[] content) {
        try {
            return extractSpreadsheet(content);
        } catch (IllegalArgumentException exception) {
            return extractPackageText(path, content);
        }
    }

    private String extractWpsPresentation(String path, byte[] content) {
        try {
            return isZipPackage(content) ? extractPptx(content) : extractPpt(content);
        } catch (IllegalArgumentException exception) {
            return extractPackageText(path, content);
        }
    }

    private boolean isZipPackage(byte[] content) {
        return content.length >= 4 && content[0] == 'P' && content[1] == 'K';
    }

    /**
     * 尝试读取 WPS/OpenDocument 的 ZIP/XML 包。无法识别的旧版专有二进制格式会明确失败，
     * 避免把乱码当成有效上下文。
     */
    private String extractPackageText(String path, byte[] content) {
        if (!isZipPackage(content)) {
            throw new IllegalArgumentException("无法解析 " + path + "，请将文件另存为 docx、xlsx 或 pptx");
        }
        StringBuilder result = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            int entryCount = 0;
            while ((entry = zip.getNextEntry()) != null && entryCount++ < 1_000) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (entry.isDirectory() || !(name.endsWith(".xml") || name.endsWith(".txt")
                        || name.endsWith(".html") || name.endsWith(".rels"))) {
                    continue;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                zip.transferTo(buffer);
                String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                text = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                if (!text.isBlank()) {
                    result.append("## ").append(entry.getName()).append('\n').append(text).append('\n');
                }
                if (result.length() >= MAX_EXTRACTED_CHARS) {
                    break;
                }
            }
            return result.toString();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法解析 WPS/OpenDocument 文件：" + path, exception);
        }
    }

    private void appendBlock(StringBuilder result, String text) {
        if (text != null && !text.isBlank()) {
            result.append(text.trim()).append('\n');
        }
    }

    /**
     * 提取图片格式、尺寸、颜色模型和帧数等元数据。
     */
    private String extractImage(String language, byte[] content) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (input == null) {
                throw new IllegalArgumentException("无法读取图片数据流");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("浏览器识别为图片，但服务端未找到可用的图片解码器");
            }
            ImageReader reader = readers.next();
            reader.setInput(input);
            BufferedImage image = reader.read(0);
            if (image == null) {
                throw new IllegalArgumentException("图片解码失败");
            }
            StringBuilder metadata = new StringBuilder();
            metadata.append("图片格式：")
                    .append(reader.getFormatName().toUpperCase(Locale.ROOT))
                    .append('\n');
            metadata.append("图片尺寸：").append(image.getWidth()).append(" x ").append(image.getHeight()).append('\n');
            ColorModel colorModel = image.getColorModel();
            metadata.append("颜色模型：").append(describeColorSpace(colorModel)).append('\n');
            metadata.append("像素位深：").append(colorModel.getPixelSize()).append(" bit");
            if (reader.getNumImages(true) > 1) {
                metadata.append("\n帧数：").append(reader.getNumImages(true));
            }
            metadata.append("\n说明：本次已读取图片元数据，但尚未进行 OCR 或图片语义识别；不会把图片像素发送给模型。");
            reader.dispose();
            return metadata.toString();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法解析图片：" + language, exception);
        }
    }

    /**
     * 把 Java 颜色空间类型转换为简短可读名称。
     */
    private String describeColorSpace(ColorModel colorModel) {
        int type = colorModel.getColorSpace().getType();
        return switch (type) {
            case ColorSpace.TYPE_RGB -> "RGB";
            case ColorSpace.TYPE_GRAY -> "灰度";
            case ColorSpace.TYPE_CMYK -> "CMYK";
            case ColorSpace.TYPE_YCbCr -> "YCbCr";
            default -> "色彩空间类型 " + type;
        };
    }

    /**
     * 提取结果：文本摘要和是否因预算被截断。
     */
    public record ExtractedText(String content, boolean truncated) {
    }
}
