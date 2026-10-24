package com.promptoptimizer.imageprompt.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 图像转提示词的专用协议。业务模式与文本结果形态独立，观察、创作要求及实际题答分别保存。
 * 所有资产与记录标识都须经服务端身份校验，随机标识不构成授权。
 * @author QingNiao
 * @since 0.1.0
 */
public final class ImagePromptModels {
    private ImagePromptModels() { }

    /** 还原模式只描述参考图；创作模式以用户构想和实际题答为依据，参考图可为空。 */
    public enum Mode { REFERENCE_RECONSTRUCTION, CUSTOM_CREATION }
    /** 参考角色限制单张图的用途，不代表用户授权复制该图所有内容。 */
    public enum ImageRole { GENERAL, SUBJECT, STYLE, LAYOUT }
    /** 提取/提问/最终生成/文字修订为不同阶段，修订不冒充再次识图。 */
    public enum Stage { EXTRACT, PLAN, GENERATE, REWRITE }
    public enum Language { ZH, EN }
    public enum QuestionType { SINGLE_CHOICE, MULTIPLE_CHOICE, TEXT }
    public enum Visibility { OBSERVED, INTERPRETATION, UNCERTAIN }

    /** 仅返回当前身份可用的内部预览地址；原图不会被加入历史正文。 */
    public record Asset(UUID id, String name, String mimeType, int width, int height,
                        long bytes, String hash, Instant expiresAt, String previewUrl, List<String> processing) { }
    public record ImageRef(UUID assetId, ImageRole role) { }
    /** 历史只保留必要图片元数据，过期后不能据此还原图片字节。 */
    public record ImageSource(UUID id, String name, String hash, ImageRole role, int width, int height, Instant expiresAt) { }
    public record Observation(UUID imageId, String category, String description, Visibility status) { }
    public record Option(String id, String label) { }
    public record Question(String id, QuestionType type, String question, List<Option> options, boolean required) { }
    /** 自定义文字可以与选项共同提交；不得以覆盖其中之一的方式丢失用户回答。 */
    public record Answer(String questionId, List<String> optionIds, String customText) { }

    /** 服务端规范化的本次输入。context 仅为用户主动选入的文字摘要，不自动读取项目源码。 */
    public record Input(Mode mode, String modelId, List<ImageRef> images, String creationIntent,
                        String exactText, String context, Language language, String targetTool) { }
    public record ProviderDraft(String summary, String optimizedPrompt, List<Observation> analysis,
                                List<Question> questions, List<String> warnings) { }
    public record ProviderResult(ProviderDraft draft, String modelId, String modelName, boolean mock) { }
    public record Plan(UUID id, String fingerprint, String summary, List<Question> questions,
                       List<Observation> analysis, List<String> warnings, Instant expiresAt, boolean mock) { }
    /** 生成正文与历史保存状态分别返回；保存失败仍可按 operationId 重试，不能再次调用模型。 */
    public record Document(UUID operationId, UUID recordId, Mode mode, String optimizedPrompt,
                           List<Observation> analysis, List<String> warnings, Input input,
                           List<ImageSource> sources, List<Question> questions, List<Answer> answers,
                           String modelId, String modelName, boolean mock, long editRevision,
                           boolean historySaved, boolean persistentHistory, Instant createdAt) {
        /** 只替换正文和修订号；用户编辑不改变原始输入、观察来源或创作模式。 */
        public Document edited(String text, long revision) {
            return new Document(operationId, recordId, mode, text, analysis, warnings, input, sources, questions,
                    answers, modelId, modelName, mock, revision, historySaved, persistentHistory, createdAt);
        }
        /** 历史保存失败后重试复用同一生成结果与记录标识。 */
        public Document saved(UUID id, boolean persistent) {
            return new Document(operationId, id, mode, optimizedPrompt, analysis, warnings, input, sources,
                    questions, answers, modelId, modelName, mock, editRevision, true, persistent, createdAt);
        }
    }
    public record Model(String id, String displayName, boolean vision, int maxImages, String evidence, boolean mock) { }
    public record Capabilities(List<Model> models, long maxImageBytes, long maxPixels, int maxEdge,
                               int maxImages, long assetTtlSeconds, boolean persistentHistory) { }
    public record HistoryPage(List<Document> records, long total, long size, long current, long pages) { }
    /** 模型视觉能力由管理员显式登记；不依据名称或供应商品牌推断。 */
    public record VisionCapability(String modelId, boolean enabled, int maxImages, String evidence) { }
}
