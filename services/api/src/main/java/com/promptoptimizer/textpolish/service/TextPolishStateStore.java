package com.promptoptimizer.textpolish.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.identity.service.ActorIdentity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文章润色操作与幂等结果的短期私有存储。生产使用Redis原子预留，故障不降级为各实例重复调用。
 * MEMORY仅在显式本机配置中使用；正文JSON仅在24小时私有状态中暂存，键包含完整服务端身份范围。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class TextPolishStateStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean memory;
    private final ConcurrentHashMap<String, Entry> local = new ConcurrentHashMap<>();

    public TextPolishStateStore(ObjectProvider<StringRedisTemplate> provider, ObjectMapper json,
                                 @Value("${app.text-polish.store-mode:REDIS}") String mode) {
        this.redis = provider.getIfAvailable(); this.json = json;
        if (!mode.equals("REDIS") && !mode.equals("MEMORY")) throw new IllegalStateException("文章润色状态存储模式无效");
        this.memory = mode.equals("MEMORY");
    }

    /** 原子预留操作，同一幂等键只有一个调用方可以发起模型请求。 */
    public boolean claim(ActorIdentity actor, String name, Object value, Duration ttl) {
        String key = key(actor, name); String encoded = encode(value);
        try {
            if (!memory) {
                Boolean claimed = requiredRedis().opsForValue().setIfAbsent(key, encoded, ttl);
                if (claimed == null) throw unavailable();
                return claimed;
            }
            prune();
            if (local.size() >= 1000) throw unavailable();
            return local.putIfAbsent(key, new Entry(encoded, Instant.now().plus(ttl))) == null;
        } catch (TextPolishException failure) { throw failure; }
        catch (RuntimeException failure) { throw unavailable(); }
    }

    /** 更新已预留的短期快照；保存的是私有业务 JSON，不进入日志。 */
    public void put(ActorIdentity actor, String name, Object value, Duration ttl) {
        String key = key(actor, name); String encoded = encode(value);
        try {
            if (!memory) requiredRedis().opsForValue().set(key, encoded, ttl);
            else { prune(); if (!local.containsKey(key) && local.size() >= 1000) throw unavailable();
                local.put(key, new Entry(encoded, Instant.now().plus(ttl))); }
        } catch (RuntimeException failure) { throw unavailable(); }
    }

    /** 先限定身份再反序列化，无法读取或已过期的操作不回退到客户端内容。 */
    public <T> T get(ActorIdentity actor, String name, Class<T> type) {
        try {
            String value;
            if (!memory) value = requiredRedis().opsForValue().get(key(actor, name));
            else { prune(); Entry entry = local.get(key(actor, name)); value = entry == null ? null : entry.value(); }
            return value == null ? null : json.readValue(value, type);
        } catch (RuntimeException | JsonProcessingException failure) { throw unavailable(); }
    }

    /** 当前身份构成存储前缀，缓存编号不能跨租户、工作区或用户访问。 */
    private String key(ActorIdentity actor, String name) {
        return "prompt-optimizer:text-polish:" + actor.tenantId() + ":" + actor.workspaceId() + ":" + actor.userId() + ":" + name;
    }
    /** 状态 JSON 只进入私有存储；序列化失败时拒绝继续模型操作。 */
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw unavailable(); }
    }
    private StringRedisTemplate requiredRedis() { if (redis == null) throw unavailable(); return redis; }
    private void prune() { Instant now = Instant.now(); local.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now)); }
    private TextPolishException unavailable() { return new TextPolishException(503, "TEXT_POLISH_STORE_UNAVAILABLE", "文章润色操作与结果存储暂不可用，请稍后重试。"); }
    private record Entry(String value, Instant expiresAt) { }
}
