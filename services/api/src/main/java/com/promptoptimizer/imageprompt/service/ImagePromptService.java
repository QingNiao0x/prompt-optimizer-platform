package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.common.logging.LogCorrelation;
import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.identity.service.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.MaterialRef;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.MaterialSource;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.Resolved;
import com.promptoptimizer.imageprompt.domain.ImagePromptMediaModels.Track;
import com.promptoptimizer.imageprompt.dto.ImagePromptRequests.*;
import com.promptoptimizer.imageprompt.service.ImagePromptProvider.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.promptoptimizer.imageprompt.domain.ImagePromptModels.knownEvidenceWarnings;

/**
 * 编排图片、视频帧和PPT页面的描述性还原与内容创作。服务端绑定全部输入、来源和实际题答，幂等缓存只保存最终阶段结果。
 * 生成与保存职责分开：存储失败重试保存，不重复模型调用；历史删除后缓存同样不再公开正文。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class ImagePromptService {
    private static final Duration PLAN_TTL = Duration.ofHours(1), OPERATION_TTL = Duration.ofHours(24);
    private final CurrentActor current;
    private final ImagePromptAssetService assets;
    private final ImagePromptModelService models;
    private final ImagePromptProvider provider;
    private final ImagePromptStateStore states;
    private final ImagePromptHistoryService history;
    private final ObjectMapper json;
    private final AnalyticsEventService analytics;
    private final ImagePromptMediaService media;
    private final ImagePromptSourceService sourceMaterials;
    /** 最多12个受控单位，最差单图模型也能留出Plan、生成与协议修复空间；所有阶段按实际尝试累计。 */
    private static final int MAX_WORKFLOW_ATTEMPTS = 28;
    private static final int MAX_SELECTED_UNITS = 12;
    private static final int MAX_SOURCE_OBSERVATIONS = MAX_SELECTED_UNITS * 8;
    private final SensitiveValueScanner scanner = new SensitiveValueScanner();

    /** 装配独立图像流程与账号审计；试用入口不产生正式账号操作事件。 */
    public ImagePromptService(CurrentActor current, ImagePromptAssetService assets, ImagePromptModelService models,
                              ImagePromptProvider provider, ImagePromptStateStore states, ImagePromptHistoryService history,
                              ObjectMapper json, AnalyticsEventService analytics) {
        this(current, assets, models, provider, states, history, json, analytics, null);
    }

    /** 多模态解析只提供已授权单位与来源，不能把客户端分析替代服务端证据。 */
    public ImagePromptService(CurrentActor current, ImagePromptAssetService assets, ImagePromptModelService models,
                              ImagePromptProvider provider, ImagePromptStateStore states, ImagePromptHistoryService history,
                              ObjectMapper json, AnalyticsEventService analytics, ImagePromptMediaService media) {
        this(current, assets, models, provider, states, history, json, analytics, media, null);
    }

    /** 通用资料与视觉资料分别解析；共享服务端Plan、操作幂等和历史边界。 */
    @Autowired
    public ImagePromptService(CurrentActor current, ImagePromptAssetService assets, ImagePromptModelService models,
                              ImagePromptProvider provider, ImagePromptStateStore states, ImagePromptHistoryService history,
                              ObjectMapper json, AnalyticsEventService analytics, ImagePromptMediaService media, ImagePromptSourceService sourceMaterials) {
        this.sourceMaterials = sourceMaterials;
        this.current = current; this.assets = assets; this.models = models; this.provider = provider;
        this.states = states; this.history = history; this.json = json; this.analytics = analytics;
        this.media = media;
    }

    /** 返回真实服务限制与本次可用模型，未知能力不通过名称猜测。 */
    public Capabilities capabilities() {
        current.require();
        return new Capabilities(models.models(), ImagePromptAssetService.MAX_BYTES, ImagePromptAssetService.MAX_PIXELS,
                ImagePromptAssetService.MAX_EDGE, 3, ImagePromptAssetService.TTL.toSeconds(), history.persistent());
    }

    /** 访客只开放单图还原；额度与幂等由trial模块预占，不建立Plan或正式历史。 */
    public Document extractTrial(String guest, Generate request) {
        Input input = normalized(request.input().input());
        requireTrialScope(input);
        var images = input.images().stream().map(reference -> {
            Asset asset = assets.requireGuest(guest, reference.assetId());
            return new ImageData(new ImageSource(asset.id(), asset.name(), asset.hash(), reference.role(), asset.width(), asset.height(), asset.expiresAt()),
                    asset.mimeType(), assets.bytesGuest(guest, asset.id()));
        }).toList();
        var result = provider.invoke(new Call(Stage.EXTRACT, input, images, List.of(), List.of(), List.of(), "", ""));
        images.forEach(image -> assets.requireGuest(guest, image.source().id()));
        var generated = document(request.requestId(), input, images.stream().map(ImageData::source).toList(),
                List.of(), List.of(), result, result.draft().analysis());
        return new Document(generated.operationId(), null, generated.mode(), generated.optimizedPrompt(), generated.analysis(),
                generated.warnings(), generated.input(), generated.sources(), List.of(), List.of(), generated.modelId(), generated.modelName(),
                generated.mock(), 0, false, false, generated.createdAt(), generated.materialSources(), generated.evidenceWarnings());
    }

    /** 在扣除试用次数前确认输入及私有图片，客户端模式不能绕过Plan登录要求。 */
    public void validateTrial(String guest, Generate request) {
        Input input = normalized(request.input().input());
        requireTrialScope(input);
        input.images().forEach(reference -> assets.requireGuest(guest, reference.assetId()));
    }

    /** 关闭 Plan：还原已选图片、页面或视频帧，不接收创作要求，也不建立问答计划。 */
    public Document extract(Generate request) {
        var actor = current.require(); Input input = normalized(request.input().input());
        if (input.mode() != Mode.REFERENCE_RECONSTRUCTION) throw ImagePromptException.invalid("内容创作请使用 Plan 入口。");
        return operate(actor, request.requestId(), fingerprint(input), () -> {
            if (input.sourceTask() != null) return sourceWorkflow(actor, request.requestId(), input, false);
            if (!input.materials().isEmpty()) return extractMaterials(actor, request.requestId(), input);
            List<ImageData> images = images(actor, input);
            analytics.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_SUBMITTED, request.requestId());
            analytics.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_DIRECT_SUBMITTED, request.requestId());
            var response = provider.invoke(new Call(Stage.EXTRACT, input, images, List.of(), List.of(), List.of(), "", ""));
            images.forEach(i -> assets.require(actor, i.source().id()));
            return new Operation(fingerprint(input), "COMPLETED", null, persist(actor, document(request.requestId(), input,
                    images.stream().map(ImageData::source).toList(), List.of(), List.of(), response, response.draft().analysis())), "", Instant.now());
        }).document();
    }

    /** 多媒体直接还原复用来源识别，不经过PLAN；最终只消费已识别证据，不再次外发像素。 */
    private Operation extractMaterials(ActorIdentity actor, UUID requestId, Input input) {
        var prepared = prepared(actor, input);
        analytics.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_SUBMITTED, requestId);
        analytics.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_DIRECT_SUBMITTED, requestId);
        Instant deadline = Instant.now().plusSeconds(180);
        var recognized = analyzeMaterials(actor, input, prepared, deadline, 2);
        requireInputs(actor, input);
        requireWithinDeadline(deadline);
        models.require(input.modelId(), 0);
        var evidenceWarnings = new ArrayList<>(recognized.warnings());
        evidenceWarnings.addAll(knownEvidenceWarnings(prepared.sources(), recognized.analysis()));
        var response = provider.invoke(new Call(Stage.GENERATE, input, List.of(), recognized.analysis(), List.of(), List.of(), "", "",
                prepared.sources(), Math.min(2, MAX_WORKFLOW_ATTEMPTS - recognized.attempts()), distinctWarnings(evidenceWarnings)));
        requireInputs(actor, input);
        return new Operation(fingerprint(input), "COMPLETED", null, persist(actor, document(requestId, input,
                prepared.images().stream().map(ImageData::source).toList(), List.of(), List.of(), response, recognized.analysis(),
                prepared.sources(), distinctWarnings(evidenceWarnings))), "", Instant.now());
    }

    /** 开启 Plan：文字构想起步，参考与替换素材可选；素材观察不会被升级为用户确认。 */
    public Plan plan(Generate request) {
        var actor = current.require(); Input input = normalized(request.input().input());
        if (input.mode() != Mode.CUSTOM_CREATION) throw ImagePromptException.invalid("参考素材还原不发起创作问答。");
        return operate(actor, request.requestId(), fingerprint(input), () -> {
            if (input.sourceTask() != null) return sourceWorkflow(actor, request.requestId(), input, true);
            var prepared = prepared(actor, input);
            var images = prepared.images();
            analytics.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_SUBMITTED, request.requestId());
            Instant deadline = Instant.now().plusSeconds(180);
            var recognized = analyzeMaterials(actor, input, prepared, deadline, 4);
            var prior = recognized.analysis();
            var evidenceWarnings = new ArrayList<>(recognized.warnings());
            int attempts = recognized.attempts();
            requireInputs(actor, input);
            requireWithinDeadline(deadline);
            var response = provider.invoke(new Call(Stage.PLAN, input, input.materials().isEmpty() ? images : List.of(), prior,
                    List.of(), List.of(), "", "", prepared.sources(), Math.min(2, MAX_WORKFLOW_ATTEMPTS - attempts - 2), distinctWarnings(evidenceWarnings)));
            attempts += attempts(response);
            // 识图期间撤销或过期的资产不再向页面交付分析，也不建立可确认计划。
            requireInputs(actor, input);
            Instant expiry = images.stream().map(i -> i.source().expiresAt()).min(Comparator.naturalOrder()).orElse(Instant.now().plus(PLAN_TTL));
            var sources = images.stream().map(ImageData::source).toList();
            String binding = fingerprint(List.of(input, sources, prepared.sources()));
            // 已独立识别的媒体只保留ANALYZE证据；Plan中的创作要求和新文案不能被追加为源素材观察。
            var sourceAnalysis = mergedAnalysis(prior, input.materials().isEmpty() ? response.draft().analysis() : List.of());
            evidenceWarnings.addAll(sourceAnalysis.stream().filter(observation -> observation.status() == Visibility.UNCERTAIN)
                    .map(Observation::description).toList());
            var warnings = new ArrayList<>(evidenceWarnings); warnings.addAll(response.draft().warnings());
            var view = new Plan(request.requestId(), binding, response.draft().summary(), response.draft().questions(),
                    sourceAnalysis, distinctWarnings(warnings), expiry, response.mock(), prepared.sources());
            states.put(actor, "plan:" + view.id(), new BoundPlan(input, sources, view, attempts, distinctWarnings(evidenceWarnings)), Duration.between(Instant.now(), expiry));
            return new Operation(fingerprint(input), "COMPLETED", view, null, "", Instant.now());
        }).plan();
    }

    /** 只能使用服务端原题、输入与来源快照生成；客户端仅提交实际答案及绑定指纹。 */
    public Document confirm(UUID id, Confirm request) {
        var actor = current.require();
        BoundPlan bound = states.get(actor, "plan:" + id, BoundPlan.class);
        if (bound == null || !bound.view().expiresAt().isAfter(Instant.now())) throw ImagePromptException.expired();
        if (!bound.view().fingerprint().equals(request.fingerprint())) throw ImagePromptException.conflict("创作输入已改变，请重新梳理并确认。");
        List<Answer> answers = validateAnswers(bound.view().questions(), request.answers().stream().map(Reply::answer).toList());
        String fingerprint = fingerprint(List.of(id, request.fingerprint(), answers));
        return operate(actor, request.requestId(), fingerprint, () -> {
            requireInputs(actor, bound.input());
            models.require(bound.input().modelId(), 0);
            analytics.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_SUBMITTED, request.requestId());
            var response = provider.invoke(new Call(Stage.GENERATE, bound.input(), List.of(), bound.view().analysis(),
                    bound.view().questions(), answers, "", "", bound.view().materialSources(), Math.min(2, MAX_WORKFLOW_ATTEMPTS - bound.attemptsUsed()), bound.evidenceWarnings()).withSourceSources(bound.view().sourceSources()));
            requireInputs(actor, bound.input());
            if (bound.input().materials().isEmpty() && !bound.input().images().isEmpty()) {
                // legacy一次Plan同时识别/提问：继续展示完整旧警告，但不把无法归因的混合提示持久化为独立来源风险。
                var legacyWarnings = new ArrayList<>(bound.view().warnings()); legacyWarnings.addAll(response.draft().warnings());
                response = new ProviderResult(new ProviderDraft(response.draft().summary(), response.draft().optimizedPrompt(), response.draft().analysis(),
                        response.draft().questions(), distinctWarnings(legacyWarnings)), response.modelId(), response.modelName(), response.mock(), response.attempts());
            }
            var completed = sourceDocument(document(request.requestId(), bound.input(), bound.sources(),
                    bound.view().questions(), answers, response, bound.view().analysis(), bound.view().materialSources(), bound.evidenceWarnings()), bound.view().sourceSources());
            var saved = persist(actor, completed);
            analytics.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_PLAN_COMPLETED, id);
            return new Operation(fingerprint, "COMPLETED", null, saved, "", Instant.now());
        }).document();
    }

    /** 再次优化从服务端已保存的当前正文出发，原图过期也可做文字修订，不声称重看图片。 */
    public Document rewrite(UUID id, Rewrite request) {
        var actor = current.require(); Document source = history.find(actor, id);
        if (source.editRevision() != request.expectedRevision()) throw ImagePromptException.conflict("历史记录已更新，请先重新读取后再优化。");
        safeText(request.instruction());
        return operate(actor, request.requestId(), fingerprint(List.of(id, source.editRevision(), request.instruction())), () -> {
            models.require(source.modelId(), 0);
            analytics.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_SUBMITTED, request.requestId());
            var response = provider.invoke(new Call(Stage.REWRITE, source.input(), List.of(), source.analysis(), source.questions(), source.answers(),
                    source.input().sourceTask() == null ? creationBody(source) : sourceBody(source), request.instruction(), source.materialSources(), 2, source.evidenceWarnings()).withSourceSources(source.sourceSources()));
            // 再次核对逻辑删除与修订，旧模型响应不能越过中途删除或其他标签页的编辑。
            if (history.find(actor, id).editRevision() != source.editRevision()) throw ImagePromptException.conflict("原记录在生成期间已修改，请核对后重新操作。");
            var warnings = new ArrayList<>(response.draft().warnings()); warnings.add("本次基于已保存正文和分析摘要优化，未重新分析图片。");
            var adjusted = new ProviderResult(new ProviderDraft(response.draft().summary(), response.draft().optimizedPrompt(), response.draft().analysis(),
                    List.of(), List.copyOf(warnings)), response.modelId(), response.modelName(), response.mock());
            var saved = persist(actor, sourceDocument(document(request.requestId(), source.input(), source.sources(), source.questions(), source.answers(), adjusted,
                    source.analysis(), source.materialSources(), source.evidenceWarnings()), source.sourceSources()));
            analytics.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_REOPTIMIZED, request.requestId());
            return new Operation(fingerprint(List.of(id, source.editRevision(), request.instruction())), "COMPLETED", null, saved, "", Instant.now());
        }).document();
    }

    /** 已完成生成的保存恢复不经过模型；已逻辑删除的记录不能被缓存复活。 */
    public Document retrySave(UUID id) {
        var actor = current.require(); Operation operation = operation(id);
        if (operation.document() == null) throw ImagePromptException.conflict("该操作没有可保存的生成结果。");
        Document saved = persist(actor, operation.document());
        states.put(actor, "op:" + id, new Operation(operation.fingerprint(), operation.status(), null, saved, operation.message(), operation.startedAt()), OPERATION_TTL);
        return saved;
    }

    /** 网络断开后查询状态；超过正常执行期限的进行中操作显示未知，禁止自动重放模型调用。 */
    public Operation operation(UUID id) {
        var actor = current.require(); Operation operation = states.get(actor, "op:" + id, Operation.class);
        if (operation == null) throw ImagePromptException.expired();
        if (operation.document() != null && operation.document().historySaved()) {
            // 历史正文可能已被编辑；缓存不能恢复旧版本，也不能复活逻辑删除记录。
            var document = history.find(actor, operation.document().recordId());
            operation = new Operation(operation.fingerprint(), operation.status(), operation.plan(), document, operation.message(), operation.startedAt());
        }
        if (operation.plan() != null && !operation.plan().expiresAt().isAfter(Instant.now())) throw ImagePromptException.expired();
        if (operation.status().equals("RUNNING") && operation.startedAt().plusSeconds(300).isBefore(Instant.now())) {
            return new Operation(operation.fingerprint(), "UNKNOWN", null, null, "处理结果尚未确认，请核对历史；不要连续发起重复生成。", operation.startedAt());
        }
        return operation;
    }

    /** 原子预留先于外部模型；重复请求只返回同一结果，输入变化则拒绝复用旧幂等键。 */
    private Operation operate(ActorIdentity actor, UUID id, String fingerprint, Supplier<Operation> action) {
        var pending = new Operation(fingerprint, "RUNNING", null, null, "", Instant.now());
        if (!states.claim(actor, "op:" + id, pending, OPERATION_TTL)) {
            var existing = operation(id);
            if (!existing.fingerprint().equals(fingerprint)) throw ImagePromptException.conflict("请求标识已用于其他输入，请重新发起本次操作。");
            if (!existing.status().equals("COMPLETED")) throw ImagePromptException.conflict(existing.status().equals("RUNNING")
                    ? "当前请求仍在处理中，请等待并查询处理状态。" : "上次请求已结束或状态未知；请先检查历史，再明确发起新的生成。");
            return existing;
        }
        try (var correlation = LogCorrelation.bindWorkflow("image", id.toString())) {
            Operation done = action.get();
            states.put(actor, "op:" + id, done, OPERATION_TTL);
            // 只采集第一次完成的有效结果。缓存重放、状态查询和补存均不经过该分支。
            if (done.document() != null) analytics.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_GENERATED, id);
            else if (done.plan() != null) analytics.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_PLAN_CREATED, id);
            return done;
        } catch (RuntimeException failure) {
            // 不保存异常正文，避免上游错误中夹带图片、请求或凭据。已发送请求不得因错误缓存重放。
            try { states.put(actor, "op:" + id, new Operation(fingerprint, "FAILED", null, null,
                    "该次处理未成功，请检查错误提示与历史后再发起新请求。", pending.startedAt()), OPERATION_TTL); }
            catch (RuntimeException ignored) { /* 存储故障保持原进行中状态，不能以新调用掩盖不确定结果。 */ }
            throw failure;
        }
    }

    /** 正常化只处理外围空白，不把用户明确文案、事实或未知重写为新的确认。 */
    private Input normalized(Input input) {
        if (input.mode() == null || input.language() == null || input.images() == null || input.images().size() > 3) throw ImagePromptException.invalid("图像请求模式或图片数量无效。");
        if (input.images().stream().map(ImageRef::assetId).distinct().count() != input.images().size()) throw ImagePromptException.invalid("同一图片不能重复加入本次任务。");
        // 旧图片与多模态素材共享任务名额，不能通过混用两种协议绕过前端的合计六份限制。
        if (input.images().size() + input.materials().size() > 6 || input.materials().stream().map(MaterialRef::assetId).distinct().count() != input.materials().size()
                || input.relations().size() > 12) throw ImagePromptException.invalid("最多使用6份不同素材和12条替换关系。");
        if (input.sourceTask() != null) {
            if (blank(input.creationIntent())) throw ImagePromptException.invalid("请说明希望下游完成的任务。");
            var actor = current.require(); requiredSources().resolve(actor, input.sourceTask()); requiredSources().requireConsent(actor, input);
        } else if (input.mode() == Mode.REFERENCE_RECONSTRUCTION) {
            if (input.images().isEmpty() && input.materials().isEmpty()
                    || input.materials().isEmpty() && input.images().size() != 1) throw ImagePromptException.invalid("直接增强需要参考图片或已选择范围的参考素材。");
            if (input.outputType() != OutputType.IMAGE || !input.relations().isEmpty()
                    || input.materials().stream().anyMatch(material -> material.track() != Track.REFERENCE)) {
                throw ImagePromptException.invalid("直接增强仅还原参考素材；替换素材、结构化替换或自定义成品请开启 Plan。");
            }
            if (!input.creationIntent().isBlank() || !input.exactText().isBlank() || !input.context().isBlank()) {
                throw ImagePromptException.invalid("更换场景、添加文案或自定义画面请开启 Plan；直接增强只还原参考素材。");
            }
        } else if (input.creationIntent().isBlank()) throw ImagePromptException.invalid("请先描述想创作的内容。");
        safeText(input.creationIntent()); safeText(input.exactText()); safeText(input.context()); safeText(input.targetTool());
        validateRelations(input);
        // 媒体请求会连同旧图片逐批识别；先校验至少单图视觉准入，不用未分批的总数误拒合法请求。
        models.require(input.modelId(), input.materials().isEmpty() ? input.images().size() : 1);
        return input;
    }

    /** 正式媒体引用不属于访客命名空间；在扣额度及读资产之前维持既有单图试用范围。 */
    private void requireTrialScope(Input input) {
        if (input.sourceTask() != null || input.mode() != Mode.REFERENCE_RECONSTRUCTION || !input.materials().isEmpty() || input.images().size() != 1) {
            throw ImagePromptException.invalid("请注册登录后使用多媒体素材或图像Plan；访客仅可试用单张图片还原。");
        }
    }

    /** 关系引用只能指向当前主动选择的素材与单位；角色选择不构成读取其他私有素材的授权。 */
    private void validateRelations(Input input) {
        var ids = new HashSet<String>();
        for (ReplacementRelation relation : input.relations()) {
            if (relation == null || relation.id() == null || !relation.id().matches("[A-Za-z0-9_-]{1,60}") || !ids.add(relation.id())
                    || relation.operation() == null) throw ImagePromptException.invalid("替换关系编号或操作无效。");
            relationSource(input, relation.referenceAssetId(), relation.referenceUnitId(), false);
            relationSource(input, relation.replacementAssetId(), relation.replacementUnitId(), true);
            boundedText(relation.referenceObject(), 500); boundedText(relation.replacementObject(), 500);
            boundedText(relation.instruction(), 1500); boundedText(relation.scope(), 500);
            if (relation.attributes() == null || relation.attributes().size() > 8) throw ImagePromptException.invalid("每条关系最多选择8项属性。");
            relation.attributes().forEach(value -> {
                if (blank(value)) throw ImagePromptException.invalid("替换关系属性不能为空。");
                boundedText(value, 100);
            });
            if (relation.operation() == RelationOperation.REPLACE && relation.replacementAssetId() == null
                    && blank(relation.replacementObject()) && blank(relation.instruction())) throw ImagePromptException.invalid("替换关系需要新素材或明确的新内容说明。");
            if (relation.referenceAssetId() == null && relation.replacementAssetId() == null && blank(relation.referenceObject())
                    && blank(relation.replacementObject()) && blank(relation.instruction())) throw ImagePromptException.invalid("请说明这条关系要保留、替换、移除或融合的内容。");
        }
    }

    /** 非空单位必须在同一素材的已选范围内，不能用独立UUID绕过页面/帧选择。 */
    private void relationSource(Input input, UUID assetId, UUID unitId, boolean replacement) {
        if (assetId == null) { if (unitId != null) throw ImagePromptException.invalid("关系单位缺少所属素材。"); return; }
        if (input.sourceTask() != null) {
            var textual = input.sourceTask().sources().stream().filter(ref -> assetId.equals(ref.assetId())).findFirst();
            if (textual.isPresent()) {
                if (replacement && textual.get().track() != Track.REPLACEMENT) throw ImagePromptException.invalid("新内容来源请选择新内容轨道中的资料。");
                if (unitId != null && !textual.get().unitIds().contains(unitId)) throw ImagePromptException.invalid("关系引用了未选择的资料片段。");
                return;
            }
        }
        MaterialRef source = input.materials().stream().filter(m -> assetId.equals(m.assetId())).findFirst()
                .orElseThrow(() -> ImagePromptException.invalid("替换关系引用了未加入本次任务的素材。"));
        if (replacement && source.track() != Track.REPLACEMENT) throw ImagePromptException.invalid("新内容来源请选择替换素材轨道中的素材。");
        if (unitId != null && (source.units() == null || source.units().stream().noneMatch(u -> unitId.equals(u.unitId())))) {
            throw ImagePromptException.invalid("替换关系引用了未选择的页面或视频帧。");
        }
    }

    /** 关系文本是用户指令，限定长度并按既有凭据扫描，不让敏感值进入模型、指纹或历史。 */
    private void boundedText(String value, int maximum) {
        if (value != null && value.length() > maximum) throw ImagePromptException.invalid("替换关系文字超过长度限制。");
        safeText(value == null ? "" : value);
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    /** 在模型调用前一次性解析受控来源；遮挡后的字节与文本必须由同一次resolve产生。 */
    private Resolved prepared(ActorIdentity actor, Input input) {
        var result = new ArrayList<>(images(actor, input));
        var resolved = input.materials().isEmpty() ? new Resolved(List.of(), List.of(), List.of()) : requiredMedia().resolve(actor, input.materials());
        result.addAll(resolved.images());
        if (result.size() > MAX_SELECTED_UNITS || result.stream().mapToLong(i -> i.bytes().length).sum() > 30L * 1024 * 1024) {
            throw ImagePromptException.invalid("本次最多选择12个图片、页面或视频帧，规范化图像总量不能超过30 MiB。");
        }
        resolved.sources().forEach(source -> { safeText(source.name()); source.units().forEach(unit -> safeText(unit.text())); });
        var warnings = new ArrayList<>(resolved.warnings());
        input.images().forEach(reference -> warnings.addAll(assets.require(actor, reference.assetId()).processing()));
        return new Resolved(List.copyOf(result), resolved.sources(), distinctWarnings(warnings));
    }

    /**
     * 两种模式共用同一来源识别：只给模型本批遮挡后像素及结构文本，按真实maxImages分批。
     * reservedAttempts为后续阶段保留预算（直接还原2次、Plan及确认生成4次）；旧单次图片不额外识别。
     */
    private SourceAnalysis analyzeMaterials(ActorIdentity actor, Input input, Resolved prepared, Instant deadline, int reservedAttempts) {
        var analysis = new ArrayList<Observation>();
        var warnings = new ArrayList<>(prepared.warnings());
        int used = 0;
        if (!input.materials().isEmpty()) {
            int batchSize = models.require(input.modelId(), 1).maxImages();
            for (int offset = 0; offset < prepared.images().size(); offset += batchSize) {
                requireInputs(actor, input); requireWithinDeadline(deadline);
                var batch = prepared.images().subList(offset, Math.min(prepared.images().size(), offset + batchSize));
                models.require(input.modelId(), batch.size());
                var observed = provider.invoke(new Call(Stage.ANALYZE, input, batch, List.of(), List.of(), List.of(), "", "",
                        batchSources(prepared.sources(), batch), Math.min(2, MAX_WORKFLOW_ATTEMPTS - used - reservedAttempts)));
                used += attempts(observed); analysis.addAll(observed.draft().analysis()); warnings.addAll(observed.draft().warnings());
                if (analysis.size() > MAX_SOURCE_OBSERVATIONS) throw ImagePromptException.invalid("素材观察超过每单位8项的分析预算，请缩小选择范围。");
            }
        }
        return new SourceAnalysis(List.copyOf(analysis), distinctWarnings(warnings), used);
    }

    /** 截止时间后不再开启新模型阶段，不重放可能已计费的请求。 */
    private void requireWithinDeadline(Instant deadline) {
        if (Instant.now().isAfter(deadline)) throw ImagePromptException.invalid("素材分析已达到时间上限，请减少选取范围后重试；已开始的模型请求不会自动重放。");
    }

    /** 素材识别产物只含来源观察、独立风险及实际尝试数，不持久化图像字节。 */
    private record SourceAnalysis(List<Observation> analysis, List<String> warnings, int attempts) { }

    /** 只检查当前归属、撤销与有效期；确认及生成结束时不得重新调用视觉模型。 */
    private void requireInputs(ActorIdentity actor, Input input) {
        if (input.sourceTask() != null) {
            requiredSources().requireConsent(actor, input);
            input.sourceTask().sources().forEach(reference -> requiredSources().require(actor, reference.assetId()));
        }
        input.images().forEach(reference -> assets.require(actor, reference.assetId()));
        input.materials().forEach(reference -> requiredMedia().require(actor, reference.assetId()));
    }
    /** 兼容旧装配入口，但多模态请求缺少解析端口时明确失败，不能退化成已识别或空资料。 */
    private ImagePromptMediaService requiredMedia() {
        if (media == null) throw ImagePromptException.invalid("多模态素材服务尚未配置。");
        return media;
    }

    /** 新资料入口缺少配置时明确拒绝，不能把资料伪造成空图片或已理解全文。 */
    private ImagePromptSourceService requiredSources() {
        if (sourceMaterials == null) throw ImagePromptException.invalid("通用资料服务尚未配置。");
        return sourceMaterials;
    }

    /**
     * 通用任务的直接与Plan共享范围/隐私快照。视觉分析只接触已选像素；文字片段只进入后续任务阶段。
     * 用户目标与Plan开关独立：关闭Plan也允许明确的迁移、替换或文档任务。
     */
    private Operation sourceWorkflow(ActorIdentity actor, UUID requestId, Input input, boolean planning) {
        var textual = requiredSources().resolve(actor, input.sourceTask());
        var prepared = prepared(actor, input);
        analytics.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_SUBMITTED, requestId);
        if (!planning) analytics.recordToolOperation(AnalyticsEventType.IMAGE_PROMPT_DIRECT_SUBMITTED, requestId);
        Instant deadline = Instant.now().plusSeconds(180);
        var analysis = new ArrayList<Observation>(); var warnings = new ArrayList<String>();
        warnings.addAll(prepared.warnings()); textual.forEach(source -> warnings.addAll(source.warnings()));
        int used = 0;
        // 即使只有旧单图，通用任务也先独立观察再生成，不能让新目标混入图像事实。
        if (!prepared.images().isEmpty()) {
            int batchSize = models.require(input.modelId(), 1).maxImages();
            for (int offset = 0; offset < prepared.images().size(); offset += batchSize) {
                requireInputs(actor, input); requireWithinDeadline(deadline);
                var batch = prepared.images().subList(offset, Math.min(prepared.images().size(), offset + batchSize));
                var observed = provider.invoke(new Call(Stage.ANALYZE, input, batch, List.of(), List.of(), List.of(), "", "",
                        batchSources(prepared.sources(), batch), Math.min(2, MAX_WORKFLOW_ATTEMPTS - used - (planning ? 4 : 2))));
                used += attempts(observed); analysis.addAll(observed.draft().analysis()); warnings.addAll(observed.draft().warnings());
            }
        }
        requireInputs(actor, input); requireWithinDeadline(deadline);
        var response = provider.invoke(new Call(planning ? Stage.PLAN : Stage.GENERATE, input, List.of(), analysis, List.of(), List.of(), "", "",
                prepared.sources(), Math.min(2, MAX_WORKFLOW_ATTEMPTS - used - (planning ? 2 : 0)), distinctWarnings(warnings)).withSourceSources(textual));
        used += attempts(response); requireInputs(actor, input);
        if (!planning) {
            var doc = sourceDocument(document(requestId, input, prepared.images().stream().map(ImageData::source).toList(), List.of(), List.of(),
                    response, analysis, prepared.sources(), distinctWarnings(warnings)), textual);
            return new Operation(fingerprint(input), "COMPLETED", null, persist(actor, doc), "", Instant.now());
        }
        Instant expiry = prepared.images().stream().map(image -> image.source().expiresAt()).min(Comparator.naturalOrder()).orElse(Instant.now().plus(PLAN_TTL));
        for (var source : input.sourceTask().sources()) {
            Instant sourceExpiry = requiredSources().require(actor, source.assetId()).expiresAt();
            if (sourceExpiry.isBefore(expiry)) expiry = sourceExpiry;
        }
        var allWarnings = new ArrayList<>(warnings); allWarnings.addAll(response.draft().warnings());
        String binding = fingerprint(List.of(input, prepared.sources(), textual));
        var view = new Plan(requestId, binding, response.draft().summary(), response.draft().questions(), List.copyOf(analysis),
                distinctWarnings(allWarnings), expiry, response.mock(), prepared.sources(), textual);
        states.put(actor, "plan:" + view.id(), new BoundPlan(input, prepared.images().stream().map(ImageData::source).toList(), view, used,
                distinctWarnings(warnings)), Duration.between(Instant.now(), expiry));
        return new Operation(fingerprint(input), "COMPLETED", view, null, "", Instant.now());
    }

    /** 仅将实际使用的脱敏证据写入历史，并附下游可理解的文件定位；不把私有ID写进正文。 */
    private Document sourceDocument(Document document, List<com.promptoptimizer.imageprompt.domain.ImagePromptSourceModels.Source> sources) {
        if (document.input().sourceTask() == null) return document;
        String body = document.optimizedPrompt() + sourceManifest(sources);
        if (body.length() > 30000) throw ImagePromptException.invalid("提示词与资料清单超过30000字符，请缩小使用范围。");
        return document.withSourceSources(sources).edited(body, document.editRevision());
    }

    /** 来源清单仅列已选路径与片段标签，范围外文件不被描述为已分析或下游已取得。 */
    private String sourceManifest(List<com.promptoptimizer.imageprompt.domain.ImagePromptSourceModels.Source> sources) {
        if (sources.isEmpty()) return "";
        var text = new StringBuilder("\n\n资料交接清单：\n请向下游提供实际使用的所选资料；文件名与标签用于对应，不是公开链接。未选片段未纳入本次分析。\n");
        for (var source : sources) {
            text.append("- ").append(source.name()).append(" · ").append(source.track()).append(" · ").append(source.role()).append('\n');
            for (var unit : source.units()) text.append("  ").append(unit.path()).append(" [").append(unit.label()).append("]\n");
        }
        return text.toString();
    }

    /** 仅去掉完整可重建尾注，防止再次优化将相同资料清单重复追加；人工编辑内容保留。 */
    private String sourceBody(Document document) {
        String suffix = sourceManifest(document.sourceSources());
        if (!suffix.isEmpty() && document.optimizedPrompt().endsWith(suffix)) {
            return creationBody(document.edited(document.optimizedPrompt().substring(0, document.optimizedPrompt().length() - suffix.length()), document.editRevision()));
        }
        return document.optimizedPrompt();
    }

    /** 应用服务按适配器实际尝试数结算，缺失计数不能伪装为未调用。 */
    private int attempts(ProviderResult response) {
        if (response.attempts() < 1 || response.attempts() > 2) throw ImagePromptException.invalid("模型调用预算状态无效。");
        return response.attempts();
    }

    /** 来源证据去除完全重复项；新媒体调用方只传ANALYZE证据，旧带图Plan保留原识图结果。 */
    private List<Observation> mergedAnalysis(List<Observation> prior, List<Observation> planned) {
        var result = new LinkedHashSet<>(prior); result.addAll(planned);
        if (result.size() > MAX_SOURCE_OBSERVATIONS + 40) throw ImagePromptException.invalid("素材观察超过有界分析预算，请缩小选择范围后重新梳理。");
        return List.copyOf(result);
    }

    /** 保留各识别批次和最终阶段的独立风险，完全相同的提示只展示一次。 */
    private List<String> distinctWarnings(List<String> warnings) {
        return warnings.stream().filter(Objects::nonNull).filter(w -> !w.isBlank()).distinct().toList();
    }

    /** 每批只附本批页/帧的结构文本，避免重复外发其他已选页面的全文及重复计费。 */
    private List<MaterialSource> batchSources(List<MaterialSource> sources, List<ImageData> batch) {
        var ids = batch.stream().map(image -> image.source().id()).collect(java.util.stream.Collectors.toSet());
        return sources.stream().map(source -> new MaterialSource(source.assetId(), source.name(), source.kind(), source.track(), source.role(),
                        source.units().stream().filter(unit -> ids.contains(unit.id())).toList(), source.warnings()))
                .filter(source -> !source.units().isEmpty()).toList();
    }

    /** 单次调用总预算与逐张预算独立校验，角色随图片作为明确来源传入模型。 */
    private List<ImageData> images(ActorIdentity actor, Input input) {
        var result = new ArrayList<ImageData>(); long size = 0;
        for (ImageRef reference : input.images()) {
            Asset asset = assets.require(actor, reference.assetId());
            byte[] bytes = assets.bytes(actor, asset.id()); size += bytes.length;
            if (size > 30L * 1024 * 1024) throw ImagePromptException.invalid("本次规范化图片总量超过 30 MiB。");
            result.add(new ImageData(new ImageSource(asset.id(), asset.name(), asset.hash(), reference.role(), asset.width(), asset.height(), asset.expiresAt()), asset.mimeType(), bytes));
        }
        return List.copyOf(result);
    }

    /** 完整原题和选项留在服务端；每个答案核对题号、选项归属和单/多选规则。 */
    private List<Answer> validateAnswers(List<Question> questions, List<Answer> replies) {
        if (replies.stream().map(Answer::questionId).distinct().count() != replies.size()) throw ImagePromptException.invalid("同一道问题不能重复提交答案。");
        var result = new ArrayList<Answer>();
        for (Answer reply : replies) {
            Question question = questions.stream().filter(q -> q.id().equals(reply.questionId())).findFirst()
                    .orElseThrow(() -> ImagePromptException.invalid("答案不属于当前计划问题。"));
            var available = question.options().stream().map(Option::id).toList();
            if (!available.containsAll(reply.optionIds()) || reply.optionIds().stream().distinct().count() != reply.optionIds().size()
                    || question.type() != QuestionType.MULTIPLE_CHOICE && reply.optionIds().size() > (question.type() == QuestionType.TEXT ? 0 : 1)) {
                throw ImagePromptException.invalid("答案选项与当前题目不一致。");
            }
            safeText(reply.customText()); result.add(reply);
        }
        for (Question q : questions) if (q.required() && result.stream().noneMatch(a -> a.questionId().equals(q.id())
                && (!a.optionIds().isEmpty() || !a.customText().isBlank()))) throw ImagePromptException.invalid("请回答影响创作的必要问题，也可以明确委托 AI 提出方案。");
        return List.copyOf(result);
    }

    /** 确切画面文案为用户硬约束，缺失时按原文补入，不润色、不翻译、不采用模型评分决定保留。 */
    private Document document(UUID id, Input input, List<ImageSource> sources, List<Question> questions, List<Answer> answers,
                              ProviderResult result, List<Observation> analysis) {
        // 本重载只服务EXTRACT；此阶段没有创作问答，其模型风险提示全部属于来源识别，不能在后续文字修订中丢失。
        var evidenceWarnings = new ArrayList<>(result.draft().warnings());
        evidenceWarnings.addAll(knownEvidenceWarnings(List.of(), analysis));
        return document(id, input, sources, questions, answers, result, analysis, List.of(), distinctWarnings(evidenceWarnings));
    }

    /** 来源清单随正文复制；编号只用于用户在下游配套上传，不把平台私有ID伪装成可访问资源。 */
    private Document document(UUID id, Input input, List<ImageSource> sources, List<Question> questions, List<Answer> answers,
                              ProviderResult result, List<Observation> analysis, List<MaterialSource> materialSources, List<String> priorWarnings) {
        String prompt = result.draft().optimizedPrompt();
        if (prompt == null || prompt.isBlank() || prompt.length() > 24000) throw ImagePromptException.invalid("模型未返回有效提示词。");
        if (!input.exactText().isBlank() && !prompt.contains(input.exactText())) {
            prompt += input.sourceTask() != null
                    ? input.language() == Language.EN ? "\nRequired content (verbatim):\n" : "\n必须原样保留的内容：\n"
                    : input.language() == Language.EN ? "\nRequired on-image text (verbatim):\n" : "\n画面文字（逐字保留）：\n";
            prompt += input.exactText();
        }
        if (!materialSources.isEmpty()) prompt += materialManifest(input, materialSources);
        prompt += confirmedSupplement(input, questions, answers);
        if (prompt.length() > 30000) throw ImagePromptException.invalid("提示词与素材说明合计超过30000字符，请缩小创作要求或替换关系范围。");
        safeText(prompt);
        var warnings = new ArrayList<>(priorWarnings); warnings.addAll(result.draft().warnings());
        return new Document(id, null, input.mode(), prompt, List.copyOf(analysis), distinctWarnings(warnings), input, sources, questions, answers,
                result.modelId(), result.modelName(), result.mock(), 0, false, history.persistent(), Instant.now(), materialSources, distinctWarnings(priorWarnings));
    }

    /** 只移除与服务端完整生成说明完全一致的尾部；人工修改过的文字保持原样，不用正则猜删用户内容。 */
    private String creationBody(Document source) {
        if (source.materialSources().isEmpty() && source.input().relations().isEmpty()) return source.optimizedPrompt();
        String suffix = (source.materialSources().isEmpty() ? "" : materialManifest(source.input(), source.materialSources()))
                + confirmedSupplement(source.input(), source.questions(), source.answers());
        if (!suffix.isEmpty() && source.optimizedPrompt().endsWith(suffix)) {
            return source.optimizedPrompt().substring(0, source.optimizedPrompt().length() - suffix.length());
        }
        // v4历史可能包含已被回答撤销的原关系尾部；只去掉能完整重建的旧服务端说明，原历史本身不被改写。
        String legacy = legacyMaterialManifest(source.input(), source.materialSources());
        return source.optimizedPrompt().endsWith(legacy)
                ? source.optimizedPrompt().substring(0, source.optimizedPrompt().length() - legacy.length()) : source.optimizedPrompt();
    }

    /** 清单只编译可确定的来源与范围；原关系可能被Plan回答更改，不能在这里重新作为当前要求执行。 */
    private String materialManifest(Input input, List<MaterialSource> sources) {
        boolean english = input.language() == Language.EN;
        var text = new StringBuilder(english ? "\n\nMaterial reference:\n" : "\n\n素材对照：\n");
        // 还原范围是用户本次已选单位；Plan素材则可按实际确认取舍，不能用同一句尾注混淆两者。
        if (input.mode() == Mode.REFERENCE_RECONSTRUCTION && input.sourceTask() == null) {
            text.append(english ? "These entries define the selected reconstruction scope. Check each selected unit against its source; unselected content is outside this scope. Labels identify sources and are not public URLs.\n"
                    : "以下是本次还原范围，请逐项核对所选单位及来源；未选内容不属于本次还原范围。编号仅用于对应，不是公开链接。\n");
        } else {
            text.append(english ? "Upload only sources actually used by the prompt; listed entries need not all appear in the result. Labels identify sources and are not public URLs.\n"
                    : "仅按正文实际采用的来源配套上传；列出条目不表示需要全部用于成品。编号仅用于对应，不是公开链接。\n");
        }
        text.append(english ? "Use only the selected slides/frames and preserve all applied masks; do not replace them with unreviewed full originals.\n"
                : "仅使用所选页面／视频帧，并保留本次遮挡；请勿用未经审阅的完整原件代替。\n");
        text.append(materialRows(input, sources, false));
        return text.toString();
    }

    /** 轨道是原输入的来源属性，不代表确认后必须使用；legacy仅用于精确重建旧版尾部。 */
    private String materialRows(Input input, List<MaterialSource> sources, boolean legacy) {
        boolean english = input.language() == Language.EN;
        var text = new StringBuilder();
        int reference = 0, replacement = 0;
        for (MaterialSource source : sources) {
            String label = source.track() == Track.REFERENCE ? "R" + ++reference : "T" + ++replacement;
            String track = source.track() == Track.REFERENCE
                    ? legacy ? (english ? "reference" : "参考素材") : (english ? "original reference source" : "原参考来源")
                    : legacy ? (english ? "new subject/content" : "新主体／新内容") : (english ? "original replacement source" : "原新内容来源");
            text.append(label).append(" · ").append(source.name()).append(" · ").append(source.kind()).append(" · ")
                    .append(track);
            for (var unit : source.units()) {
                text.append(" [");
                if (unit.page() != null) text.append(english ? "slide " : "第").append(unit.page()).append(english ? "" : "页");
                else if (unit.timestampMs() != null) text.append(String.format(Locale.ROOT, "%.3fs", unit.timestampMs() / 1000.0));
                else text.append(english ? "image" : "图片");
                if (unit.masks() != null && !unit.masks().isEmpty()) text.append(english ? ", masked" : "，已遮挡");
                text.append(']');
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** 实际选项标签和自填文字共同保留；只强化已回答的确认，不推断哪些原卡被取消或改写。 */
    private String confirmedSupplement(Input input, List<Question> questions, List<Answer> answers) {
        if (input.relations().isEmpty()) return "";
        var confirmed = answers.stream().filter(answer -> !answer.optionIds().isEmpty() || !answer.customText().isBlank()).toList();
        if (confirmed.isEmpty()) return "";
        boolean english = input.language() == Language.EN;
        var text = new StringBuilder(english ? "\n\nConfirmed additional requirements:\n" : "\n\n已确认补充要求：\n");
        text.append(english ? "Apply these actual answers to the same matters; keep independent requirements that they do not change.\n"
                : "以下为本次实际回答；同一事项按本次确认执行，未被修改的独立要求继续有效。\n");
        for (Answer answer : confirmed) {
            Question question = questions.stream().filter(value -> value.id().equals(answer.questionId())).findFirst()
                    .orElseThrow(() -> ImagePromptException.invalid("确认答案缺少服务端原题。"));
            text.append("- ").append(question.question()).append('\n');
            for (String optionId : answer.optionIds()) {
                String label = question.options().stream().filter(option -> option.id().equals(optionId)).findFirst()
                        .orElseThrow(() -> ImagePromptException.invalid("确认答案缺少服务端原选项。")).label();
                text.append("  ").append(label).append('\n');
            }
            if (!answer.customText().isBlank()) text.append("  ").append(answer.customText()).append('\n');
        }
        return text.toString();
    }

    /** 仅为精确移除v4历史的完整服务端尾部重建旧格式；新结果绝不调用它来追加关系。 */
    private String legacyMaterialManifest(Input input, List<MaterialSource> sources) {
        boolean english = input.language() == Language.EN;
        String legacyHeading = english ? "\n\nMaterial upload guide and confirmed relationships:\n" : "\n\n素材上传说明与已明确的关系：\n";
        var text = new StringBuilder(legacyHeading)
                .append(english ? "Upload the corresponding files to the downstream tool; these labels are not public URLs.\n"
                        : "请在下游工具配套上传以下素材；编号仅用于对应，不能凭编号读取平台临时文件。\n")
                .append(english ? "Use only the selected slides/frames and preserve all applied masks; do not replace them with unreviewed full originals.\n"
                        : "仅使用所选页面／视频帧，并保留本次遮挡；请勿用未经审阅的完整原件代替。\n")
                .append(materialRows(input, sources, true));
        var labels = new LinkedHashMap<UUID, String>(); int reference = 0, replacement = 0;
        for (MaterialSource source : sources) labels.put(source.assetId(), source.track() == Track.REFERENCE ? "R" + ++reference : "T" + ++replacement);
        for (ReplacementRelation relation : input.relations()) {
            String operation = english ? relation.operation().name() : switch (relation.operation()) {
                case PRESERVE -> "保留"; case REPLACE -> "替换"; case REMOVE -> "移除"; case FUSE -> "融合";
            };
            text.append("- ").append(operation).append("：");
            appendRelationSource(text, labels, sources, relation.referenceAssetId(), relation.referenceUnitId(), relation.referenceObject());
            if (relation.replacementAssetId() != null || !blank(relation.replacementObject())) {
                text.append(" → "); appendRelationSource(text, labels, sources, relation.replacementAssetId(), relation.replacementUnitId(), relation.replacementObject());
            }
            if (!relation.attributes().isEmpty()) text.append(english ? "; attributes: " : "；属性：").append(String.join("、", relation.attributes()));
            if (!blank(relation.scope())) text.append(english ? "; scope: " : "；范围：").append(relation.scope());
            if (!blank(relation.instruction())) text.append("；").append(relation.instruction());
            text.append('\n');
        }
        return text.toString();
    }

    /** 单位定位使用可读页码/时间戳，UUID只留在平台协议中。 */
    private void appendRelationSource(StringBuilder text, Map<UUID, String> labels, List<MaterialSource> sources,
                                      UUID assetId, UUID unitId, String object) {
        if (assetId != null) {
            text.append(labels.getOrDefault(assetId, "素材"));
            if (unitId != null) sources.stream().filter(s -> assetId.equals(s.assetId())).flatMap(s -> s.units().stream())
                    .filter(u -> unitId.equals(u.id())).findFirst().ifPresent(unit -> {
                        if (unit.page() != null) text.append(" [slide ").append(unit.page()).append(']');
                        else if (unit.timestampMs() != null) text.append(String.format(Locale.ROOT, " [%.3fs]", unit.timestampMs() / 1000.0));
                    });
            text.append(' ');
        }
        if (!blank(object)) text.append(object);
    }

    /** 生成结果先返回；预期数据库故障保留可重试保存状态，删除等归属拒绝不能伪装成功。 */
    private Document persist(ActorIdentity actor, Document document) {
        try { return history.save(actor, document); }
        catch (ImagePromptException failure) { if (failure.status() == 404) throw failure; return document; }
        catch (org.springframework.dao.DataAccessException failure) { return document; }
    }
    /** 复用平台明确凭据检测，保护用户输入及最终正文；不宣称覆盖所有图像隐私。 */
    private void safeText(String text) {
        if (scanner.containsCredential(text)) throw ImagePromptException.invalid("内容含疑似密码或密钥，请移除敏感值后再提交。");
    }
    /** 指纹绑定实际模式、参数和题答，不能用相同幂等键替换输入。 */
    private String fingerprint(Object value) {
        try { return ImagePromptAssetService.hash(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("图像输入无法编码", failure); }
    }
    /** 来源证据与Plan警告分别绑定；旧带图Plan的混合警告只留在view中兼容展示，不提升为独立来源风险。 */
    public record BoundPlan(Input input, List<ImageSource> sources, Plan view, int attemptsUsed, List<String> evidenceWarnings) {
        /** 旧缓存无法可靠区分警告种类，保留可确定的来源风险，不猜测哪些旧提示已被回答解决。 */
        public BoundPlan {
            if (evidenceWarnings == null) {
                evidenceWarnings = knownEvidenceWarnings(view.materialSources(), view.analysis());
            } else evidenceWarnings = List.copyOf(evidenceWarnings);
        }
        /** 兼容不带阶段风险字段的历史调用方，按可确定来源重建而不猜测旧提示的当前状态。 */
        public BoundPlan(Input input, List<ImageSource> sources, Plan view, int attemptsUsed) { this(input, sources, view, attemptsUsed, null); }
    }
    /** 短期私有操作快照；状态为 RUNNING/COMPLETED/FAILED，超过等待期的查询返回 UNKNOWN。 */
    public record Operation(String fingerprint, String status, Plan plan, Document document, String message, Instant startedAt) { }
}
