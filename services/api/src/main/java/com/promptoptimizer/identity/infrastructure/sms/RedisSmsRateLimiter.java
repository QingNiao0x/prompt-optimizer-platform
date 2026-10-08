package com.promptoptimizer.identity.infrastructure.sms;

import com.promptoptimizer.identity.service.SmsException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * 跨短信用途共用预算；真实短信没有内存降级，不因供应商失败退回次数。
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class RedisSmsRateLimiter {
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
        local ttl = redis.call('PTTL', KEYS[1])
        if ttl > 0 then return math.ceil(ttl / 1000) end
        for i = 2, 4 do
          local maximum = tonumber(ARGV[i - 1])
          if maximum > 0 and tonumber(redis.call('GET', KEYS[i]) or '0') >= maximum then
            return math.max(1, redis.call('TTL', KEYS[i]))
          end
        end
        redis.call('SET', KEYS[1], '1', 'EX', 60)
        for i = 2, 4 do
          if tonumber(ARGV[i - 1]) > 0 then
            if redis.call('INCR', KEYS[i]) == 1 then redis.call('EXPIRE', KEYS[i], 3600) end
          end
        end
        return 0
        """, Long.class);
    private final StringRedisTemplate redis;
    private final SmsProperties properties;
    public RedisSmsRateLimiter(ObjectProvider<StringRedisTemplate> redis, SmsProperties properties) {
        this.redis = redis.getIfAvailable(); this.properties = properties;
    }

    /** 所有标识必须已经HMAC；单脚本同时预留号码、IP与可选操作者预算。 */
    public void reserve(String phone, String ip, String actor) {
        if (redis == null) throw SmsException.unavailable();
        String prefix = "prompt-optimizer:sms:{" + properties.getSchemePrefix() + "}:";
        Long wait;
        try {
            wait = redis.execute(RESERVE, List.of(prefix + "cooldown:" + phone, prefix + "phone:" + phone,
                    prefix + "ip:" + ip, prefix + "actor:" + (actor == null ? "anonymous" : actor)),
                    String.valueOf(properties.getPhoneHourlyLimit()), String.valueOf(properties.getIpHourlyLimit()),
                    String.valueOf(actor == null ? 0 : properties.getActorHourlyLimit()));
        } catch (RuntimeException exception) { throw SmsException.unavailable(); }
        if (wait == null) throw SmsException.unavailable();
        if (wait > 0) throw new SmsException(429, "SMS_RATE_LIMITED", "验证码请求过于频繁，请稍后重试。", wait);
    }
}
