package com.promptoptimizer.common.logging;

import java.util.Objects;
import java.util.UUID;

/**
 * 传递模型对账所需的服务端租户范围；不复制账号身份、认证会话或用户正文。
 * 异步任务必须显式捕获和恢复快照，线程池复用时恢复原上下文。
 */
public final class ModelCallAuditContext {
    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private ModelCallAuditContext() { }

    /** 捕获不可变租户范围；没有受信任范围时返回 null，禁止猜测默认租户。 */
    public static Snapshot capture() { return CURRENT.get(); }

    /** 临时绑定范围，关闭时恢复之前的值，支持嵌套和异步工作线程。 */
    public static Scope bind(Snapshot snapshot) {
        Snapshot previous = CURRENT.get();
        if (snapshot == null) CURRENT.remove(); else CURRENT.set(snapshot);
        return () -> {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        };
    }

    /** 仅包含租户与采集端口；userId 不进入模型对账上下文。 */
    public record Snapshot(UUID tenantId, Sink sink) {
        public Snapshot {
            Objects.requireNonNull(tenantId, "tenant id");
            Objects.requireNonNull(sink, "model audit sink");
        }
    }

    /** 采集已完成的一次模型尝试，不接收任意 JSON 或正文。 */
    @FunctionalInterface
    public interface Sink { void record(UUID tenantId, ModelCallObservation observation); }

    /** 无 checked exception 的范围恢复句柄。 */
    @FunctionalInterface
    public interface Scope extends AutoCloseable { @Override void close(); }
}
