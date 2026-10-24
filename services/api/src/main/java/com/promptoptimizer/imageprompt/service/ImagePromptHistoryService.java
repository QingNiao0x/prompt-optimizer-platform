package com.promptoptimizer.imageprompt.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.identity.service.ActorIdentity;
import com.promptoptimizer.imageprompt.domain.ImagePromptModels.*;
import com.promptoptimizer.imageprompt.domain.ImagePromptRecordRow;
import com.promptoptimizer.imageprompt.mapper.ImagePromptMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图像历史的完整作用域与编辑事务。正式模式使用 PostgreSQL；history.enabled=false 时仅供本机演示的有界内存记录。
 * 原图仅保留元数据，所有读写排除逻辑删除，普通管理员统计权限不能扩大读取范围。
 * @author QingNiao
 * @since 0.1.0
 */
@Service
public class ImagePromptHistoryService {
    private final ImagePromptMapper mapper;
    private final ObjectMapper json;
    private final boolean persistent;
    private final Map<UUID, MemoryRecord> memory = new ConcurrentHashMap<>();

    public ImagePromptHistoryService(ObjectProvider<ImagePromptMapper> mapper, ObjectMapper json,
                                     @Value("${app.history.enabled:true}") boolean persistent) {
        this.mapper = persistent ? mapper.getIfAvailable() : null; this.json = json; this.persistent = persistent;
        if (persistent && this.mapper == null) throw new IllegalStateException("图像历史需要数据库 Mapper");
    }

    /** 供界面区分 PostgreSQL 长期历史与服务重启即失效的演示记录。 */
    public boolean persistent() { return persistent; }

    /** 同一操作只创建同一记录；已删除记录不能通过保存重放重新出现。 */
    @Transactional
    public Document save(ActorIdentity actor, Document document) {
        Document saved = document.saved(document.operationId(), persistent);
        if (persistent) {
            mapper.insertRecord(new ImagePromptRecordRow(saved.recordId(), actor.tenantId(), actor.workspaceId(), actor.userId(),
                    encode(saved), saved.optimizedPrompt(), saved.editRevision(), OffsetDateTime.ofInstant(saved.createdAt(), ZoneOffset.UTC)), saved.mode().name());
            return find(actor, saved.recordId());
        }
        if (!memory.containsKey(saved.recordId()) && memory.size() >= 500) throw new ImagePromptException(503, "IMAGE_HISTORY_FULL", "演示历史容量已满；请复制正文，服务重启后演示历史会清空。");
        memory.putIfAbsent(saved.recordId(), new MemoryRecord(actor, saved, false));
        return find(actor, saved.recordId());
    }

    /** 不返回已删除、其他工作区或其他所有者的记录。 */
    public Document find(ActorIdentity actor, UUID id) {
        if (persistent) {
            var row = mapper.find(id, actor);
            if (row == null) throw ImagePromptException.missing();
            return decode(row);
        }
        var row = memory.get(id);
        if (row == null || row.deleted || !sameScope(actor, row.actor)) throw ImagePromptException.missing();
        return row.document;
    }

    /** SQL 层分页，内存演示沿用相同页码与模式筛选边界。 */
    public HistoryPage list(ActorIdentity actor, int current, int size, Mode mode) {
        if (current < 1 || current > 100000 || !Set.of(10, 20, 50).contains(size)) throw ImagePromptException.invalid("页码或每页数量无效。");
        if (persistent) {
            Page<ImagePromptRecordRow> page = new Page<>(current, size); page.setOptimizeCountSql(false);
            var result = mapper.list(page, actor, mode == null ? null : mode.name());
            return new HistoryPage(result.getRecords().stream().map(this::decode).toList(), result.getTotal(), size, current, result.getPages());
        }
        var records = memory.values().stream().filter(r -> !r.deleted && sameScope(actor, r.actor))
                .map(r -> r.document).filter(d -> mode == null || d.mode() == mode)
                .sorted(Comparator.comparing(Document::createdAt).reversed()).toList();
        return new HistoryPage(records.stream().skip((long) (current - 1) * size).limit(size).toList(), records.size(), size, current,
                (records.size() + size - 1L) / size);
    }

    /** 编辑使用乐观修订，不生成图片、不调用模型。 */
    @Transactional
    public Document edit(ActorIdentity actor, UUID id, String text, long revision) {
        if (text == null || text.isBlank() || text.length() > 30000 || revision < 0) throw ImagePromptException.invalid("正文不能为空且不能超过 30000 字符。");
        find(actor, id);
        if (persistent) {
            if (mapper.edit(id, actor, revision, text) != 1) throw ImagePromptException.conflict("记录已被修改，请重新读取后保存；当前草稿仍可保留。");
        } else {
            memory.compute(id, (key, row) -> {
                if (row == null || row.deleted || !sameScope(actor, row.actor)) throw ImagePromptException.missing();
                if (row.document.editRevision() != revision) throw ImagePromptException.conflict("记录已被修改，请重新读取后保存。");
                return new MemoryRecord(row.actor, row.document.edited(text, revision + 1), false);
            });
        }
        return find(actor, id);
    }

    /** 删除与编辑共享身份、版本化记录范围，正文不能从再次优化入口绕过删除。 */
    @Transactional
    public void delete(ActorIdentity actor, UUID id) {
        if (persistent) { if (mapper.delete(id, actor) != 1) throw ImagePromptException.missing(); }
        else memory.compute(id, (key, row) -> {
            if (row == null || row.deleted || !sameScope(actor, row.actor)) throw ImagePromptException.missing();
            return new MemoryRecord(row.actor, row.document, true);
        });
    }

    /** 内存演示也同时校验三个身份维度，不能以用户或管理员角色替代归属。 */
    private boolean sameScope(ActorIdentity a, ActorIdentity b) {
        return a.userId().equals(b.userId()) && a.workspaceId().equals(b.workspaceId()) && a.tenantId().equals(b.tenantId());
    }
    /** 只持久化业务文字及来源元数据，不包含临时图片字节。 */
    private String encode(Document value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("图像记录序列化失败", failure); }
    }
    /** 独立列中的当前正文和修订覆盖生成时 JSON 快照，避免编辑与历史不一致。 */
    private Document decode(ImagePromptRecordRow row) {
        try { return json.readValue(row.payload(), Document.class).edited(row.optimizedPrompt(), row.editRevision()); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("图像记录版本无法读取", failure); }
    }
    private record MemoryRecord(ActorIdentity actor, Document document, boolean deleted) { }
}
