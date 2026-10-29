package com.promptoptimizer.imageprompt.domain;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.MaterialRef;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.MaterialSource;

/**
 * 图像转提示词的专用协议。业务模式与文本结果形态独立，观察、创作要求及实际题答分别保存。
 * 所有资产与记录标识都须经服务端身份校验，随机标识不构成授权。
 * @author QingNiao
 * @since 0.1.0
 */
public final class ImagePromptModels {
    private ImagePromptModels() { }

    /** 还原模式只描述主动选中的参考图片、页面或视频帧，不发起问答；创作模式以用户构想和实际题答为依据，参考与替换素材可为空。 */
    public enum Mode { REFERENCE_RECONSTRUCTION, CUSTOM_CREATION }
    /** 参考角色限制单张图的用途，不代表用户授权复制该图所有内容。 */
    public enum ImageRole { GENERAL, SUBJECT, STYLE, LAYOUT }
    /** 提取/素材识别/提问/最终生成/文字修订为不同阶段，修订不冒充再次识图。 */
    public enum Stage { EXTRACT, ANALYZE, PLAN, GENERATE, REWRITE }
    /** 成品类型与上传格式独立；还原模式沿用IMAGE兼容值且不创作新成品，创作模式按所选类型编写提示词。 */
    public enum OutputType { IMAGE, VIDEO, PPT }
    /** 属性级操作允许保留位置同时替换外观，不把素材用途误作整图复制授权。 */
    public enum RelationOperation { PRESERVE, REPLACE, REMOVE, FUSE }
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
                        String exactText, String context, Language language, String targetTool,
                        OutputType outputType, List<MaterialRef> materials, List<ReplacementRelation> relations, ImagePromptSourceModels.Task sourceTask) {
        /** 旧请求与已保存历史缺少新字段时，按原来的图片任务读取。 */
        public Input {
            outputType = outputType == null ? OutputType.IMAGE : outputType;
            materials = materials == null ? List.of() : List.copyOf(materials);
            relations = relations == null ? List.of() : List.copyOf(relations);
        }
        /** 旧视觉输入不携带通用资料任务，维持原构造及JSON语义。 */
        public Input(Mode mode, String modelId, List<ImageRef> images, String creationIntent,
                     String exactText, String context, Language language, String targetTool,
                     OutputType outputType, List<MaterialRef> materials, List<ReplacementRelation> relations) {
            this(mode, modelId, images, creationIntent, exactText, context, language, targetTool, outputType, materials, relations, null);
        }
        public Input(Mode mode, String modelId, List<ImageRef> images, String creationIntent,
                     String exactText, String context, Language language, String targetTool) {
            this(mode, modelId, images, creationIntent, exactText, context, language, targetTool, OutputType.IMAGE, List.of(), List.of());
        }
    }
    /** 用户明确输入的关系；单位编号可定位图片区域、PPT页面或视频帧，不接受客户端伪造模型确认。 */
    public record ReplacementRelation(String id, RelationOperation operation, UUID referenceAssetId, UUID referenceUnitId,
                                      String referenceObject, UUID replacementAssetId, UUID replacementUnitId,
                                      String replacementObject, String instruction, List<String> attributes, String scope) { }
    public record ProviderDraft(String summary, String optimizedPrompt, List<Observation> analysis,
                                List<Question> questions, List<String> warnings) { }
    public record ProviderResult(ProviderDraft draft, String modelId, String modelName, boolean mock, int attempts) {
        public ProviderResult(ProviderDraft draft, String modelId, String modelName, boolean mock) { this(draft, modelId, modelName, mock, 1); }
    }
    public record Plan(UUID id, String fingerprint, String summary, List<Question> questions,
                       List<Observation> analysis, List<String> warnings, Instant expiresAt, boolean mock,
                       List<MaterialSource> materialSources, List<ImagePromptSourceModels.Source> sourceSources) {
        public Plan {
            materialSources = materialSources == null ? List.of() : List.copyOf(materialSources);
            sourceSources = sourceSources == null ? List.of() : List.copyOf(sourceSources);
        }
        public Plan(UUID id, String fingerprint, String summary, List<Question> questions,
                    List<Observation> analysis, List<String> warnings, Instant expiresAt, boolean mock, List<MaterialSource> materialSources) {
            this(id, fingerprint, summary, questions, analysis, warnings, expiresAt, mock, materialSources, List.of());
        }
        public Plan(UUID id, String fingerprint, String summary, List<Question> questions,
                    List<Observation> analysis, List<String> warnings, Instant expiresAt, boolean mock) {
            this(id, fingerprint, summary, questions, analysis, warnings, expiresAt, mock, List.of());
        }
    }
    /** 来源风险只按已知素材元数据和UNCERTAIN观察恢复；用于旧JSON缺少阶段风险字段时的有限兼容，不分类混合警告文案。 */
    public static List<String> knownEvidenceWarnings(List<MaterialSource> sources, List<Observation> analysis) {
        var risks = new ArrayList<String>();
        sources.forEach(source -> risks.addAll(source.warnings()));
        analysis.stream().filter(observation -> observation.status() == Visibility.UNCERTAIN)
                .forEach(observation -> risks.add(observation.description()));
        return risks.stream().filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct().toList();
    }

    /** 生成正文与历史保存状态分别返回；evidenceWarnings单独保存来源风险，warnings仍为本次可展示的合并提示。 */
    public record Document(UUID operationId, UUID recordId, Mode mode, String optimizedPrompt,
                           List<Observation> analysis, List<String> warnings, Input input,
                           List<ImageSource> sources, List<Question> questions, List<Answer> answers,
                           String modelId, String modelName, boolean mock, long editRevision,
                           boolean historySaved, boolean persistentHistory, Instant createdAt, List<MaterialSource> materialSources,
                           List<String> evidenceWarnings, List<ImagePromptSourceModels.Source> sourceSources) {
        /** 缺字段/null表示旧协议，只恢复可确定来源风险；显式空数组表示来源阶段未报告风险，不从混合warnings猜测。 */
        public Document {
            sourceSources = sourceSources == null ? List.of() : List.copyOf(sourceSources);
            materialSources = materialSources == null ? List.of() : List.copyOf(materialSources);
            evidenceWarnings = evidenceWarnings == null ? knownEvidenceWarnings(materialSources, analysis) : List.copyOf(evidenceWarnings);
        }
        /** 兼容原视觉结果，来源快照缺省为空。 */
        public Document(UUID operationId, UUID recordId, Mode mode, String optimizedPrompt,
                        List<Observation> analysis, List<String> warnings, Input input,
                        List<ImageSource> sources, List<Question> questions, List<Answer> answers,
                        String modelId, String modelName, boolean mock, long editRevision,
                        boolean historySaved, boolean persistentHistory, Instant createdAt, List<MaterialSource> materialSources,
                        List<String> evidenceWarnings) {
            this(operationId, recordId, mode, optimizedPrompt, analysis, warnings, input, sources, questions, answers,
                    modelId, modelName, mock, editRevision, historySaved, persistentHistory, createdAt, materialSources, evidenceWarnings, List.of());
        }
        /** 只加入实际选中的脱敏资料快照，不把临时全文放入历史。 */
        public Document withSourceSources(List<ImagePromptSourceModels.Source> references) {
            return new Document(operationId, recordId, mode, optimizedPrompt, analysis, warnings, input, sources, questions,
                    answers, modelId, modelName, mock, editRevision, historySaved, persistentHistory, createdAt, materialSources, evidenceWarnings, references);
        }
        /** 老版本调用方与历史JSON仍保留原构造语义。 */
        public Document(UUID operationId, UUID recordId, Mode mode, String optimizedPrompt,
                        List<Observation> analysis, List<String> warnings, Input input,
                        List<ImageSource> sources, List<Question> questions, List<Answer> answers,
                        String modelId, String modelName, boolean mock, long editRevision,
                        boolean historySaved, boolean persistentHistory, Instant createdAt) {
            this(operationId, recordId, mode, optimizedPrompt, analysis, warnings, input, sources, questions, answers,
                    modelId, modelName, mock, editRevision, historySaved, persistentHistory, createdAt, List.of());
        }
        /** 兼容已有多模态JSON与调用方；新应用服务使用带独立来源风险的完整构造。 */
        public Document(UUID operationId, UUID recordId, Mode mode, String optimizedPrompt,
                        List<Observation> analysis, List<String> warnings, Input input,
                        List<ImageSource> sources, List<Question> questions, List<Answer> answers,
                        String modelId, String modelName, boolean mock, long editRevision,
                        boolean historySaved, boolean persistentHistory, Instant createdAt, List<MaterialSource> materialSources) {
            this(operationId, recordId, mode, optimizedPrompt, analysis, warnings, input, sources, questions, answers,
                    modelId, modelName, mock, editRevision, historySaved, persistentHistory, createdAt, materialSources, null);
        }
        /** 只替换正文和修订号；用户编辑不改变原始输入、观察来源或创作模式。 */
        public Document edited(String text, long revision) {
            return new Document(operationId, recordId, mode, text, analysis, warnings, input, sources, questions,
                    answers, modelId, modelName, mock, revision, historySaved, persistentHistory, createdAt, materialSources, evidenceWarnings, sourceSources);
        }
        /** 历史保存失败后重试复用同一生成结果与记录标识。 */
        public Document saved(UUID id, boolean persistent) {
            return new Document(operationId, id, mode, optimizedPrompt, analysis, warnings, input, sources,
                    questions, answers, modelId, modelName, mock, editRevision, true, persistent, createdAt, materialSources, evidenceWarnings, sourceSources);
        }
    }
    public record Model(String id, String displayName, boolean vision, int maxImages, String evidence, boolean mock) { }
    public record Capabilities(List<Model> models, long maxImageBytes, long maxPixels, int maxEdge,
                               int maxImages, long assetTtlSeconds, boolean persistentHistory) { }
    public record HistoryPage(List<Document> records, long total, long size, long current, long pages) { }
    /** 模型视觉能力由管理员显式登记；不依据名称或供应商品牌推断。 */
    public record VisionCapability(String modelId, boolean enabled, int maxImages, String evidence) { }
}
