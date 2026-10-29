package com.promptoptimizer.imageprompt.domain;

import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.Track;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 非视觉资料的临时证据协议；来源文字不是用户指令，文件夹只表示主动提交的文件集合。
 * 所有引用按服务端用户、租户和工作区解析，历史只保留实际选中的脱敏片段。
 * @author QingNiao
 * @since 0.1.0
 */
public final class ImagePromptSourceModels {
    private ImagePromptSourceModels() { }
    /** 用户选择的下游任务方向；不依据文件扩展名替代用户决定交付物。 */
    public enum TaskType { CODE, DOCUMENT, OTHER }
    /** 本地提取所得资料类别；COLLECTION只表示本次主动选择的文件集合，不代表完整项目。 */
    public enum Kind { TEXT, CODE, DOCUMENT, COLLECTION }
    /** 资料用途与事实状态分开；角色不授权执行资料内命令，也不代表要求复制全部内容。 */
    public enum Role { BASELINE, REFERENCE, FACTS, CONSTRAINTS, REPLACEMENT }
    /** 准备完成或仅部分可用；模型尚未调用，PARTIAL必须同时保留具体覆盖警告。 */
    public enum PreparationStatus { READY, PARTIAL }
    /** 脱敏后的纯文字证据单元，路径和标签用于定位，不是平台读取本机路径的指令。 */
    public record Unit(UUID id, String path, String label, String text) { }
    public record Asset(UUID id, String name, Kind kind, PreparationStatus status, List<Unit> units,
                        List<String> warnings, Instant expiresAt, int totalUnits) { }
    public record Reference(@NotNull UUID assetId, @NotEmpty @Size(max = 40) List<@NotNull UUID> unitIds,
                            @NotNull Track track, @NotNull Role role) { }
    public record Task(@NotNull TaskType taskType, @NotNull @Size(max = 20) List<@NotNull @Valid Reference> sources,
                       UUID consentId) { }
    public record Entry(@NotBlank @Size(max = 300) String path, @NotNull @Size(max = 500000) String content) { }
    public record TextUpload(@NotBlank @Size(max = 160) String name,
                             @NotEmpty @Size(max = 5000) List<@NotNull @Valid Entry> entries) { }
    public record Source(UUID assetId, String name, Kind kind, Track track, Role role,
                         List<Unit> units, List<String> warnings) { }
    public record Consent(UUID id, Instant expiresAt) { }
    public record Capabilities(int maxAssets, long maxFileBytes, int maxSelectedUnits,
                               int maxSelectedCharacters, int maxEntries, List<String> extensions) { }
    public record Stored(Asset asset, boolean revoked) { }
    public record BoundConsent(String fingerprint, Instant expiresAt) { }
}
