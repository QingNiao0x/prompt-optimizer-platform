package com.promptoptimizer.context.application;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.color.ColorSpace;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * @DateTime: 2026-08-15
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 把前端上传的 Word 文档和图片字节转换为可参与提示词上下文的文本摘要。
 */
@Component
public class BinaryContentExtractor {

    /**
     * 当前支持解析的二进制文件语言标识。
     */
    public static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "doc", "docx", "jpeg", "png", "gif", "webp", "bmp"
    );

    private static final int MAX_EXTRACTED_CHARS = 60_000;

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
            case "jpeg", "png", "gif", "webp", "bmp" -> extractImage(normalizedLanguage, content);
            default -> throw new IllegalArgumentException("不支持的二进制文件类型：" + language);
        };
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("未能从文件中提取到有效内容：" + path);
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
            metadata.append("\n说明：本次只提取图片元数据，不把图片像素发送给模型。");
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
