package com.promptoptimizer.imageprompt.domain;

import com.promptoptimizer.imageprompt.domain.ImagePromptModels.ImageRole;
import com.promptoptimizer.imageprompt.service.ImagePromptProvider.ImageData;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 多模态素材的临时解析与来源协议；上传到平台不代表已授权模型外发。
 * 标识必须在服务端完整身份范围内解析，历史只保存选取范围和来源说明，不保存原件。
 * @author QingNiao
 * @since 0.1.0
 */
public final class ImagePromptMediaModels {
    private ImagePromptMediaModels() { }

    /** IMAGE 为真实 JPEG/PNG；VIDEO 为受控解码片段；PPTX 仅处理可见静态幻灯片。 */
    public enum Kind { IMAGE, VIDEO, PPTX }
    /** 参考素材提供借鉴内容，替换素材提供最终希望出现的新内容。 */
    public enum Track { REFERENCE, REPLACEMENT }
    /** PARTIAL 说明只形成部分派生证据，不能作为全文/全片已理解的证明。 */
    public enum Status { PROCESSING, READY, PARTIAL, FAILED, CANCELLED }

    /** 矩形坐标相对于规范化图片，范围为 0..1；外发时填充真正的不透明黑色像素。 */
    public record Mask(double x, double y, double width, double height) { }
    public record UnitSelection(UUID unitId, List<Mask> masks) { }
    public record Export(List<Mask> masks) { }
    public record MaterialRef(UUID assetId, Track track, ImageRole role, List<UnitSelection> units) { }
    /** page 从 1 起算；timestampMs 是源视频中的毫秒位置；二者对普通图片均为空。 */
    public record Unit(UUID id, Integer page, Long timestampMs, int width, int height, String text, String previewUrl) { }
    public record MediaAsset(UUID id, String name, Kind kind, Status status, List<Unit> units,
                             List<String> warnings, Instant expiresAt, int totalUnits, Long durationMs) { }
    public record Capabilities(int maxAssets, long maxFileBytes, int maxSelectedUnits,
                               long maxVideoDurationMs, int maxSlides, boolean videoAvailable) { }
    /** 已确认选区的来源快照；存在遮挡时 text 必须为空，避免结构原文绕过像素遮挡。 */
    public record SourceUnit(UUID id, Integer page, Long timestampMs, String text, List<Mask> masks) { }
    public record MaterialSource(UUID assetId, String name, Kind kind, Track track, ImageRole role,
                                 List<SourceUnit> units, List<String> warnings) { }
    public record Resolved(List<ImageData> images, List<MaterialSource> sources, List<String> warnings) { }
    /** 短期缓存含撤销标记，用户撤销立即失去访问权，物理清理由临时保留期任务负责。 */
    public record StoredMedia(MediaAsset asset, boolean revoked) { }
}
