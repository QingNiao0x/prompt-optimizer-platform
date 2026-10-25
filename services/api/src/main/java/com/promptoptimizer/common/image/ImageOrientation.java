package com.promptoptimizer.common.image;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 共用有界 JPEG 方向读取和像素校正；不保留或执行 EXIF 中的其他内容。
 * @author QingNiao
 * @since 0.1.0
 */
public final class ImageOrientation {
    private ImageOrientation() { }
    /** 损坏或缺失的方向回退为 1，仅扫描 JPEG APP1 内的有限 TIFF 条目。 */
    public static int read(byte[] bytes) {
        try {
            int offset = 2;
            while (offset + 4 <= bytes.length && (bytes[offset] & 255) == 255) {
                int marker = bytes[offset + 1] & 255;
                if (marker == 218 || marker == 217) break;
                int length = ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
                if (length < 2 || offset + 2 + length > bytes.length) break;
                if (marker == 225 && length >= 16 && bytes[offset + 4] == 'E' && bytes[offset + 5] == 'x'
                        && bytes[offset + 6] == 'i' && bytes[offset + 7] == 'f') {
                    int base = offset + 10, end = offset + 2 + length;
                    var buffer = ByteBuffer.wrap(bytes).order(bytes[base] == 'I' && bytes[base + 1] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                    long position = base + Integer.toUnsignedLong(buffer.getInt(base + 4));
                    if (position < base || position + 2 > end) return 1;
                    int count = Short.toUnsignedInt(buffer.getShort((int) position));
                    for (int i = 0; i < Math.min(count, 256); i++) {
                        long item = position + 2 + 12L * i;
                        if (item + 12 > end) return 1;
                        if (Short.toUnsignedInt(buffer.getShort((int) item)) == 274) {
                            int value = Short.toUnsignedInt(buffer.getShort((int) item + 8));
                            return value >= 1 && value <= 8 ? value : 1;
                        }
                    }
                }
                offset += 2 + length;
            }
        } catch (IndexOutOfBoundsException failure) { return 1; }
        return 1;
    }
    /** 校正八种方向并释放旧图像；重编码由调用方完成。 */
    public static BufferedImage normalize(BufferedImage image, int orientation) {
        int w = image.getWidth(), h = image.getHeight();
        var result = new BufferedImage(orientation >= 5 ? h : w, orientation >= 5 ? w : h,
                image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> new AffineTransform();
        };
        Graphics2D graphics = result.createGraphics();
        try { graphics.drawImage(image, transform, null); } finally { graphics.dispose(); image.flush(); }
        return result;
    }
}
