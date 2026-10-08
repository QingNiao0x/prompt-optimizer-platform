package com.promptoptimizer.identity.infrastructure.sms;

import com.promptoptimizer.identity.service.SmsException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 存储不可用、未返回结果和并发限流不能退化为允许收费发送。 */
class RedisSmsRateLimiterTest {
    @Test void failsClosedWithoutRedis() {
        var limiter=new RedisSmsRateLimiter(new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class),new SmsProperties());
        assertThatThrownBy(() -> limiter.reserve("phone-hmac","ip-hmac",null)).isInstanceOf(SmsException.class)
                .extracting(e -> ((SmsException)e).getStatus()).isEqualTo(503);
    }

    @Test void failsClosedOnNullOrRedisExceptionWithoutLeakingMessage() {
        var redis=mock(StringRedisTemplate.class); var beans=new StaticListableBeanFactory(); beans.addBean("redis",redis);
        var limiter=new RedisSmsRateLimiter(beans.getBeanProvider(StringRedisTemplate.class),new SmsProperties());
        assertThatThrownBy(() -> limiter.reserve("phone-hmac","ip-hmac",null)).isInstanceOf(SmsException.class);
        doThrow(new org.springframework.data.redis.RedisSystemException("private-redis-parameters",new RuntimeException()))
                .when(redis).execute(any(RedisScript.class),anyList(),any(Object[].class));
        assertThatThrownBy(() -> limiter.reserve("phone-hmac","ip-hmac",null)).isInstanceOf(SmsException.class).hasMessageNotContaining("private");
    }
}
