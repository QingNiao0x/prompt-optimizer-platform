package com.promptoptimizer.textpolish.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.textpolish.domain.TextPolishModels.*;
import com.promptoptimizer.textpolish.domain.TextPolishRecordRow;
import com.promptoptimizer.textpolish.mapper.TextPolishMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 润色私有历史；生成快照不可变，编辑通过独立正文列与CAS修订完成，删除永不恢复。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class TextPolishHistoryService {
    private final TextPolishMapper mapper;
    private final ObjectMapper json;
    private final TextPolishFidelity fidelity;
    private final Map<UUID, MemoryRecord> memory = new ConcurrentHashMap<>();
    private final boolean persistent;
    public TextPolishHistoryService(ObjectProvider<TextPolishMapper> provider, ObjectMapper json, TextPolishFidelity fidelity,
                                     @Value("${app.history.enabled:true}") boolean persistent) {
        this.mapper = persistent ? provider.getIfAvailable() : null; this.json = json; this.fidelity = fidelity; this.persistent = persistent;
        if (persistent && mapper == null) throw new IllegalStateException("文章历史需要数据库Mapper");
    }
    public boolean persistent() { return persistent; }
    /** 幂等插入，重复保存不会覆盖编辑稿或复活已删除记录。 */
    @Transactional
    public Document save(ActorIdentity actor, Document value) {
        var saved = value.saved(persistent);
        if (persistent) mapper.insertRecord(new TextPolishRecordRow(saved.id(), actor.tenantId(), actor.workspaceId(), actor.userId(), saved.sourceRecordId(),
                encode(saved), saved.polishedText(), saved.editRevision(), OffsetDateTime.ofInstant(saved.createdAt(), ZoneOffset.UTC)));
        else {
            if (!memory.containsKey(saved.id()) && memory.size() >= 500) throw new TextPolishException(503, "TEXT_POLISH_HISTORY_FULL", "演示历史容量已满，请复制当前正文。");
            memory.putIfAbsent(saved.id(), new MemoryRecord(actor, saved, false));
        }
        return find(actor, saved.id());
    }
    /** 三重归属与未删除条件同查；旧缓存和来源关联不得绕过。 */
    public Document find(ActorIdentity actor, UUID id) {
        if (persistent) { var row = mapper.find(id, actor); if (row == null) throw TextPolishException.missing(); return decode(row); }
        var row = memory.get(id); requireScope(actor, row); return row.document();
    }
    /** 每页10/20/50条，真实数据库分页，避免一次读取全部私有正文。 */
    public HistoryPage list(ActorIdentity actor, int current, int size) {
        if (current < 1 || current > 100000 || !Set.of(10, 20, 50).contains(size)) throw TextPolishException.invalid("历史页码或每页数量无效。");
        if (persistent) {
            var page = mapper.list(new Page<>(current, size), actor);
            return new HistoryPage(page.getRecords().stream().map(this::decode).toList(), page.getTotal(), size, current, page.getPages());
        }
        var records = memory.values().stream().filter(r -> !r.deleted() && r.actor().userId().equals(actor.userId())
                && r.actor().tenantId().equals(actor.tenantId()) && r.actor().workspaceId().equals(actor.workspaceId()))
                .map(MemoryRecord::document).sorted(Comparator.comparing(Document::createdAt).reversed()).toList();
        return new HistoryPage(records.stream().skip((long)(current - 1) * size).limit(size).toList(), records.size(), size, current, (records.size() + size - 1L) / size);
    }
    /** 编辑仍保护原文锁定内容，冲突保留旧记录，客户端保留未保存草稿。 */
    @Transactional
    public Document edit(ActorIdentity actor, UUID id, String text, long revision) {
        var source = find(actor, id); fidelity.check(source.input(), text);
        if (persistent) { if (mapper.edit(id, actor, revision, text) != 1) throw TextPolishException.conflict("记录已更新，请保留当前草稿并重新读取。"); }
        else memory.compute(id, (key, row) -> {
            requireScope(actor, row);
            if (row.document().editRevision() != revision) throw TextPolishException.conflict("记录已更新，请保留当前草稿并重新读取。");
            return new MemoryRecord(row.actor(), row.document().edited(text, revision + 1, fidelity.check(row.document().input(), text)), false);
        });
        return find(actor, id);
    }
    /** 所有业务删除只打标志；编辑、再次润色、操作恢复均需重新读取此状态。 */
    @Transactional
    public void delete(ActorIdentity actor, UUID id) {
        if (persistent) { if (mapper.delete(id, actor) != 1) throw TextPolishException.missing(); }
        else memory.compute(id, (key, row) -> { requireScope(actor, row); return new MemoryRecord(row.actor(), row.document(), true); });
    }
    private void requireScope(ActorIdentity actor, MemoryRecord row) {
        if (row == null || row.deleted() || !row.actor().userId().equals(actor.userId()) || !row.actor().tenantId().equals(actor.tenantId())
                || !row.actor().workspaceId().equals(actor.workspaceId())) throw TextPolishException.missing();
    }
    private String encode(Document value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException failure) { throw new IllegalStateException("润色记录无法编码", failure); } }
    private Document decode(TextPolishRecordRow row) {
        try { var doc = json.readValue(row.payload(), Document.class); return doc.edited(row.polishedText(), row.editRevision(), fidelity.check(doc.input(), row.polishedText())); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("润色记录无法读取", failure); }
    }
    private record MemoryRecord(ActorIdentity actor, Document document, boolean deleted) { }
}
