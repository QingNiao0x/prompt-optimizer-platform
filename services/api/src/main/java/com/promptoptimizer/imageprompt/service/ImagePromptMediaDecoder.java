package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.image.ImageOrientation;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.*;
import org.apache.poi.xslf.usermodel.*;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * 只处理本服务生成的私有文件路径：JPEG/PNG 重编码、PPTX 静态结构与页面渲染、短视频有界抽帧。
 * FFmpeg 的程序和参数由服务端固定，禁用网络协议、音轨和字幕；解析不等于完整语义识别。
 * @author QingNiao
 * @since 0.1.0
 */
public class ImagePromptMediaDecoder {
    private static final int MAX_EDGE = 1600;
    private static final Pattern TIMESTAMP = Pattern.compile("pts_time:([0-9]+(?:\\.[0-9]+)?)");
    private final ObjectMapper json;
    private final String ffmpeg, ffprobe;
    private volatile Boolean available;

    /** 可执行程序来自受信任部署配置，绝不使用上传的文件名构建 shell 命令。 */
    public ImagePromptMediaDecoder(ObjectMapper json, String ffmpeg, String ffprobe) {
        this.json = json; this.ffmpeg = ffmpeg; this.ffprobe = ffprobe;
    }

    /** 同时验证探测与解码工具，检测结果仅在本实例缓存，不把文件后缀当解码能力。 */
    public boolean videoAvailable() {
        Boolean value = available;
        if (value != null) return value;
        try { run(List.of(ffmpeg, "-version"), 4, 64 * 1024); run(List.of(ffprobe, "-version"), 4, 64 * 1024); available = true; }
        catch (Exception failure) { available = false; }
        return available;
    }

    /** 先判断真实容器签名，ZIP 还必须通过 PPTX 包结构检查；不接受播放列表、URL 或任意文件。 */
    public Kind kind(Path source) throws IOException {
        byte[] header;
        try (InputStream input = Files.newInputStream(source)) { header = input.readNBytes(16); }
        if (header.length >= 8 && (header[0] == (byte) 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                || header[0] == (byte) 0xff && header[1] == (byte) 0xd8)) return Kind.IMAGE;
        if (header.length >= 4 && header[0] == 'P' && header[1] == 'K') {
            try (ZipFile zip = new ZipFile(source.toFile())) {
                if (zip.getEntry("[Content_Types].xml") != null && zip.getEntry("ppt/presentation.xml") != null) return Kind.PPTX;
            } catch (ZipException failure) { throw invalid("PPTX 文件已损坏。"); }
            throw invalid("仅支持真正的 PPTX 幻灯片，不接受其他 ZIP 文件。");
        }
        if (header.length >= 12 && (header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p'
                || header[0] == 0x1a && header[1] == 0x45 && header[2] == (byte) 0xdf && header[3] == (byte) 0xa3)) return Kind.VIDEO;
        throw invalid("仅支持 JPEG、PNG、MP4/MOV/WebM 视频和 PPTX，文件实际格式不受支持。");
    }

    /** 原件只产生静态证据；视频和 PPT 的覆盖范围明确写入结果，不推断音频和动画。 */
    public Decoded decode(Path source, Kind kind, UUID assetId, Long startMs, Long endMs) throws Exception {
        return switch (kind) {
            case IMAGE -> image(source, assetId);
            case PPTX -> presentation(source, assetId);
            case VIDEO -> video(source, assetId, startMs, endMs);
        };
    }

    /** 重编码后只保留规范化像素，原始 EXIF 不进入派生文件。 */
    private Decoded image(Path source, UUID assetId) throws IOException {
        BufferedImage image = readImage(source); UUID unit = UUID.randomUUID();
        try {
            byte[] png = png(image); Files.write(source.getParent().resolve(unit + ".png"), png, StandardOpenOption.CREATE_NEW);
            return new Decoded(List.of(unit(assetId, unit, null, null, image.getWidth(), image.getHeight(), "")), List.of("图片已校正方向、缩放并清除元数据。"), 1, null, false);
        } finally { image.flush(); }
    }

    /** 遍历有限可见页建立索引，实际图像按用户预览或选择再渲染，避免默认渲染整份幻灯片。 */
    private Decoded presentation(Path source, UUID assetId) throws Exception {
        checkPackage(source);
        try (var ppt = new XMLSlideShow(Files.newInputStream(source))) {
            if (ppt.getSlides().size() > ImagePromptMediaService.MAX_SLIDES) throw invalid("PPTX 最多支持一百页，请拆分后上传。");
            Dimension size = ppt.getPageSize(); validateSlideSize(size);
            Dimension rendered = boundedSize(size.width, size.height); var units = new ArrayList<Unit>(); int page = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                interrupted(); page++;
                // 隐藏页和演讲者备注不是默认授权材料；保留原始页码，不重新编号误导用户。
                if (slide.getXmlObject().isSetShow() && !slide.getXmlObject().getShow()) continue;
                if (slide.getShapes().size() > 1000) throw invalid("幻灯片元素过多，请简化页面后上传。");
                StringBuilder text = new StringBuilder();
                text.append("第 ").append(page).append(" 页；画布 ").append(size.width).append("×").append(size.height).append(" pt。\n");
                appendShapes(slide.getShapes(), text, 0);
                units.add(unit(assetId, UUID.randomUUID(), page, null, rendered.width, rendered.height, limit(text.toString(), 4000)));
            }
            if (units.isEmpty()) throw invalid("PPTX 没有可见幻灯片。");
            return new Decoded(List.copyOf(units), List.of("只解析可见静态页面，不读取备注；动画、切换效果和内嵌媒体未被理解。",
                    "页面结构文字是每页最多4000字符的有界摘录，不代表全文转录。",
                    "图表按源文件缓存值解析，可能不是工作簿最新值；预览不保证绘制全部图表、字体和复杂图形，请对照原件核对。"), units.size(), null, false);
        }
    }

    /** 仅对用户选择或预览的指定页分配图像；页面尺寸和像素预算在分配前校验。 */
    public byte[] renderSlide(Path source, int page) throws IOException {
        try (var ppt = new XMLSlideShow(Files.newInputStream(source))) {
            if (page < 1 || page > ppt.getSlides().size()) throw invalid("幻灯片页码无效。");
            XSLFSlide slide = ppt.getSlides().get(page - 1);
            if (slide.getXmlObject().isSetShow() && !slide.getXmlObject().getShow()) throw invalid("隐藏页不参与默认预览和模型外发。");
            Dimension size = ppt.getPageSize(); validateSlideSize(size); Dimension rendered = boundedSize(size.width, size.height);
            var image = new BufferedImage(rendered.width, rendered.height, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try {
                graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, rendered.width, rendered.height);
                graphics.scale((double) rendered.width / size.width, (double) rendered.height / size.height);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                slide.draw(graphics); return png(image);
            } catch (RuntimeException failure) { throw invalid("该页包含当前渲染器不支持的图形，请导出为图片后上传。"); }
            finally { graphics.dispose(); image.flush(); }
        }
    }

    /** 探测真实容器与源时长，对明确片段提取有时间锚点的有限帧，保留未覆盖和无音轨分析边界。 */
    private Decoded video(Path source, UUID assetId, Long requestedStart, Long requestedEnd) throws Exception {
        Result probe = run(List.of(ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe", "-format_whitelist", "mov,matroska,webm",
                "-select_streams", "v:0", "-show_entries", "stream=width,height,codec_name:format=duration,format_name", "-of", "json", source.toString()), 12, 128 * 1024);
        JsonNode info = json.readTree(probe.stdout()); JsonNode stream = info.path("streams").path(0);
        int width = stream.path("width").asInt(), height = stream.path("height").asInt();
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 33_177_600L) throw invalid("视频分辨率超出支持范围，请降低至 4K 或以下。");
        double seconds;
        try { seconds = Double.parseDouble(info.path("format").path("duration").asText()); }
        catch (NumberFormatException failure) { throw invalid("无法确定视频时长，请重新导出为 MP4。"); }
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 24 * 3600) throw invalid("视频时长无效。");
        long duration = Math.round(seconds * 1000), start = requestedStart == null ? 0 : requestedStart;
        long end = requestedEnd == null ? Math.min(duration, start + ImagePromptMediaService.MAX_VIDEO_MS) : requestedEnd;
        if (start < 0 || end <= start || end > duration || end - start > ImagePromptMediaService.MAX_VIDEO_MS) throw invalid("请选择视频内不超过三十秒的时间段。");
        double period = Math.max(0.1, (end - start) / 1000D / 8D);
        // 同一遍解码组合均匀时间采样与场景变化；上限导致后段未覆盖时返回 PARTIAL，不声称完整理解。
        String filter = "select=isnan(prev_selected_t)+gte(t-prev_selected_t\\," + String.format(Locale.ROOT, "%.4f", period)
                + ")+gt(scene\\,0.35),scale=w='min(1600,iw)':h='min(1600,ih)':force_original_aspect_ratio=decrease,showinfo";
        Path output = source.getParent().resolve("frame-%03d.png");
        Result decoded = run(List.of(ffmpeg, "-hide_banner", "-nostdin", "-v", "info", "-threads", "1", "-protocol_whitelist", "file,pipe", "-format_whitelist", "mov,matroska,webm",
                "-ss", decimal(start), "-i", source.toString(), "-t", decimal(end - start), "-map", "0:v:0", "-an", "-sn", "-dn",
                "-vf", filter, "-fps_mode", "vfr", "-frames:v", "12", "-threads", "1", output.toString()), 45, 512 * 1024);
        var timestamps = new ArrayList<Long>(); var matcher = TIMESTAMP.matcher(decoded.stderr());
        while (matcher.find() && timestamps.size() < 12) timestamps.add(start + Math.round(Double.parseDouble(matcher.group(1)) * 1000));
        var units = new ArrayList<Unit>();
        for (int index = 0; index < timestamps.size(); index++) {
            interrupted(); Path frame = source.getParent().resolve(String.format(Locale.ROOT, "frame-%03d.png", index + 1));
            if (!Files.isRegularFile(frame)) break;
            BufferedImage image = readImage(frame); UUID unitId = UUID.randomUUID();
            try { Files.write(source.getParent().resolve(unitId + ".png"), png(image), StandardOpenOption.CREATE_NEW);
                units.add(unit(assetId, unitId, null, timestamps.get(index), image.getWidth(), image.getHeight(), "")); }
            finally { image.flush(); Files.deleteIfExists(frame); }
        }
        if (units.isEmpty()) throw invalid("视频没有可解码的视频帧，请重新导出后上传。");
        boolean partial = units.size() == 12 && units.getLast().timestampMs() < end - Math.round(period * 1500);
        var warnings = new ArrayList<String>();
        warnings.add("仅采样源视频 " + start + "–" + end + " ms 片段，共 " + units.size() + " 帧；音频未发送或识别。");
        warnings.add("静态采样不等于完整运动理解，镜头节奏仅限有时间戳的采样证据。");
        if (partial) warnings.add("镜头变化较多，十二帧预算用尽，片段后部未完整覆盖；可缩短时间段后重新上传。");
        return new Decoded(List.copyOf(units), List.copyOf(warnings), units.size(), duration, partial);
    }

    /** 在 POI 打开前检查 ZIP 展开预算、嵌入执行对象及外部资源，避免渲染阶段主动访问网络。 */
    private void checkPackage(Path source) throws Exception {
        long total = 0; int entries = 0;
        try (ZipFile zip = new ZipFile(source.toFile())) {
            var all = zip.entries();
            while (all.hasMoreElements()) {
                interrupted(); ZipEntry entry = all.nextElement(); if (++entries > 5000) throw invalid("PPTX 包含过多内部文件。");
                if (entry.isDirectory()) continue;
                String name = entry.getName().toLowerCase(Locale.ROOT);
                // 普通图表可携带内部 XLSX 数据源；它只作为受限包资源存在，本流程只读图表缓存、不打开工作簿。
                // 宏、ActiveX 和 OLE/其他内嵌对象不参与静态幻灯片解析，不能误把常见图表文件一并拒绝。
                if (name.contains("vbaproject") || name.startsWith("ppt/activex/")
                        || name.startsWith("ppt/embeddings/") && !name.endsWith(".xlsx")) throw invalid("请移除宏、ActiveX 或 OLE 内嵌对象后上传 PPTX。");
                if (entry.getSize() > 20L * 1024 * 1024) throw invalid("PPTX 内部资源过大，请压缩图片后重试。");
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] data = input.readNBytes(20 * 1024 * 1024 + 1); total += data.length;
                    if (data.length > 20 * 1024 * 1024 || total > 100L * 1024 * 1024) throw invalid("PPTX 展开后超过安全资源预算。");
                    if (name.endsWith(".rels")) checkRelationships(data);
                    if (name.startsWith("ppt/media/") && (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg"))) validateImageHeader(data);
                }
            }
        }
    }

    /** 关系 XML 禁用 DTD 与实体，仅允许不会触发资源加载的普通文字超链接。 */
    private void checkRelationships(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false); factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)); var relationships = document.getElementsByTagName("Relationship");
        for (int index = 0; index < relationships.getLength(); index++) {
            var node = relationships.item(index); var external = node.getAttributes().getNamedItem("TargetMode"); var type = node.getAttributes().getNamedItem("Type");
            if (type != null && (type.getNodeValue().endsWith("/oleObject") || type.getNodeValue().endsWith("/control")))
                throw invalid("PPTX 含 OLE 或 ActiveX 对象，请移除后上传。");
            if (external != null && external.getNodeValue().equalsIgnoreCase("External") && (type == null || !type.getNodeValue().endsWith("/hyperlink")))
                throw invalid("PPTX 含外部链接资源，请将资源嵌入页面或导出图片后上传。");
        }
    }

    /** 结构摘录保留元素名称、位置、文字与表格/图表缓存来源，递归和字符量均有界。 */
    private void appendShapes(List<XSLFShape> shapes, StringBuilder output, int depth) {
        if (depth > 8 || output.length() >= 4000) return;
        for (XSLFShape shape : shapes) {
            if (output.length() >= 4000) break;
            output.append("元素 ").append(shape.getShapeName()).append(' ');
            if (shape instanceof XSLFSimpleShape simple) { Rectangle2D box = simple.getAnchor(); output.append("位置/尺寸(pt): ").append(Math.round(box.getX())).append(',').append(Math.round(box.getY())).append(',').append(Math.round(box.getWidth())).append(',').append(Math.round(box.getHeight())).append(' '); }
            if (shape instanceof XSLFTextShape text) output.append(limit(text.getText(), 2000));
            else if (shape instanceof XSLFTable table) for (var row : table.getRows()) { for (var cell : row.getCells()) output.append(limit(cell.getText(), 500)).append(" | "); output.append('\n'); }
            else if (shape instanceof XSLFGroupShape group) appendShapes(group.getShapes(), output, depth + 1);
            else if (shape instanceof XSLFPictureShape) output.append("嵌入图片，需结合该页视觉分析。");
            else if (shape instanceof XSLFGraphicFrame frame && frame.hasChart()) appendChart(frame, output);
            else output.append("图形，内容以实际可见页面为准。");
            output.append('\n');
        }
    }

    /** 读取图表已缓存的类别与数值，不执行公式、不打开内嵌工作簿，也不从图形高度猜测数字。 */
    private void appendChart(XSLFGraphicFrame frame, StringBuilder output) {
        output.append("图表缓存（可能不是工作簿最新值，按源文件实际缓存保留）：\n");
        try {
            int seriesNumber = 0;
            for (var chartData : frame.getChart().getChartSeries()) {
                for (var series : chartData.getSeries()) {
                    if (++seriesNumber > 20 || output.length() >= 4000) return;
                    output.append("序列 ").append(seriesNumber).append("：");
                    var categories = series.getCategoryData(); var values = series.getValuesData();
                    int points = Math.min(100, Math.min(categories.getPointCount(), values.getPointCount()));
                    for (int index = 0; index < points && output.length() < 4000; index++) {
                        Object category = categories.getPointAt(index), value = values.getPointAt(index);
                        output.append(category == null ? "未缓存类别" : limit(category.toString(), 100)).append(" = ")
                                .append(value == null ? "未缓存数值" : limit(value.toString(), 100)).append("；");
                    }
                    output.append('\n');
                }
            }
            if (seriesNumber == 0) output.append("无可读缓存，数值保持未知，需核对原始资料。");
        } catch (RuntimeException unsupported) {
            output.append("当前图表类型的缓存不可读取，仅结合页面视觉分析，不补造数值。");
        }
    }

    /** 在解码前验证像素上限，解码后校正方向、缩小并铺白底；透明区域不作为脱敏遮挡。 */
    private BufferedImage readImage(Path source) throws IOException {
        byte[] bytes = Files.readAllBytes(source); validateImageHeader(bytes);
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(bytes));
        if (original == null) throw invalid("无法解码图片。");
        original = ImageOrientation.normalize(original, ImageOrientation.read(bytes)); Dimension size = boundedSize(original.getWidth(), original.getHeight());
        var result = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB); var graphics = result.createGraphics();
        try { graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, size.width, size.height); graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); graphics.drawImage(original, 0, 0, size.width, size.height, null); }
        finally { graphics.dispose(); original.flush(); }
        return result;
    }

    /** 图片签名和尺寸来自实际解码器头信息，阻止压缩图片在 read 阶段分配过大内存。 */
    private void validateImageHeader(byte[] bytes) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input); if (!readers.hasNext()) throw invalid("嵌入图片实际格式不受支持。");
            var reader = readers.next();
            try { reader.setInput(input); String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Set.of("jpeg", "jpg", "png").contains(format)) throw invalid("仅支持真实 JPEG 或 PNG 图片。");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 15_000_000L) throw invalid("图片超过 1500 万像素或单边 8192 像素上限。");
            } finally { reader.dispose(); }
        }
    }

    private Unit unit(UUID assetId, UUID unitId, Integer page, Long timestamp, int width, int height, String text) {
        return new Unit(unitId, page, timestamp, width, height, text, "/api/v1/image-prompt-media/" + assetId + "/units/" + unitId);
    }
    private Dimension boundedSize(int width, int height) { double scale = Math.min(1D, (double) MAX_EDGE / Math.max(width, height)); return new Dimension(Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))); }
    private void validateSlideSize(Dimension size) { if (size.width <= 0 || size.height <= 0 || size.width > 20000 || size.height > 20000) throw invalid("幻灯片画布尺寸超出支持范围。"); }
    private byte[] png(BufferedImage image) throws IOException { var output = new ByteArrayOutputStream(); if (!ImageIO.write(image, "png", output) || output.size() > 10 * 1024 * 1024) throw invalid("规范化图片超过 10 MiB。"); return output.toByteArray(); }
    private String decimal(long millis) { return String.format(Locale.ROOT, "%.3f", millis / 1000D); }
    private String limit(String value, int limit) { return value == null ? "" : value.substring(0, Math.min(value.length(), limit)); }
    private void interrupted() { if (Thread.currentThread().isInterrupted()) throw invalid("素材解析已取消。"); }
    private ImagePromptException invalid(String text) { return ImagePromptException.invalid(text); }

    /** 子进程不经 shell，输出与耗时有界；中断或超时会强制终止其整个子进程树。 */
    private Result run(List<String> command, int timeoutSeconds, int outputLimit) throws Exception {
        Process process = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.PIPE).start(); process.getOutputStream().close();
        var stdout = CompletableFuture.supplyAsync(() -> readBounded(process.getInputStream(), outputLimit));
        var stderr = CompletableFuture.supplyAsync(() -> readBounded(process.getErrorStream(), outputLimit));
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw invalid("视频解析超时，请缩短片段或降低分辨率。");
            String out = stdout.get(2, TimeUnit.SECONDS), err = stderr.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw invalid("视频解码失败，请导出为标准 MP4 后上传。");
            return new Result(out, err);
        } finally {
            process.descendants().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly(); stdout.cancel(true); stderr.cancel(true);
        }
    }

    private String readBounded(InputStream input, int limit) {
        try (input) { byte[] bytes = input.readNBytes(limit + 1); if (bytes.length > limit) throw invalid("视频解析输出超出预算，请简化片段后重试。"); return new String(bytes, StandardCharsets.UTF_8); }
        catch (IOException failure) { throw new CompletionException(failure); }
    }
    public record Decoded(List<Unit> units, List<String> warnings, int totalUnits, Long durationMs, boolean partial) { }
    private record Result(String stdout, String stderr) { }
}
