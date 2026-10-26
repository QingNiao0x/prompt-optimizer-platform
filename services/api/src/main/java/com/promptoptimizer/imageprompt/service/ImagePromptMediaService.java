package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.context.service.impl.ContextPageRasterizer;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.ImageSource;
import com.promptoptimizer.imageprompt.service.ImagePromptProvider.ImageData;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 私有多模态素材的有界异步解析、预览和外发字节解析，不调用模型。
 * 临时身份快照复用图像状态存储；多实例应共享受保护的素材目录，原件不进入长期历史。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class ImagePromptMediaService {
    public static final int MAX_ASSETS = 6, MAX_SELECTED_UNITS = 12, MAX_SLIDES = 100;
    public static final long MAX_BYTES = 50L * 1024 * 1024, MAX_VIDEO_MS = 30_000;
    private static final Duration TTL = Duration.ofHours(1);
    private final ImagePromptStateStore states;
    private final ContextPageRasterizer rasterizer;
    private final ImagePromptMediaDecoder decoder;
    private final Path root;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService cleanup;
    private final Map<UUID, LocalAsset> owned = new ConcurrentHashMap<>();
    private final Map<UUID, Future<?>> jobs = new ConcurrentHashMap<>();
    private final Semaphore previews = new Semaphore(2);

    /** 二进制命令仅由部署配置提供；客户端不得指定可执行文件、路径或任意解码参数。 */
    @Autowired
    public ImagePromptMediaService(ImagePromptStateStore states, ContextPageRasterizer rasterizer, ObjectMapper json,
            @Value("${app.image-prompt.media-directory:${java.io.tmpdir}/prompt-optimizer-media-assets}") String directory,
            @Value("${app.image-prompt.ffmpeg:ffmpeg}") String ffmpeg,
            @Value("${app.image-prompt.ffprobe:ffprobe}") String ffprobe) {
        this(states, rasterizer, new ImagePromptMediaDecoder(json, ffmpeg, ffprobe), Path.of(directory), Clock.systemUTC());
    }

    ImagePromptMediaService(ImagePromptStateStore states, ContextPageRasterizer rasterizer,
                            ImagePromptMediaDecoder decoder, Path directory, Clock clock) {
        this.states = states; this.rasterizer = rasterizer; this.decoder = decoder;
        this.root = directory.toAbsolutePath().normalize(); this.clock = clock;
        try { Files.createDirectories(root); } catch (IOException failure) { throw new IllegalStateException("无法准备媒体临时目录", failure); }
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
            Thread thread = new Thread(task, "image-media-parser"); thread.setDaemon(true); return thread;
        });
        cleanup = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "image-media-cleanup"); thread.setDaemon(true); return thread;
        });
        cleanup.scheduleWithFixedDelay(this::cleanExpired, 60, 60, TimeUnit.SECONDS);
    }

    /** 返回实际解析限制；视觉模型仍由平台目录及其自身图片上限控制。 */
    public Capabilities capabilities() {
        return new Capabilities(MAX_ASSETS, MAX_BYTES, MAX_SELECTED_UNITS, MAX_VIDEO_MS, MAX_SLIDES, decoder.videoAvailable());
    }

    /** 接收用户主动上传的本地素材；仅建立平台解析任务，绝不据此调用模型或外发原件。 */
    public synchronized MediaAsset upload(ActorIdentity actor, MultipartFile file, Long startMs, Long endMs) {
        cleanExpired();
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw invalid("请选择不超过 50 MiB 的图片、视频或 PPTX。");
        String owner = scope(actor);
        if (owned.values().stream().filter(value -> value.owner().equals(owner) && !value.revoked()).count() >= MAX_ASSETS)
            throw new ImagePromptException(429, "IMAGE_MEDIA_LIMIT", "最多保留六份临时素材，请移除旧素材后继续。");
        UUID id = UUID.randomUUID(); Path directory = directory(id);
        try {
            checkDiskBudget(file.getSize()); Files.createDirectory(directory);
            Path source = directory.resolve("source.bin");
            try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(source, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192]; long copied = 0; int count;
                while ((count = input.read(buffer)) != -1) {
                    copied += count; if (copied > MAX_BYTES) throw invalid("素材超过 50 MiB 限制。"); output.write(buffer, 0, count);
                }
            }
            Kind kind = decoder.kind(source);
            if (kind == Kind.IMAGE && Files.size(source) > ImagePromptAssetService.MAX_BYTES) throw invalid("图片不能超过 10 MiB。");
            if (kind == Kind.VIDEO && !decoder.videoAvailable()) throw new ImagePromptException(503, "IMAGE_VIDEO_UNAVAILABLE", "服务器尚未配置可用的 FFmpeg 和 ffprobe，暂时无法解析视频。");
            validateRange(startMs, endMs);
            var asset = new MediaAsset(id, name(file.getOriginalFilename()), kind, Status.PROCESSING, List.of(), List.of(),
                    clock.instant().plus(TTL), 0, null);
            states.put(actor, key(id), new StoredMedia(asset, false), TTL);
            owned.put(id, new LocalAsset(owner, asset.expiresAt(), false));
            var task = new FutureTask<Void>(() -> { process(actor, asset, source, startMs, endMs); return null; });
            jobs.put(id, task);
            try { workers.execute(task); }
            catch (RejectedExecutionException failure) {
                revoke(actor, id); throw new ImagePromptException(503, "IMAGE_MEDIA_BUSY", "媒体解析任务较多，请稍后重试。");
            }
            return asset;
        } catch (ImagePromptException failure) {
            if (!owned.containsKey(id)) deleteDirectory(directory); throw failure;
        } catch (IOException failure) {
            deleteDirectory(directory); throw invalid("素材上传失败，请检查文件后重试。");
        }
    }

    /** 当前身份可以轮询解析状态；失败、部分识别和处理中不伪装为完整成功。 */
    public MediaAsset require(ActorIdentity actor, UUID id) {
        if (Boolean.TRUE.equals(states.get(actor, "media-revoked:" + id, Boolean.class))) throw ImagePromptException.expired();
        StoredMedia stored = states.get(actor, key(id), StoredMedia.class);
        if (stored == null || stored.revoked() || !stored.asset().expiresAt().isAfter(clock.instant())) throw ImagePromptException.expired();
        if (!Files.isDirectory(directory(id), LinkOption.NOFOLLOW_LINKS)) throw new ImagePromptException(409, "IMAGE_MEDIA_UNAVAILABLE", "素材已不可用，请重新上传；多实例需要共享私有临时目录。");
        return stored.asset();
    }

    /** 页面预览仅输出重新编码 PNG，不返回原始视频、PPTX、备注或嵌入对象。 */
    public byte[] preview(ActorIdentity actor, UUID id, UUID unitId) {
        MediaAsset asset = ready(actor, id); Unit unit = findUnit(asset, unitId);
        return unitBytes(actor, asset, unit, List.of());
    }

    /** 导出与模型使用同一规范化和遮挡路径的选定单位，供用户在下游配套上传，绝不导出原件。 */
    public byte[] export(ActorIdentity actor, UUID id, UUID unitId, List<Mask> masks) {
        MediaAsset asset = ready(actor, id); Unit unit = findUnit(asset, unitId);
        return unitBytes(actor, asset, unit, validateMasks(masks));
    }

    /** 撤销首先写入完整身份下的状态；后台停止继续解析，物理文件随后由临时清理任务删除。 */
    public void revoke(ActorIdentity actor, UUID id) {
        MediaAsset asset = require(actor, id);
        // 独立撤销墓碑不会被另一实例迟到的解析完成快照覆盖；随机素材编号本身不是访问授权。
        states.put(actor, "media-revoked:" + id, true, TTL);
        states.put(actor, key(id), new StoredMedia(new MediaAsset(asset.id(), asset.name(), asset.kind(), Status.CANCELLED,
                asset.units(), List.of("素材已撤销。"), asset.expiresAt(), asset.totalUnits(), asset.durationMs()), true), TTL);
        owned.computeIfPresent(id, (ignored, local) -> new LocalAsset(local.owner(), local.expiresAt(), true));
        Future<?> task = jobs.remove(id); if (task != null) task.cancel(true);
    }

    /**
     * 仅解析请求明确选中的单位，应用掩码后才形成外发字节；不在这里启动任何模型调用。
     * 有遮挡时舍弃该单位全部结构原文，防止文字层绕过像素遮挡；选区与来源进入计划指纹。
     */
    public Resolved resolve(ActorIdentity actor, List<MaterialRef> materials) {
        if (materials == null || materials.size() > MAX_ASSETS) throw invalid("每个任务最多选择六份素材。");
        var images = new ArrayList<ImageData>(); var sources = new ArrayList<MaterialSource>(); var warnings = new ArrayList<String>();
        var assetIds = new HashSet<UUID>(); long bytes = 0;
        for (MaterialRef ref : materials) {
            if (ref == null || ref.assetId() == null || ref.track() == null || ref.role() == null || ref.units() == null
                    || ref.units().isEmpty() || !assetIds.add(ref.assetId())) throw invalid("素材来源、用途和选取范围不能为空，且同一素材不能重复提交。");
            MediaAsset asset = ready(actor, ref.assetId()); var selected = new ArrayList<SourceUnit>(); var unitIds = new HashSet<UUID>();
            for (UnitSelection selection : ref.units()) {
                if (selection == null || selection.unitId() == null || !unitIds.add(selection.unitId())) throw invalid("素材页或视频帧不能重复或为空。");
                if (images.size() >= MAX_SELECTED_UNITS) throw invalid("每个任务最多选择十二个图片、页面或视频帧。");
                Unit unit = findUnit(asset, selection.unitId()); List<Mask> masks = validateMasks(selection.masks());
                String text = masks.isEmpty() ? unit.text() : "";
                if (new SensitiveValueScanner().containsCredential(text)) throw invalid("选中页面的文字含疑似凭据，请先遮挡或移除该页面。");
                byte[] png = unitBytes(actor, asset, unit, masks); bytes += png.length;
                if (bytes > 30L * 1024 * 1024) throw invalid("所选规范化素材总量超过 30 MiB，请减少页面或视频帧。");
                String sourceName = asset.name() + (unit.page() != null ? " · 第 " + unit.page() + " 页" : unit.timestampMs() != null ? " · " + unit.timestampMs() + " ms" : "");
                images.add(new ImageData(new ImageSource(unit.id(), sourceName, ImagePromptAssetService.hash(png), ref.role(), unit.width(), unit.height(), asset.expiresAt()), "image/png", png));
                selected.add(new SourceUnit(unit.id(), unit.page(), unit.timestampMs(), text, masks));
            }
            var coverage = new ArrayList<>(asset.warnings());
            if (asset.kind() == Kind.PPTX) coverage.add("仅分析选中的 " + selected.size() + " / " + asset.totalUnits() + " 个可见页面；未选择页面不参与模型分析。");
            if (asset.kind() == Kind.VIDEO) {
                // 解析产生的预览总数与本任务实际外发的选中数是不同口径；原片段和部分覆盖风险继续保留。
                coverage.add("解析共生成 " + asset.units().size() + " 个预览帧；本任务选择 " + selected.size() + " 个采样帧（具体时间戳见 units）。");
                coverage.add("仅分析所选时间戳的采样帧，不能据此声称完整运动、音轨或音乐节拍已识别。");
            }
            sources.add(new MaterialSource(asset.id(), asset.name(), asset.kind(), ref.track(), ref.role(), List.copyOf(selected), List.copyOf(coverage)));
            warnings.addAll(coverage);
        }
        return new Resolved(List.copyOf(images), List.copyOf(sources), List.copyOf(warnings));
    }

    /** 异步阶段只在原完整身份仍有效时发布结果，失败清理原件，不公开异常 cause 或媒体正文。 */
    private void process(ActorIdentity actor, MediaAsset initial, Path source, Long startMs, Long endMs) {
        try {
            ImagePromptMediaDecoder.Decoded decoded = decoder.decode(source, initial.kind(), initial.id(), startMs, endMs);
            require(actor, initial.id()); if (Thread.currentThread().isInterrupted()) return;
            var asset = new MediaAsset(initial.id(), initial.name(), initial.kind(), decoded.partial() ? Status.PARTIAL : Status.READY,
                    decoded.units(), decoded.warnings(), initial.expiresAt(), decoded.totalUnits(), decoded.durationMs());
            // 图片/视频的派生 PNG 已充足，及时删除原件；PPTX 为选页惰性预览保留至原有临时有效期。
            if (initial.kind() != Kind.PPTX) Files.deleteIfExists(source);
            states.put(actor, key(initial.id()), new StoredMedia(asset, false), TTL);
        } catch (Exception failure) {
            try {
                require(actor, initial.id());
                if (!Thread.currentThread().isInterrupted()) states.put(actor, key(initial.id()), new StoredMedia(new MediaAsset(initial.id(), initial.name(), initial.kind(), Status.FAILED,
                        List.of(), List.of(failure instanceof ImagePromptException expected ? expected.getMessage() : "素材解析失败，请重新导出后上传。"), initial.expiresAt(), 0, null), false), TTL);
            } catch (RuntimeException ignored) { /* 已撤销或过期的任务不得恢复访问权。 */ }
            try { Files.deleteIfExists(source); } catch (IOException ignored) { /* 原件仍由保留期清理兜底。 */ }
        } finally { jobs.remove(initial.id()); }
    }

    /** 外发或预览必须等待解析完成，失败及处理中不能通过空证据继续。 */
    private MediaAsset ready(ActorIdentity actor, UUID id) {
        MediaAsset asset = require(actor, id);
        if (asset.status() != Status.READY && asset.status() != Status.PARTIAL) throw ImagePromptException.conflict(asset.status() == Status.PROCESSING ? "素材仍在解析，请等待完成后继续。" : "素材解析未成功，请重新上传。");
        return asset;
    }

    /** 预览与外发共用实际像素路径，惰性渲染受并发限制；每次读取仍复核身份与撤销状态。 */
    private byte[] unitBytes(ActorIdentity actor, MediaAsset asset, Unit unit, List<Mask> masks) {
        if (!previews.tryAcquire()) throw new ImagePromptException(429, "IMAGE_MEDIA_PREVIEW_BUSY", "素材预览正在处理中，请稍后重试。");
        try {
            require(actor, asset.id()); Path path = directory(asset.id()).resolve(unit.id() + ".png");
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                if (asset.kind() != Kind.PPTX) throw ImagePromptException.expired();
                byte[] png = decoder.renderSlide(directory(asset.id()).resolve("source.bin"), unit.page());
                checkDiskBudget(png.length); Files.write(path, png, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            var converted = masks.stream().map(mask -> new com.promptoptimizer.context.domain.ContextVisionModels.Mask(mask.x(), mask.y(), mask.width(), mask.height())).toList();
            return rasterizer.png(() -> rasterizer.image(path), converted);
        } catch (ImagePromptException failure) { throw failure; }
        catch (Exception failure) { throw invalid("无法生成该素材的安全预览，请重新导出后上传。"); }
        finally { previews.release(); }
    }

    /** 拒绝 NaN、越界或过多矩形，不以 CSS 覆盖代替模型实际接收字节上的遮挡。 */
    private List<Mask> validateMasks(List<Mask> requested) {
        if (requested == null) return List.of();
        if (requested.size() > 20) throw invalid("每个素材单位最多设置二十处遮挡。");
        for (Mask mask : requested) if (mask == null || !Double.isFinite(mask.x()) || !Double.isFinite(mask.y()) || !Double.isFinite(mask.width()) || !Double.isFinite(mask.height())
                || mask.x() < 0 || mask.y() < 0 || mask.width() <= 0 || mask.height() <= 0 || mask.x() + mask.width() > 1 || mask.y() + mask.height() > 1) throw invalid("遮挡范围必须位于图片内，坐标使用 0 到 1 的比例。");
        return List.copyOf(requested);
    }

    private Unit findUnit(MediaAsset asset, UUID id) {
        return asset.units().stream().filter(unit -> unit.id().equals(id)).findFirst().orElseThrow(() -> invalid("选择的页面或视频帧不属于该素材。"));
    }

    private void validateRange(Long startMs, Long endMs) {
        if (startMs != null && startMs < 0 || endMs != null && (endMs <= (startMs == null ? 0 : startMs)
                || endMs - (startMs == null ? 0 : startMs) > MAX_VIDEO_MS)) throw invalid("请选择不超过三十秒的有效视频时间段。");
    }

    /** 同一私有根目录的所有进程资产共同计入磁盘预算，单账号额度不能代替总量限制。 */
    private void checkDiskBudget(long incoming) throws IOException {
        try (var paths = Files.walk(root, 2)) {
            long total = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).mapToLong(path -> {
                try { return Files.size(path); } catch (IOException failure) { return MAX_BYTES; }
            }).sum();
            if (incoming + total > 512L * 1024 * 1024) throw new ImagePromptException(503, "IMAGE_MEDIA_STORAGE_FULL", "媒体临时空间繁忙，请稍后重试。");
        }
    }

    /** 到期或撤销后的物理清理由独立周期执行；孤立目录也只能在本服务根内按期限回收。 */
    private void cleanExpired() {
        Instant now = clock.instant();
        owned.forEach((id, value) -> {
            if (value.revoked() || !value.expiresAt().isAfter(now)) {
                Future<?> task = jobs.remove(id); if (task != null) task.cancel(true);
                deleteDirectory(directory(id)); owned.remove(id, value);
            }
        });
        // 进程重启后的孤立目录同样受保留期约束；仅处理本服务生成的 UUID 目录，不递归跟随链接。
        try (var directories = Files.list(root)) {
            directories.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && path.getFileName().toString().matches("[0-9a-f-]{36}"))
                    .filter(path -> { try { return Files.getLastModifiedTime(path).toInstant().plus(TTL).isBefore(now); } catch (IOException failure) { return false; } })
                    .forEach(this::deleteDirectory);
        } catch (IOException ignored) { /* 读取失败不开放任何素材，下一周期继续清理。 */ }
    }

    /** 仅删除已经校验为根目录直接子级的 UUID 目录，遍历不跟随符号链接。 */
    private void deleteDirectory(Path path) {
        if (!path.toAbsolutePath().normalize().getParent().equals(root)) return;
        try (var paths = Files.walk(path)) { paths.sorted(Comparator.reverseOrder()).forEach(item -> { try { Files.deleteIfExists(item); } catch (IOException ignored) { } }); }
        catch (IOException ignored) { }
    }

    private Path directory(UUID id) { return root.resolve(id.toString()); }
    private String key(UUID id) { return "media:" + id; }
    private String scope(ActorIdentity actor) { return actor.tenantId() + ":" + actor.workspaceId() + ":" + actor.userId(); }
    private String name(String value) { String safe = value == null ? "素材" : value.replace('\\', '/'); safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", ""); return safe.isBlank() ? "素材" : safe.substring(0, Math.min(180, safe.length())); }
    private ImagePromptException invalid(String message) { return ImagePromptException.invalid(message); }

    /** 终止私有解析线程；业务历史与其他模块的执行器不受影响。 */
    @PreDestroy
    public void close() { jobs.values().forEach(job -> job.cancel(true)); workers.shutdownNow(); cleanup.shutdownNow(); }
    private record LocalAsset(String owner, Instant expiresAt, boolean revoked) { }
}
