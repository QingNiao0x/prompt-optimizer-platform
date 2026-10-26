package com.promptoptimizer.imageprompt.dto;

import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.MaterialRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

/**
 * 图像业务输入只接收资源引用和用户要求，不接受所有者、模型端点、服务端分析或确认状态。
 * @author QingNiao
 * @since 0.1.0
 */
public final class ImagePromptRequests {
    private ImagePromptRequests() { }
    public record Reference(@NotNull UUID assetId, @NotNull ImageRole role) { }
    public record Task(@NotNull Mode mode, @NotBlank @Size(max = 160) String modelId,
                       @NotNull @Size(max = 3) List<@NotNull @Valid Reference> images,
                       @NotNull @Size(max = 6000) String creationIntent,
                       @NotNull @Size(max = 2000) String exactText,
                       @NotNull @Size(max = 6000) String context,
                       @NotNull Language language, @NotNull @Size(max = 100) String targetTool,
                       OutputType outputType, @Size(max = 6) List<@NotNull @Valid MaterialRef> materials,
                       @Size(max = 12) List<@NotNull ReplacementRelation> relations) {
        /** 保留原有单图调用与测试夹具；新增字段由Input统一提供历史兼容默认值。 */
        public Task(Mode mode, String modelId, List<Reference> images, String creationIntent,
                    String exactText, String context, Language language, String targetTool) {
            this(mode, modelId, images, creationIntent, exactText, context, language, targetTool, null, null, null);
        }
        /** 普通描述只去除外围空白，确切画面文案保留原字符；事实与权限仍由应用服务核对。 */
        public Input input() {
            return new Input(mode, modelId.trim(), images.stream().map(i -> new ImageRef(i.assetId(), i.role())).toList(),
                    creationIntent.strip(), exactText, context.strip(), language, targetTool.strip(), outputType, materials, relations);
        }
    }
    public record Generate(@NotNull UUID requestId, @NotNull @Valid Task input) { }
    public record Reply(@NotBlank @Size(max = 40) String questionId,
                        @NotNull @Size(max = 12) List<@NotBlank @Size(max = 40) String> optionIds,
                        @NotNull @Size(max = 2000) String customText) {
        /** 选择与补充文字同时保留，不能用其中之一覆盖另一项。 */
        public Answer answer() { return new Answer(questionId, List.copyOf(optionIds), customText.strip()); }
    }
    public record Confirm(@NotNull UUID requestId, @NotBlank @Size(max = 64) String fingerprint,
                          @NotNull @Size(max = 8) List<@NotNull @Valid Reply> answers) { }
    public record Edit(@NotNull @Size(min = 1, max = 30000) String optimizedPrompt, @Min(0) long expectedRevision) { }
    public record Rewrite(@NotNull UUID requestId, @Min(0) long expectedRevision,
                          @NotBlank @Size(max = 3000) String instruction) { }
    public record Capability(@NotNull Boolean enabled, @Min(1) @Max(3) int maxImages,
                             @NotBlank @Size(max = 400) String evidence) { }
}
