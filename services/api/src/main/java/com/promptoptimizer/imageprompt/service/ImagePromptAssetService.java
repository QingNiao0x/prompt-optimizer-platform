package com.promptoptimizer.imageprompt.service;

import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;

/**
 * 私有临时图片资产：先读尺寸限制解码内存，再校正方向并重新编码清除元数据。
 * 本机临时目录不对外静态开放；多实例须共享此受控目录或配置会话亲和，不把缺失文件冒充已识图。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class ImagePromptAssetService {
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final long MAX_PIXELS = 15_000_000;
    public static final int MAX_EDGE = 8192;
    public static final Duration TTL = Duration.ofHours(1);
    private final Path root;
    private final ImagePromptStateStore store;
    private final Clock clock;
    private final ScheduledExecutorService cleanup;
    private final Map<UUID, LocalAsset> owned = new ConcurrentHashMap<>();

    @Autowired
    public ImagePromptAssetService(ImagePromptStateStore store,
            @Value("${app.image-prompt.asset-directory:${java.io.tmpdir}/prompt-optimizer-image-assets}") String root) {
        this(store, Path.of(root), Clock.systemUTC());
    }

    ImagePromptAssetService(ImagePromptStateStore store, Path root, Clock clock) {
        this.store = store; this.root = root.toAbsolutePath().normalize(); this.clock = clock;
        try { Files.createDirectories(this.root); }
        catch (IOException failure) { throw new IllegalStateException("无法准备图像临时目录", failure); }
        cleanup = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "image-asset-cleanup"); thread.setDaemon(true); return thread;
        });
        cleanup.scheduleWithFixedDelay(this::cleanExpired, 0, 60, TimeUnit.SECONDS);
    }

    /** 串行受控解码限制瞬时内存；前端声明大小、后缀和 MIME 均不作为可信格式依据。 */
    public synchronized Asset upload(ActorIdentity actor, MultipartFile file) {
        return upload(actor, null, file);
    }

    /** 复用像素、EXIF及磁盘预算校验，访客资产与正式账号完全分区。 */
    public synchronized Asset uploadGuest(String guest, MultipartFile file) {
        return upload(null, guest, file);
    }

    private Asset upload(ActorIdentity actor, String guest, MultipartFile file) {
        cleanExpired();
        String owner = actor == null ? "guest:" + guest : scope(actor);
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw ImagePromptException.invalid("请选择不超过 10 MiB 的 JPEG 或 PNG 图片。");
        if (owned.values().stream().filter(a -> a.owner().equals(owner)).count() >= (actor == null ? 2 : 12)) {
            throw new ImagePromptException(429, "IMAGE_ASSET_LIMIT", "本次账号临时图片较多，请移除旧图片或等待过期。");
        }
        try (var input = file.getInputStream()) {
            byte[] raw = input.readNBytes((int) MAX_BYTES + 1);
            if (raw.length > MAX_BYTES) throw ImagePromptException.invalid("图片超过 10 MiB 限制。");
            String format;
            BufferedImage decoded;
            try (var imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
                var readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw ImagePromptException.invalid("图片无法解码，请选择真实的 JPEG 或 PNG 文件。");
                var reader = readers.next();
                try {
                    reader.setInput(imageInput, true, true);
                    format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!Set.of("jpeg", "jpg", "png").contains(format)) throw ImagePromptException.invalid("目前只支持 JPEG 和 PNG 图片。");
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    // 在 read(0) 分配像素缓冲区之前阻止超大压缩图片，乘法使用 long 避免溢出。
                    if (width < 1 || height < 1 || width > MAX_EDGE || height > MAX_EDGE || (long) width * height > MAX_PIXELS) {
                        throw ImagePromptException.invalid("图片须不超过 1500 万像素，单边不超过 8192 像素。");
                    }
                    decoded = reader.read(0);
                } finally { reader.dispose(); }
            }
            int orientation = format.equals("png") ? 1 : orientation(raw);
            BufferedImage normalized = orient(decoded, orientation);
            var encoded = new ByteArrayOutputStream();
            String output = format.equals("png") ? "png" : "jpeg";
            if (!ImageIO.write(normalized, output, encoded)) throw ImagePromptException.invalid("图片编码失败，请重新导出后上传。");
            byte[] bytes = encoded.toByteArray();
            if (bytes.length > MAX_BYTES) throw ImagePromptException.invalid("图片规范化后超过 10 MiB，请降低分辨率后重试。");
            // 磁盘总预算独立于单账号限制，也覆盖同一目录中的其他进程资产。
            try (var paths = Files.list(root)) {
                long total = paths.filter(this::isAssetFile).mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return MAX_BYTES; } }).sum();
                if (total + bytes.length > 512L * 1024 * 1024) throw new ImagePromptException(503, "IMAGE_STORAGE_FULL", "临时图片空间繁忙，请稍后重试。");
            }
            UUID id = UUID.randomUUID();
            var asset = new Asset(id, safeName(file.getOriginalFilename()), "image/" + output, normalized.getWidth(), normalized.getHeight(),
                    bytes.length, hash(bytes), clock.instant().plus(TTL), (actor == null ? "/api/v1/trial/image-prompt-assets/" : "/api/v1/image-prompt-assets/") + id,
                    List.of(orientation == 1 ? "已保留画面方向" : "已校正照片方向", "已重新编码并清除图片元数据"));
            Path path = path(id);
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
            try {
                if (actor == null) store.putGuest(guest, "asset:" + id, new StoredAsset(asset, false), TTL);
                else store.put(actor, "asset:" + id, new StoredAsset(asset, false), TTL);
            }
            catch (RuntimeException failure) { Files.deleteIfExists(path); throw failure; }
            owned.put(id, new LocalAsset(owner, asset.expiresAt()));
            return asset;
        } catch (ImagePromptException failure) { throw failure; }
        catch (IOException failure) { throw ImagePromptException.invalid("图片处理失败，请检查文件是否损坏。"); }
    }

    /** 返回仍可访问的服务端元数据；工作区/租户/用户都来自认证上下文。 */
    public Asset require(ActorIdentity actor, UUID id) {
        StoredAsset stored = store.get(actor, "asset:" + id, StoredAsset.class);
        if (stored == null || stored.revoked() || !stored.asset().expiresAt().isAfter(clock.instant())) throw ImagePromptException.expired();
        if (!Files.isRegularFile(path(id), LinkOption.NOFOLLOW_LINKS)) throw new ImagePromptException(409, "IMAGE_ASSET_UNAVAILABLE", "参考图已不可用，请重新上传；多实例部署需共享临时图片目录。");
        return stored.asset();
    }

    /** 只读取授权资产的受控路径，不接受客户端文件路径或 URL。 */
    public byte[] bytes(ActorIdentity actor, UUID id) {
        require(actor, id);
        try { return Files.readAllBytes(path(id)); }
        catch (IOException failure) { throw ImagePromptException.expired(); }
    }

    /** 先撤销引用再清理临时文件；用户历史记录另由逻辑删除维护。 */
    public void revoke(ActorIdentity actor, UUID id) {
        var stored = store.get(actor, "asset:" + id, StoredAsset.class);
        if (stored == null || stored.revoked()) return;
        store.put(actor, "asset:" + id, new StoredAsset(stored.asset(), true), TTL);
        try { Files.deleteIfExists(path(id)); owned.remove(id); }
        catch (IOException failure) { LoggerFactory.getLogger(getClass()).warn("event=image.asset.cleanup_failed"); }
    }

    /** 预览与识图均先检查访客所属缓存，不把随机资源编号当作授权。 */
    public Asset requireGuest(String guest, UUID id) {
        StoredAsset stored = store.getGuest(guest, "asset:" + id, StoredAsset.class);
        if (stored == null || stored.revoked() || !stored.asset().expiresAt().isAfter(clock.instant())) throw ImagePromptException.expired();
        if (!Files.isRegularFile(path(id), LinkOption.NOFOLLOW_LINKS)) throw ImagePromptException.expired();
        return stored.asset();
    }

    /** 只读取经过访客归属校验的规范图片，不接受任意路径。 */
    public byte[] bytesGuest(String guest, UUID id) {
        requireGuest(guest, id);
        try { return Files.readAllBytes(path(id)); }
        catch (IOException failure) { throw ImagePromptException.expired(); }
    }

    /** 撤销仅影响当前访客临时资源，正式历史不参与此流程。 */
    public void revokeGuest(String guest, UUID id) {
        var asset = store.getGuest(guest, "asset:" + id, StoredAsset.class);
        if (asset == null || asset.revoked()) return;
        store.putGuest(guest, "asset:" + id, new StoredAsset(asset.asset(), true), TTL);
        try { Files.deleteIfExists(path(id)); owned.remove(id); }
        catch (IOException failure) { LoggerFactory.getLogger(getClass()).warn("event=image.asset.cleanup_failed"); }
    }

    /** 定时清理也回收异常退出留下的规范文件；不跟随目录/符号链接，不递归删除其他文件。 */
    private void cleanExpired() {
        Instant before = clock.instant().minus(TTL);
        try (var paths = Files.list(root)) {
            paths.filter(this::isAssetFile).forEach(p -> {
                try { if (Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(before)) Files.deleteIfExists(p); }
                catch (IOException failure) { LoggerFactory.getLogger(getClass()).warn("event=image.asset.cleanup_failed"); }
            });
            owned.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(clock.instant()));
        } catch (IOException failure) { LoggerFactory.getLogger(getClass()).warn("event=image.asset.cleanup_failed"); }
    }

    private Path path(UUID id) { return root.resolve(id + ".image"); }
    private boolean isAssetFile(Path p) {
        return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && p.getFileName().toString().matches("[0-9a-f-]{36}\\.image");
    }
    private String scope(ActorIdentity a) { return a.tenantId() + ":" + a.workspaceId() + ":" + a.userId(); }
    /** 名称仅用于来源展示，去除路径和控制字符；空末段使用固定名称。 */
    private String safeName(String name) {
        if (name == null) return "参考图片";
        String[] parts = name.replace('\\', '/').split("/", -1);
        String clean = parts[parts.length - 1].replaceAll("[\\p{Cntrl}]", "");
        return clean.isBlank() ? "参考图片" : clean.substring(0, Math.min(120, clean.length()));
    }
    /** SHA-256 对实际发送的规范化像素文件计算，Plan 不能复用另一裁剪版本的确认。 */
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    /** 只解析 JPEG APP1 中有界的 TIFF Orientation 标记，忽略损坏元数据，不执行任何外部引用。 */
    static int orientation(byte[] bytes) {
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

    /** EXIF 八种方向映射为实际像素，随后重编码不再携带原位置、设备和 EXIF 数据。 */
    private BufferedImage orient(BufferedImage image, int orientation) {
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

    /** 释放模块自己的清理线程，不停止其他任务或服务线程。 */
    @PreDestroy
    public void close() { cleanup.shutdownNow(); }
    public record StoredAsset(Asset asset, boolean revoked) { }
    private record LocalAsset(String owner, Instant expiresAt) { }
}
