package com.promptoptimizer.provider.infrastructure.concurrency;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

/**
 * 使用 Redis 原子脚本共享并发名额，租约以 Redis 时间为准，避免 API 实例时钟偏差。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class RedisModelConcurrencyStore implements ModelConcurrencyStore {

    private static final DefaultRedisScript<Long> ACQUIRE = script("acquire");
    private static final DefaultRedisScript<Long> RENEW = script("renew");
    private final StringRedisTemplate redis;

    RedisModelConcurrencyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean acquire(String key, String token, int limit, Duration leaseDuration) {
        return Long.valueOf(1).equals(redis.execute(ACQUIRE, List.of(key), token,
                Integer.toString(limit), Long.toString(leaseDuration.toMillis())));
    }

    @Override
    public boolean renew(String key, String token, Duration leaseDuration) {
        return Long.valueOf(1).equals(redis.execute(RENEW, List.of(key), token,
                Long.toString(leaseDuration.toMillis())));
    }

    @Override
    public void release(String key, String token) {
        redis.opsForZSet().remove(key, token);
    }

    /** 脚本是受版本管理的固定资源，外部标识只通过参数传入。 */
    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/model-concurrency/" + name + ".lua"));
        script.setResultType(Long.class);
        return script;
    }
}
