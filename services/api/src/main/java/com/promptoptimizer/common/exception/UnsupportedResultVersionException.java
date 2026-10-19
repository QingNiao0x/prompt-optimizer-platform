package com.promptoptimizer.common.exception;

/**
 * 结果形态或生成版本不能被当前接口安全处理，要求客户端显式升级或恢复原始需求。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class UnsupportedResultVersionException extends InvalidOptimizationRequestException {
    /** 不把新正文伪装成旧段落，也不猜测未知版本的再次增强策略。 */
    public UnsupportedResultVersionException(String message) {
        super(message);
    }
}
