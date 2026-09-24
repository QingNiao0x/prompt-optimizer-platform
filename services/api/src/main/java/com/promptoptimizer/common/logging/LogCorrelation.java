package com.promptoptimizer.common.logging;

import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 为多请求业务流程提供不暴露原始资源标识的日志关联值。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class LogCorrelation {

    private static final String WORKFLOW_ID_KEY = "workflowId";
    private static final int CORRELATION_BYTES = 10;

    private LogCorrelation() {
    }

    /** 将当前线程日志上下文临时绑定到资源标识的短哈希，并在离开作用域后恢复旧值。 */
    public static Scope bindWorkflow(String namespace, String resourceId) {
        String previous = MDC.get(WORKFLOW_ID_KEY);
        if (resourceId == null || resourceId.isBlank()) {
            return () -> restore(previous);
        }
        MDC.put(WORKFLOW_ID_KEY, fingerprint(namespace, resourceId));
        return () -> restore(previous);
    }

    /** 在线程切换后安全恢复触发异步任务的请求 ID，避免线程池复用时串入其他请求。 */
    public static Scope bindRequestId(String requestId) {
        String previous = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            return () -> restore("requestId", previous);
        }
        MDC.put("requestId", LogFields.value(requestId));
        return () -> restore("requestId", previous);
    }

    /** 返回不包含原始上下文或文档 ID 的稳定关联值。 */
    public static String fingerprint(String namespace, String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return "-";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(safeNamespace(namespace).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            byte[] hash = digest.digest(resourceId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, CORRELATION_BYTES);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 是 Java 平台必须提供的算法；此分支代表运行环境损坏，不能退回记录原始 ID。
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    private static String safeNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return "workflow";
        }
        return namespace.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void restore(String previous) {
        restore(WORKFLOW_ID_KEY, previous);
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previous);
        }
    }

    /** 无 checked exception 的 MDC 作用域，便于 try-with-resources 安全恢复上下文。 */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
