package com.promptoptimizer.textpolish.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.Instant;
import java.util.*;

/**
 * 文章润色的独立契约；正文是待改写材料，不是平台指令或优化提示词。
 * @author QingNiao
 * @since 0.1.0
 */
public final class TextPolishModels {
    private TextPolishModels() { }
    /** 三种文体平行，NATURAL为缺省；不表示质量或检测通过率。 */
    public enum Style {
        FORMAL, NATURAL, CONVERSATIONAL;
        /** 只接受公开代码，数字序号不能伪装成文体。 */
        @JsonCreator public static Style from(String value) { return value == null ? NATURAL : valueOf(value); }
    }
    /** LIGHT保守修改，RESTRUCTURE允许调整句式和段落；事实保护优先于两者。 */
    public enum Depth {
        LIGHT, RESTRUCTURE;
        /** 与前端有限代码一致，非法值在模型调用前拒绝。 */
        @JsonCreator public static Depth from(String value) { return value == null ? LIGHT : valueOf(value); }
    }
    /** RAW首次输入；ORIGINAL沿用来源原文；CURRENT基于服务端已保存的当前稿。 */
    public enum SourceMode {
        RAW, ORIGINAL, CURRENT;
        /** 不允许数字序号或未知来源方式回退到RAW。 */
        @JsonCreator public static SourceMode from(String value) { return value == null ? RAW : valueOf(value); }
    }
    /** 输入和用户要求单独承载；锁定文本必须实际出现于本次输入，不能继承不匹配的旧锁定。 */
    public record Input(@NotBlank @Size(max = 12000) String sourceText, Style style, Depth depth,
                        @Size(max = 300) String audience, @Size(max = 1500) String requirements,
                        @Size(max = 20) List<@NotBlank @Size(max = 1000) String> lockedTexts,
                        @Size(max = 160) String modelId) {
        public Input {
            style = style == null ? Style.NATURAL : style;
            depth = depth == null ? Depth.LIGHT : depth;
            audience = audience == null ? "" : audience;
            requirements = requirements == null ? "" : requirements;
            lockedTexts = lockedTexts == null ? List.of() : List.copyOf(lockedTexts);
            modelId = modelId == null ? "" : modelId;
        }
    }
    /** requestId只标识幂等操作；所有者由CurrentActor决定，来源版本由服务端读取。 */
    public record Generate(@NotNull UUID requestId, @NotNull @Valid Input input,
                           UUID sourceRecordId, @PositiveOrZero Long expectedRevision, SourceMode sourceMode) {
        public Generate { sourceMode = sourceMode == null ? SourceMode.RAW : sourceMode; }
    }
    /** 保存编辑稿的乐观锁请求，失败不能覆盖客户端草稿。 */
    public record Edit(@NotBlank @Size(max = 24000) String polishedText, @Min(0) long expectedRevision) { }
    /** 局部差异线索，无评分或语义正确性承诺；before/after为有限长度的可查看片段。 */
    public record Check(String code, String message, String before, String after) { }
    /** 实际发送的传输参数；null表示未发送，thinking为null时供应商默认行为未知。 */
    public record ExecutionParameters(Integer maxTokens, Integer maxCompletionTokens, Double temperature,
                                      boolean stream, String responseFormat, String thinking) { }
    /** 正文语言；UNKNOWN表示证据不足或当前无专门资源的语言。 */
    public enum Language { ZH, EN, MIXED, UNKNOWN }
    /** 服务端选择快照；GENERAL表示用途由编辑模型结合全文判断，reason为有限依据代码。 */
    public record Selection(String selectorVersion, Language language, String profile, String reason) { }
    /** 内置资源身份；sha256为实际加载的规范化UTF-8正文哈希，不含文件头。 */
    public record Resource(String id, String version, String sha256) { }
    /**
     * 服务端生成的执行快照，随text_polish_record.payload持久化，继承记录的用户/租户/工作区范围。
     * resourceHash为基础system指令UTF-8的SHA-256；repairInstructionHash仅在协议修复时记录。
     * skillVersion为空表示未加载Skill；旧记录整体为null，不能推定使用当前策略。路由只存标识，不存端点或密钥。
     * parameters记录实际发送值，供应商未显式配置的默认行为不作推断；attemptCount包含协议修复。
     * selection与resources来自首次冻结的装配结果；旧记录缺失时为null，禁止按当前配置补算。
     */
    public record ExecutionMetadata(String strategyVersion, String skillVersion, String resourceHash,
                                    String repairInstructionHash, String routeKey, String upstreamModel,
                                    ExecutionParameters parameters, int attemptCount,
                                    Selection selection, List<Resource> resources) {
        /** 资源清单冻结为不可变副本；null保留旧记录的未知状态。 */
        public ExecutionMetadata { resources = resources == null ? null : List.copyOf(resources); }
        /** 兼容旧适配器及历史夹具，未采集的选择信息保持未知。 */
        public ExecutionMetadata(String strategyVersion, String skillVersion, String resourceHash,
                                 String repairInstructionHash, String routeKey, String upstreamModel,
                                 ExecutionParameters parameters, int attemptCount) {
            this(strategyVersion, skillVersion, resourceHash, repairInstructionHash, routeKey, upstreamModel,
                    parameters, attemptCount, null, null);
        }
    }
    /** Provider返回正文及与该次实际请求绑定的元数据；兼容适配器的元数据可以未知。 */
    public record Generation(String text, ExecutionMetadata executionMetadata) { }
    /** text_polish_record.payload的不可变生成快照；正文和修订读取时由独立列覆盖。 */
    public record Document(UUID id, Input input, String generatedText, String polishedText, UUID sourceRecordId,
                           SourceMode sourceMode, String modelId, String modelName, boolean mock, long editRevision,
                           List<Check> checks, boolean historySaved, boolean persistentHistory, Instant createdAt, ExecutionMetadata executionMetadata) {
        /** 兼容既有调用方；历史缺少执行信息时保留未知。 */
        public Document(UUID id, Input input, String generatedText, String polishedText, UUID sourceRecordId,
                        SourceMode sourceMode, String modelId, String modelName, boolean mock, long editRevision,
                        List<Check> checks, boolean historySaved, boolean persistentHistory, Instant createdAt) {
            this(id, input, generatedText, polishedText, sourceRecordId, sourceMode, modelId, modelName, mock,
                    editRevision, checks, historySaved, persistentHistory, createdAt, null);
        }
        /** 手动编辑不改写生成原件、输入或来源。 */
        public Document edited(String text, long revision, List<Check> findings) {
            return new Document(id, input, generatedText, text, sourceRecordId, sourceMode, modelId, modelName,
                    mock, revision, findings, historySaved, persistentHistory, createdAt, executionMetadata);
        }
        /** 历史存储确认后更新状态，失败时调用方仍可持有正文。 */
        public Document saved(boolean persistent) {
            return new Document(id, input, generatedText, polishedText, sourceRecordId, sourceMode, modelId, modelName,
                    mock, editRevision, checks, true, persistent, createdAt, executionMetadata);
        }
    }
    /** SQL分页结果，不把所有用户记录载入浏览器。 */
    public record HistoryPage(List<Document> records, long total, int size, int current, long pages) { }
    /** 模块可用模型仅返回公开目录字段。 */
    public record Model(String id, String displayName, boolean defaultModel, boolean mock) { }
    /** 模块专属模型策略；默认项必须在启用清单中，全局可用状态继续生效。 */
    public record ModelPolicy(@NotNull @Size(max = 200) List<@NotBlank @Size(max = 160) String> enabledModelIds,
                              @Size(max = 160) String defaultModelId) { }
    /** 服务端资源上限及历史能力，前端不自行猜测。 */
    public record Capabilities(List<Model> models, int maxInputCharacters, int maxOutputCharacters, boolean persistentHistory) { }
}
