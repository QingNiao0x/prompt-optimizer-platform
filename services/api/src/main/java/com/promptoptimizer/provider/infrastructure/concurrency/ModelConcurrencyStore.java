package com.promptoptimizer.provider.infrastructure.concurrency;

import java.time.Duration;

/**
 * 按作用域原子管理有租期的并发名额，令牌只标识本次调用，不包含提示词或凭据。
 *
 * @author QingNiao
 * @since 0.1.0
 */
interface ModelConcurrencyStore {

    /** 清理过期令牌后原子检查容量并占用一个名额。 */
    boolean acquire(String key, String token, int limit, Duration leaseDuration);

    /** 只延长仍有效的原令牌，不能复活已释放或已经过期的调用。 */
    boolean renew(String key, String token, Duration leaseDuration);

    /** 仅释放自己的令牌，重复释放不得影响其他请求。 */
    void release(String key, String token);
}
