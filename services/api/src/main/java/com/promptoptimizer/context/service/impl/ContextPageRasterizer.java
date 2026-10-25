package com.promptoptimizer.context.service.impl;

import com.promptoptimizer.common.image.ImageOrientation;
import com.promptoptimizer.context.domain.ContextVisionModels.Mask;
import com.promptoptimizer.context.service.DocumentUploadException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;

/**
 * 在服务端将指定页转换为方向一致的无元数据 PNG，并在实际外发像素上应用不透明遮挡。
 * 预览与识别共用同一尺寸算法；限制并行栅格化及像素分配，拒绝任意路径与外部 URL。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class ContextPageRasterizer {
    private static final long MAX_PIXELS = 4_000_000L;
    private final Semaphore rendering = new Semaphore(2);

    /** 校验实际格式与尺寸在解码之前完成，JPEG 方向沿用共用校正算法。 */
    public BufferedImage image(Path source) throws IOException {
        if (Files.size(source) > 10L * 1024 * 1024) throw invalid("用于视觉识别的图片不能超过 10 MiB。");
        byte[] raw = Files.readAllBytes(source);
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("图片实际格式不受支持。");
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!List.of("jpeg", "jpg", "png").contains(format)) throw invalid("视觉识别仅接受实际 JPEG 或 PNG 图片。");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 8192 || height > 8192 || (long) width * height > 15_000_000L)
                    throw invalid("图片超过 1500 万像素或单边 8192 像素上限。");
                return bounded(ImageOrientation.normalize(reader.read(0), format.equals("png") ? 1 : ImageOrientation.read(raw)));
            } finally { reader.dispose(); }
        }
    }
    /** 按页渲染并使用 PDF 内部裁剪与旋转；巨大页面先缩放，再分配图像。 */
    public BufferedImage pdf(PDDocument document, int page) throws IOException {
        var box = document.getPage(page - 1).getCropBox();
        double width = box.getWidth(), height = box.getHeight();
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) throw invalid("PDF 页面尺寸无效。");
        float scale = (float) Math.min(2D, Math.min(Math.sqrt(MAX_PIXELS / width / height), 4096D / Math.max(width, height)));
        var renderer = new PDFRenderer(document);
        renderer.setSubsamplingAllowed(true);
        return renderer.renderImage(page - 1, scale, ImageType.RGB);
    }
    /** 同时限制预览请求和后台识别中的栅格化，取消时不启动新的工作。 */
    public byte[] png(ImageSupplier supplier, List<Mask> masks) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw invalid("文档处理已取消。");
        if (!rendering.tryAcquire()) throw new DocumentUploadException(DocumentUploadException.Reason.CAPACITY_EXCEEDED, "页面预览正在处理中，请稍后重试。");
        BufferedImage image = null;
        try {
            image = supplier.get();
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(Color.BLACK);
                for (Mask mask : masks) {
                    int x = (int) Math.floor(mask.x() * image.getWidth()), y = (int) Math.floor(mask.y() * image.getHeight());
                    int right = (int) Math.ceil((mask.x() + mask.width()) * image.getWidth());
                    int bottom = (int) Math.ceil((mask.y() + mask.height()) * image.getHeight());
                    graphics.fillRect(x, y, right - x, bottom - y);
                }
            } finally { graphics.dispose(); }
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) throw invalid("无法生成安全图片。");
            if (output.size() > 10 * 1024 * 1024) throw invalid("规范化页面超过 10 MiB 上限。");
            return output.toByteArray();
        } finally { if (image != null) image.flush(); rendering.release(); }
    }
    /** 保持长宽比缩小并铺白底，限制实际外发像素；不将透明层当作脱敏遮挡。 */
    private BufferedImage bounded(BufferedImage image) {
        double scale = Math.min(1D, Math.min(Math.sqrt((double) MAX_PIXELS / image.getWidth() / image.getHeight()), 4096D / Math.max(image.getWidth(), image.getHeight())));
        var result = new BufferedImage(Math.max(1, (int) (image.getWidth() * scale)), Math.max(1, (int) (image.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
        var graphics = result.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, result.getWidth(), result.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image, 0, 0, result.getWidth(), result.getHeight(), null);
        } finally { graphics.dispose(); image.flush(); }
        return result;
    }
    private DocumentUploadException invalid(String message) { return new DocumentUploadException(DocumentUploadException.Reason.INVALID_ARGUMENT, message); }
    /** 图像仅在有名额时创建，调用方不得返回共享的可变像素。 */
    @FunctionalInterface public interface ImageSupplier { BufferedImage get() throws IOException; }
}
