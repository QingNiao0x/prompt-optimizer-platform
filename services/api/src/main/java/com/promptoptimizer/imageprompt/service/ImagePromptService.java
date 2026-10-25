package com.promptoptimizer.imageprompt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.common.logging.LogCorrelation;
import com.promptoptimizer.common.security.SensitiveValueScanner;
import com.promptoptimizer.identity.service.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.imageprompt.dto.ImagePromptRequests.*;
import com.promptoptimizer.imageprompt.service.ImagePromptProvider.*;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * 编排图像还原和自定义新图创作。服务端绑定全部输入、来源和实际题答，幂等缓存只保存最终阶段结果。
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
    private final SensitiveValueScanner scanner = new SensitiveValueScanner();

    public ImagePromptService(CurrentActor current, ImagePromptAssetService assets, ImagePromptModelService models,
                              ImagePromptProvider provider, ImagePromptStateStore states, ImagePromptHistoryService history, ObjectMapper json) {
        this.current = current; this.assets = assets; this.models = models; this.provider = provider;
        this.states = states; this.history = history; this.json = json;
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
        if (input.mode() != Mode.REFERENCE_RECONSTRUCTION) throw ImagePromptException.invalid("请注册登录后使用图像Plan。");
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
                generated.mock(), 0, false, false, generated.createdAt());
    }

    /** 在扣除试用次数前确认输入及私有图片，客户端模式不能绕过Plan登录要求。 */
    public void validateTrial(String guest, Generate request) {
        Input input = normalized(request.input().input());
        if (input.mode() != Mode.REFERENCE_RECONSTRUCTION) throw ImagePromptException.invalid("请注册登录后使用图像Plan。");
        input.images().forEach(reference -> assets.requireGuest(guest, reference.assetId()));
    }

    /** 关闭 Plan：一张必填图片，从图到文，不接收新图创作要求。 */
    public Document extract(Generate request) {
        var actor = current.require(); Input input = normalized(request.input().input());
        if (input.mode() != Mode.REFERENCE_RECONSTRUCTION) throw ImagePromptException.invalid("新图创作请使用 Plan 入口。");
        return operate(actor, request.requestId(), fingerprint(input), () -> {
            List<ImageData> images = images(actor, input);
            var response = provider.invoke(new Call(Stage.EXTRACT, input, images, List.of(), List.of(), List.of(), "", ""));
            images.forEach(i -> assets.require(actor, i.source().id()));
            return new Operation(fingerprint(input), "COMPLETED", null, persist(actor, document(request.requestId(), input,
                    images.stream().map(ImageData::source).toList(), List.of(), List.of(), response, response.draft().analysis())), "", Instant.now());
        }).document();
    }

    /** 开启 Plan：文字构想起步，图片可选；图像观察不会被升级为用户确认。 */
    public Plan plan(Generate request) {
        var actor = current.require(); Input input = normalized(request.input().input());
        if (input.mode() != Mode.CUSTOM_CREATION) throw ImagePromptException.invalid("参考图还原不发起创作问答。");
        return operate(actor, request.requestId(), fingerprint(input), () -> {
            var images = images(actor, input);
            var response = provider.invoke(new Call(Stage.PLAN, input, images, List.of(), List.of(), List.of(), "", ""));
            // 识图期间撤销或过期的资产不再向页面交付分析，也不建立可确认计划。
            images.forEach(i -> assets.require(actor, i.source().id()));
            Instant expiry = images.stream().map(i -> i.source().expiresAt()).min(Comparator.naturalOrder()).orElse(Instant.now().plus(PLAN_TTL));
            var sources = images.stream().map(ImageData::source).toList();
            String binding = fingerprint(List.of(input, sources));
            var view = new Plan(request.requestId(), binding, response.draft().summary(), response.draft().questions(),
                    response.draft().analysis(), response.draft().warnings(), expiry, response.mock());
            states.put(actor, "plan:" + view.id(), new BoundPlan(input, sources, view), Duration.between(Instant.now(), expiry));
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
            bound.sources().forEach(s -> assets.require(actor, s.id()));
            models.require(bound.input().modelId(), 0);
            var response = provider.invoke(new Call(Stage.GENERATE, bound.input(), List.of(), bound.view().analysis(),
                    bound.view().questions(), answers, "", ""));
            bound.sources().forEach(s -> assets.require(actor, s.id()));
            return new Operation(fingerprint, "COMPLETED", null, persist(actor, document(request.requestId(), bound.input(), bound.sources(),
                    bound.view().questions(), answers, response, bound.view().analysis())), "", Instant.now());
        }).document();
    }

    /** 再次优化从服务端已保存的当前正文出发，原图过期也可做文字修订，不声称重看图片。 */
    public Document rewrite(UUID id, Rewrite request) {
        var actor = current.require(); Document source = history.find(actor, id);
        if (source.editRevision() != request.expectedRevision()) throw ImagePromptException.conflict("历史记录已更新，请先重新读取后再优化。");
        safeText(request.instruction());
        return operate(actor, request.requestId(), fingerprint(List.of(id, source.editRevision(), request.instruction())), () -> {
            models.require(source.modelId(), 0);
            var response = provider.invoke(new Call(Stage.REWRITE, source.input(), List.of(), source.analysis(), source.questions(), source.answers(),
                    source.optimizedPrompt(), request.instruction()));
            // 再次核对逻辑删除与修订，旧模型响应不能越过中途删除或其他标签页的编辑。
            if (history.find(actor, id).editRevision() != source.editRevision()) throw ImagePromptException.conflict("原记录在生成期间已修改，请核对后重新操作。");
            var warnings = new ArrayList<>(response.draft().warnings()); warnings.add("本次基于已保存正文和分析摘要优化，未重新分析图片。");
            var adjusted = new ProviderResult(new ProviderDraft(response.draft().summary(), response.draft().optimizedPrompt(), response.draft().analysis(),
                    List.of(), List.copyOf(warnings)), response.modelId(), response.modelName(), response.mock());
            return new Operation(fingerprint(List.of(id, source.editRevision(), request.instruction())), "COMPLETED", null,
                    persist(actor, document(request.requestId(), source.input(), source.sources(), source.questions(), source.answers(), adjusted, source.analysis())), "", Instant.now());
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
        if (input.mode() == Mode.REFERENCE_RECONSTRUCTION) {
            if (input.images().size() != 1) throw ImagePromptException.invalid("直接增强需要一张参考图。");
            if (!input.creationIntent().isBlank() || !input.exactText().isBlank() || !input.context().isBlank()) {
                throw ImagePromptException.invalid("更换场景、添加文案或自定义画面请开启 Plan；直接增强只还原参考图。");
            }
        } else if (input.creationIntent().isBlank()) throw ImagePromptException.invalid("请先描述想创作的新图片。");
        safeText(input.creationIntent()); safeText(input.exactText()); safeText(input.context()); safeText(input.targetTool());
        models.require(input.modelId(), input.images().size());
        return input;
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
        String prompt = result.draft().optimizedPrompt();
        if (prompt == null || prompt.isBlank() || prompt.length() > 24000) throw ImagePromptException.invalid("模型未返回有效提示词。");
        if (!input.exactText().isBlank() && !prompt.contains(input.exactText())) {
            prompt += input.language() == Language.EN ? "\nRequired on-image text (verbatim):\n" : "\n画面文字（逐字保留）：\n";
            prompt += input.exactText();
        }
        safeText(prompt);
        return new Document(id, null, input.mode(), prompt, List.copyOf(analysis), result.draft().warnings(), input, sources, questions, answers,
                result.modelId(), result.modelName(), result.mock(), 0, false, history.persistent(), Instant.now());
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
    /** 服务端原输入及完整来源绑定；客户端只能提交 view 的指纹和实际答案。 */
    public record BoundPlan(Input input, List<ImageSource> sources, Plan view) { }
    /** 短期私有操作快照；状态为 RUNNING/COMPLETED/FAILED，超过等待期的查询返回 UNKNOWN。 */
    public record Operation(String fingerprint, String status, Plan plan, Document document, String message, Instant startedAt) { }
}
