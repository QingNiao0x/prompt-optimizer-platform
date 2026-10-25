package com.promptoptimizer.textpolish.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.analytics.domain.AnalyticsEventType;
import com.promptoptimizer.analytics.service.AnalyticsEventService;
import com.promptoptimizer.common.logging.LogCorrelation;
import com.promptoptimizer.identity.service.*;
import com.promptoptimizer.textpolish.domain.TextPolishModels.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/**
 * 组织单次文章润色、版本来源、幂等恢复和私有历史；不调用提示词增强或Plan。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class TextPolishService {
    private static final Duration TTL = Duration.ofHours(24);
    private final CurrentActor current;
    private final TextPolishModelService models;
    private final TextPolishProvider provider;
    private final TextPolishHistoryService history;
    private final TextPolishStateStore states;
    private final TextPolishFidelity fidelity;
    private final ObjectMapper json;
    private final AnalyticsEventService analytics;
    /** 装配文章独立生成与审计；不把模型重试、状态恢复或补存当作新提交。 */
    public TextPolishService(CurrentActor current, TextPolishModelService models, TextPolishProvider provider,
            TextPolishHistoryService history, TextPolishStateStore states, TextPolishFidelity fidelity, ObjectMapper json,
            AnalyticsEventService analytics) {
        this.current = current; this.models = models; this.provider = provider; this.history = history;
        this.states = states; this.fidelity = fidelity; this.json = json; this.analytics = analytics;
    }
    /** 页面限制和可用模型来自服务端当前策略。 */
    public Capabilities capabilities() { current.require(); return new Capabilities(models.models(), 12000, 24000, history.persistent()); }
    /** 访客只能提交主动输入的原文，禁止借来源编号读取或继续正式历史。 */
    public void validateTrial(Generate request) {
        if (request.sourceMode() != SourceMode.RAW || request.sourceRecordId() != null || request.expectedRevision() != null)
            throw new com.promptoptimizer.trial.TrialException(401, "AUTHENTICATION_REQUIRED", "请注册登录后使用历史稿件。");
        fidelity.validateInput(request.input());
        models.require(request.input().modelId());
    }

    /** 复用同一模型、输入与事实保护，访客结果不落入正式账号历史。 */
    public Document generateTrial(Generate request) {
        validateTrial(request);
        var model = models.require(request.input().modelId());
        Input in = request.input();
        Input input = new Input(in.sourceText(), in.style(), in.depth(), in.audience(), in.requirements(), in.lockedTexts(), model.publicId());
        String text = provider.polish(input, model);
        return new Document(request.requestId(), input, text, text, null, SourceMode.RAW, model.publicId(), model.displayName(),
                model.routeKey().equals("mock"), 0, fidelity.check(input, text), false, false, Instant.now());
    }

    /** 提交前冻结来源；并发编辑或删除发生在模型期间时拒绝把迟到响应关联到旧版本。 */
    public Document generate(Generate request) {
        var actor = current.require();
        Document source = source(actor, request);
        Input in = request.input();
        if (source != null) in = new Input(request.sourceMode() == SourceMode.ORIGINAL ? source.input().sourceText() : source.polishedText(),
                in.style(), in.depth(), in.audience(), in.requirements(), in.lockedTexts(), in.modelId());
        fidelity.validateInput(in);
        var model = models.require(in.modelId());
        Input input = new Input(in.sourceText(), in.style(), in.depth(), in.audience(), in.requirements(), in.lockedTexts(), model.publicId());
        String fingerprint = fingerprint(List.of(input, request.sourceRecordId() == null ? "" : request.sourceRecordId(),
                request.expectedRevision() == null ? -1 : request.expectedRevision(), request.sourceMode()));
        Operation pending = new Operation("RUNNING", fingerprint, null, Instant.now());
        if (!states.claim(actor, request.requestId().toString(), pending, TTL)) {
            var existing = operation(request.requestId());
            if (!existing.fingerprint().equals(fingerprint)) throw TextPolishException.conflict("请求标识已用于其他输入，请使用新的生成操作。");
            if (!existing.status().equals("COMPLETED")) throw TextPolishException.conflict("该次请求仍在处理或已结束，请查询结果后显式发起新操作。");
            return existing.document();
        }
        try (var correlation = LogCorrelation.bindWorkflow("text-polish", request.requestId().toString())) {
            // 幂等预留和服务端来源/模型校验通过后才计提交；模型失败保留尝试，不记成功结果。
            analytics.recordToolOperation(AnalyticsEventType.TEXT_POLISH_SUBMITTED, request.requestId());
            String text = provider.polish(input, model);
            var checks = fidelity.check(input, text);
            if (source != null && history.find(actor, source.id()).editRevision() != source.editRevision())
                throw TextPolishException.conflict("来源稿在生成期间已修改，已保留当前稿，请重新核对后生成。");
            var doc = new Document(request.requestId(), input, text, text, request.sourceRecordId(), request.sourceMode(),
                    model.publicId(), model.displayName(), model.routeKey().equals("mock"), 0, checks, false, history.persistent(), Instant.now());
            // 已经生成的结果落库失败时留在私有短期状态中，重试保存不触发新模型调用。
            try { doc = history.save(actor, doc); }
            catch (DataAccessException failure) { /* 返回可恢复正文，页面明确显示历史未保存。 */ }
            states.put(actor, request.requestId().toString(), new Operation("COMPLETED", fingerprint, doc, pending.startedAt()), TTL);
            analytics.recordToolOperation(AnalyticsEventType.TEXT_POLISH_GENERATED, request.requestId());
            return doc;
        } catch (RuntimeException failure) {
            states.put(actor, request.requestId().toString(), new Operation("FAILED", fingerprint, null, pending.startedAt()), TTL);
            throw failure;
        }
    }
    /** 恢复查询不调用模型；已落库结果始终重新读取删除状态和当前编辑稿。 */
    public Operation operation(UUID id) {
        var actor = current.require();
        var op = states.get(actor, id.toString(), Operation.class);
        if (op == null) throw TextPolishException.missing();
        if (op.document() != null) {
            if (op.document().sourceRecordId() != null) history.find(actor, op.document().sourceRecordId());
            if (op.document().historySaved()) return new Operation(op.status(), op.fingerprint(), history.find(actor, id), op.startedAt());
        }
        if (op.status().equals("RUNNING") && op.startedAt().plusSeconds(300).isBefore(Instant.now()))
            return new Operation("UNKNOWN", op.fingerprint(), null, op.startedAt());
        return op;
    }
    /** 重试落库只使用服务端已生成原件，不能复活已删除的记录。 */
    public Document save(UUID id) {
        var op = operation(id);
        if (op.document() == null) throw TextPolishException.conflict("该次操作没有可保存的正文。");
        var saved = history.save(current.require(), op.document());
        states.put(current.require(), id.toString(), new Operation("COMPLETED", op.fingerprint(), saved, op.startedAt()), TTL);
        return saved;
    }
    /** 原文重生与当前稿继续采用明确来源；不接受客户端自报正文替代服务端来源版本。 */
    private Document source(ActorIdentity actor, Generate request) {
        if (request.sourceMode() == SourceMode.RAW) {
            if (request.sourceRecordId() != null || request.expectedRevision() != null) throw TextPolishException.invalid("新原文不能附带历史来源修订。");
            return null;
        }
        if (request.sourceRecordId() == null || request.expectedRevision() == null) throw TextPolishException.invalid("请指定来源记录和修订号。");
        var doc = history.find(actor, request.sourceRecordId());
        if (doc.editRevision() != request.expectedRevision()) throw TextPolishException.conflict("来源记录已更新，请保留编辑稿并重新读取。");
        return doc;
    }
    /** 指纹只用于等价判断，不写入技术日志，不接受模型自报身份或确认状态。 */
    private String fingerprint(Object input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(input).getBytes(StandardCharsets.UTF_8))); }
        catch (JsonProcessingException | NoSuchAlgorithmException failure) { throw new IllegalStateException("润色请求无法绑定", failure); }
    }
    /** 24小时短期操作状态，不包含异常正文；页面可查询以避免断网重放。 */
    public record Operation(String status, String fingerprint, Document document, Instant startedAt) { }
}
