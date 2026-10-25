package com.promptoptimizer.imageprompt.service;

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
 * 图像 Plan 与幂等结果的短期私有存储。生产使用 Redis 原子预留，故障不降级为各实例重复调用。
 * MEMORY 仅在显式本机配置中使用；不持久化图片字节，键包含完整服务端身份范围。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class ImagePromptStateStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean memory;
    private final ConcurrentHashMap<String, Entry> local = new ConcurrentHashMap<>();

    public ImagePromptStateStore(ObjectProvider<StringRedisTemplate> provider, ObjectMapper json,
                                 @Value("${app.image-prompt.store-mode:REDIS}") String mode) {
        this.redis = provider.getIfAvailable(); this.json = json;
        if (!mode.equals("REDIS") && !mode.equals("MEMORY")) throw new IllegalStateException("图像状态存储模式无效");
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
        } catch (ImagePromptException failure) { throw failure; }
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

    /** 先限定身份再反序列化，无法读取或已过期的计划不回退到客户端内容。 */
    public <T> T get(ActorIdentity actor, String name, Class<T> type) {
        try {
            String value;
            if (!memory) value = requiredRedis().opsForValue().get(key(actor, name));
            else { prune(); Entry entry = local.get(key(actor, name)); value = entry == null ? null : entry.value(); }
            return value == null ? null : json.readValue(value, type);
        } catch (RuntimeException | JsonProcessingException failure) { throw unavailable(); }
    }

    /** 访客资产独立命名空间，禁止构造正式ActorIdentity或访问账号缓存。 */
    public void putGuest(String guest, String name, Object value, Duration ttl) {
        String key = guestKey(guest, name);
        try {
            if (!memory) requiredRedis().opsForValue().set(key, encode(value), ttl);
            else { prune(); if (!local.containsKey(key) && local.size() >= 1000) throw unavailable();
                local.put(key, new Entry(encode(value), Instant.now().plus(ttl))); }
        } catch (RuntimeException failure) { throw unavailable(); }
    }

    /** 所有者只由服务端试用会话提供；访客编号不能命中正式租户命名空间。 */
    public <T> T getGuest(String guest, String name, Class<T> type) {
        try {
            String key = guestKey(guest, name);
            Entry entry = memory ? local.get(key) : null;
            String value = memory ? entry != null && entry.expiresAt().isAfter(Instant.now()) ? entry.value() : null
                    : requiredRedis().opsForValue().get(key);
            return value == null ? null : json.readValue(value, type);
        } catch (RuntimeException | JsonProcessingException failure) { throw unavailable(); }
    }

    private String guestKey(String guest, String name) {
        if (guest == null || !guest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("访客资产作用域无效");
        return "prompt-optimizer:image:guest:" + guest + ":" + name;
    }

    /** 当前身份构成存储前缀，缓存编号不能跨租户、工作区或用户访问。 */
    private String key(ActorIdentity actor, String name) {
        return "prompt-optimizer:image:" + actor.tenantId() + ":" + actor.workspaceId() + ":" + actor.userId() + ":" + name;
    }
    /** 状态 JSON 只进入私有存储；序列化失败时拒绝继续模型操作。 */
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw unavailable(); }
    }
    private StringRedisTemplate requiredRedis() { if (redis == null) throw unavailable(); return redis; }
    private void prune() { Instant now = Instant.now(); local.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(now)); }
    private ImagePromptException unavailable() { return new ImagePromptException(503, "IMAGE_PROMPT_STORE_UNAVAILABLE", "图像计划与结果存储暂不可用，请稍后重试。"); }
    private record Entry(String value, Instant expiresAt) { }
}
