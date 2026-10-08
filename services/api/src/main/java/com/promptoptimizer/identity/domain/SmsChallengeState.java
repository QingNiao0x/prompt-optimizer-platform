package com.promptoptimizer.identity.domain;

/**
 * 短信挑战状态，与 V13 的 CHECK 值域一致；到期另由 expires_at 判断。
 * @author QingNiao
 * @since 0.1.0
 */
public enum SmsChallengeState {
    /** 初始状态，已预留发送预算，等待云端受理。 */
    SENDING,
    /** 云端已受理发送，可以提交核验。 */
    SENT,
    /** 一个请求已独占本次云端核验，不允许并发重复核验。 */
    VERIFYING,
    /** 云端核验通过，等待业务事务消费，不延长有效期。 */
    VERIFIED,
    /** 业务结果已提交；短信登录不能再次建立会话。 */
    CONSUMED,
    /** 次数耗尽、云调用失败或结果不确定，终止挑战。 */
    FAILED,
    /** 同号码同用途的新挑战已替换旧挑战。 */
    SUPERSEDED
}
