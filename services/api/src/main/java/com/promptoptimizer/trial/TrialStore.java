package com.promptoptimizer.trial;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 访客额度和幂等执行账本。额度没有日期桶或过期重置；正文恢复快照仅保留24小时。
 * Redis Lua将总额检查、操作预占、共享来源预算和访客并发检查原子提交。
 * MEMORY只供显式Mock演示/测试，真实模型永不降级本地计数。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class TrialStore {
    private static final String PREFIX = "prompt-optimizer:trial:";
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[1]) == 0 then return -4 end
        if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then return 0 end
        if tonumber(redis.call('HGET', KEYS[1], ARGV[2]) or '0') >= tonumber(ARGV[3]) then return -1 end
        if tonumber(redis.call('HGET', KEYS[1], 'busyUntil') or '0') > tonumber(ARGV[4]) then return -2 end
        if tonumber(redis.call('GET', KEYS[2]) or '0') >= 30 or tonumber(redis.call('GET', KEYS[3]) or '0') >= 1000 then return -3 end
        redis.call('HINCRBY', KEYS[1], ARGV[2], 1)
        redis.call('HMSET', KEYS[1], ARGV[1], ARGV[5], 'busyUntil', ARGV[6], 'busyOperation', ARGV[1])
        redis.call('SET', KEYS[4], ARGV[7], 'EX', 86400)
        redis.call('INCR', KEYS[2]); redis.call('EXPIRE', KEYS[2], 86400)
        redis.call('INCR', KEYS[3]); redis.call('EXPIRE', KEYS[3], 86400)
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>("""
        redis.call('SET', KEYS[2], ARGV[2], 'EX', 86400)
        if redis.call('HGET', KEYS[1], 'busyOperation') == ARGV[1] then
          redis.call('HDEL', KEYS[1], 'busyOperation', 'busyUntil')
        end
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>("""
        if tonumber(redis.call('GET', KEYS[2]) or '0') >= 10 then return 0 end
        redis.call('INCR', KEYS[2]); redis.call('EXPIRE', KEYS[2], 3600)
        redis.call('HSET', KEYS[1], 'createdAt', ARGV[1])
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> UPLOAD = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
        if tonumber(redis.call('HGET', KEYS[1], 'uploads') or '0') >= 5 then return -2 end
        return redis.call('HINCRBY', KEYS[1], 'uploads', 1)
        """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean memory;
    private final Map<String, Map<String, String>> visitors = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Integer> budgets = new ConcurrentHashMap<>();

    public TrialStore(ObjectProvider<StringRedisTemplate> provider, ObjectMapper json,
            @Value("${app.trial.store-mode:REDIS}") String mode,
            @Value("${app.provider.mode:openai-compatible}") String providerMode) {
        this.redis = provider.getIfAvailable(); this.json = json;
        if (!Set.of("REDIS", "MEMORY").contains(mode)) throw new IllegalStateException("访客存储模式无效");
        memory = mode.equals("MEMORY");
        if (memory && !providerMode.equals("mock")) throw new IllegalStateException("真实模型访客试用必须使用Redis存储");
    }
    /** 读取不创建新身份；Redis故障与身份不存在必须区分。 */
    public synchronized boolean exists(String guest) {
        try { return memory ? visitors.containsKey(guest) : Boolean.TRUE.equals(requiredRedis().hasKey(visitor(guest))); }
        catch (RuntimeException failure) { throw TrialException.unavailable(failure); }
    }
    /** 每来源每小时最多建立10个访客身份，清Cookie不会绕过来源发送预算。 */
    public synchronized void create(String guest, String source) {
        try {
            String bucket = "create:" + source + ":" + Instant.now().getEpochSecond() / 3600;
            boolean accepted;
            if (memory) {
                accepted = visitors.size() < 10000 && budgets.getOrDefault(bucket, 0) < 10;
                if (accepted) { budgets.merge(bucket, 1, Integer::sum); visitors.put(guest, new HashMap<>()); }
            } else accepted = Long.valueOf(1).equals(requiredRedis().execute(CREATE,
                    List.of(visitor(guest), PREFIX + bucket), Instant.now().toString()));
            if (!accepted) throw new TrialException(429, "TRIAL_RATE_LIMITED", "创建体验会话过于频繁，请稍后重试或注册登录。");
        } catch (TrialException failure) { throw failure; }
        catch (RuntimeException failure) { throw TrialException.unavailable(failure); }
    }
    /** 使用持久随机盐摘要来源地址；盐只保存后端私有Redis，不输出原地址或盐。 */
    public synchronized String salt() {
        if (memory) return "explicit-mock-only-source-salt";
        try {
            String key = PREFIX + "source-salt";
            requiredRedis().opsForValue().setIfAbsent(key, UUID.randomUUID() + ":" + UUID.randomUUID());
            String value = requiredRedis().opsForValue().get(key);
            if (value == null) throw TrialException.unavailable();
            return value;
        } catch (RuntimeException failure) { throw TrialException.unavailable(failure); }
    }
    /** 返回服务器实际计数；没有以UTC日期或浏览器本地状态重置额度的路径。 */
    public synchronized int used(String guest, TrialFeature feature) {
        try {
            Object value = memory ? visitors.getOrDefault(guest, Map.of()).get(feature.key())
                    : requiredRedis().opsForHash().get(visitor(guest), feature.key());
            return value == null ? 0 : Integer.parseInt(value.toString());
        } catch (RuntimeException failure) { throw TrialException.unavailable(failure); }
    }

    /** 上传尝试预算独立于一次图像生成，限制反复上传/撤销造成的解码CPU与磁盘消耗。 */
    public synchronized void claimUpload(String guest) {
        try {
            Long count;
            if (memory) {
                var values = visitors.get(guest);
                if (values == null) count = -1L;
                else if (Long.parseLong(values.getOrDefault("uploads", "0")) >= 5) count = -2L;
                else {
                    count = Long.parseLong(values.getOrDefault("uploads", "0")) + 1;
                    values.put("uploads", count.toString());
                }
            // EXISTS与递增必须是同一原子操作；账本在检查后丢失时绝不重建空额度身份。
            } else count = requiredRedis().execute(UPLOAD, List.of(visitor(guest)));
            if (count == null) throw TrialException.unavailable();
            if (count == -1) throw new TrialException(401, "TRIAL_SESSION_REQUIRED", "体验会话已失效，请重新开启体验。");
            if (count == -2) throw new TrialException(429, "TRIAL_UPLOAD_LIMIT", "本次体验的图片上传次数已达上限，请注册登录后继续。");
        } catch (TrialException failure) { throw failure; }
        catch (RuntimeException failure) { throw TrialException.unavailable(failure); }
    }
    /** 返回true表示首次原子预占；false只允许恢复原操作，不再次执行模型。 */
    public synchronized boolean reserve(String guest, String source, TrialFeature feature, UUID id, String fingerprint) {
        String operation = operation(feature, id);
        long now = Instant.now().getEpochSecond();
        String day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        Snapshot pending = new Snapshot("RUNNING", fingerprint, null, Instant.now(), Instant.now().plusSeconds(86400));
        long decision;
        try {
            if (memory) {
                Map<String, String> values = visitors.get(guest);
                if (values == null) decision = -4;
                else if (values.containsKey(operation)) decision = 0;
                else if (used(guest, feature) >= feature.limit()) decision = -1;
                else if (Long.parseLong(values.getOrDefault("busyUntil", "0")) > now) decision = -2;
                else if (budgets.getOrDefault(source + day, 0) >= 30 || budgets.getOrDefault("global" + day, 0) >= 1000) decision = -3;
                else {
                    values.put(operation, fingerprint); values.put(feature.key(), Integer.toString(used(guest, feature) + 1));
                    values.put("busyUntil", Long.toString(now + 600)); values.put("busyOperation", operation);
                    snapshots.put(guest + operation, pending); budgets.merge(source + day, 1, Integer::sum); budgets.merge("global" + day, 1, Integer::sum);
                    decision = 1;
                }
            } else {
                Long result = requiredRedis().execute(RESERVE, List.of(visitor(guest), PREFIX + "source:" + source + ":" + day,
                        PREFIX + "global:" + day, snapshot(guest, operation)), operation, feature.key(), Integer.toString(feature.limit()),
                        Long.toString(now), fingerprint, Long.toString(now + 600), json.writeValueAsString(pending));
                if (result == null) throw TrialException.unavailable(); decision = result;
            }
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) { throw TrialException.unavailable(failure); }
        if (decision == -1) throw new TrialException(429, "TRIAL_QUOTA_EXHAUSTED", "该功能的免费体验次数已用完，请注册登录后继续。");
        if (decision == -2) throw new TrialException(429, "TRIAL_REQUEST_RUNNING", "已有体验请求正在处理，请等待完成后再试。");
        if (decision == -3) throw new TrialException(429, "TRIAL_RATE_LIMITED", "免费体验请求较多，请稍后重试或注册登录。");
        if (decision == -4) throw new TrialException(401, "TRIAL_SESSION_REQUIRED", "体验会话已失效，请重新开启体验。");
        return decision == 1;
    }
    /** 完成或失败都不退回已经发起的尝试；超时不证明下游没有发生费用。 */
    public synchronized void finish(String guest, TrialFeature feature, UUID id, Snapshot state) {
        try {
            String operation = operation(feature, id);
            if (memory) {
                snapshots.put(guest + operation, state);
                var values = visitors.get(guest);
                if (values != null && operation.equals(values.get("busyOperation"))) { values.remove("busyUntil"); values.remove("busyOperation"); }
            } else if (requiredRedis().execute(FINISH, List.of(visitor(guest), snapshot(guest, operation)), operation,
                    json.writeValueAsString(state)) == null) throw TrialException.unavailable();
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) { throw TrialException.unavailable(failure); }
    }
    /** 仅查询同一访客、功能和操作的短期原件，过期不能借旧操作编号重新调用模型。 */
    public synchronized Snapshot get(String guest, TrialFeature feature, UUID id) {
        try {
            Snapshot state;
            if (memory) state = snapshots.get(guest + operation(feature, id));
            else {
                String value = requiredRedis().opsForValue().get(snapshot(guest, operation(feature, id)));
                state = value == null ? null : json.readValue(value, Snapshot.class);
            }
            return state != null && state.expiresAt().isAfter(Instant.now()) ? state : null;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException failure) { throw TrialException.unavailable(failure); }
    }
    private String visitor(String guest) { return PREFIX + "visitor:" + guest; }
    private String operation(TrialFeature feature, UUID id) { return "op:" + feature.key() + ":" + id; }
    private String snapshot(String guest, String operation) { return PREFIX + "snapshot:" + guest + ":" + operation; }
    private StringRedisTemplate requiredRedis() { if (redis == null) throw TrialException.unavailable(); return redis; }
    /** 正文只保存私有短期JSON，状态与指纹不作为客户端授权。 */
    public record Snapshot(String status, String fingerprint, String resultJson, Instant startedAt, Instant expiresAt) { }
}
