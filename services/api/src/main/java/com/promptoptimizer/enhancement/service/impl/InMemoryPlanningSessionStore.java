package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.service.PlanningSessionStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 不可用时使用的进程内短期计划会话存储，也用于核心逻辑单元测试。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public class InMemoryPlanningSessionStore implements PlanningSessionStore {

    private final Map<String, ContextSession> contexts = new ConcurrentHashMap<>();
    private final Map<String, PlanSession> plans = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryPlanningSessionStore() {
        this(Clock.systemUTC());
    }

    public InMemoryPlanningSessionStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void saveContext(ContextSession context) {
        removeExpired();
        contexts.put(context.reference().contextId(), context);
    }

    @Override
    public Optional<ContextSession> findContext(String contextId) {
        return active(contexts, contextId);
    }

    @Override
    public void savePlan(PlanSession plan) {
        removeExpired();
        plans.put(plan.planId(), plan);
    }

    @Override
    public Optional<PlanSession> findPlan(String planId) {
        return active(plans, planId);
    }

    /** 只返回未过期的短期会话；发现过期时立即从内存中移除。 */
    private <T> Optional<T> active(Map<String, T> values, String id) {
        T value = values.get(id);
        if (value == null) {
            return Optional.empty();
        }
        Instant expiresAt = value instanceof ContextSession context
                ? context.expiresAt()
                : ((PlanSession) value).expiresAt();
        if (!expiresAt.isAfter(clock.instant())) {
            values.remove(id, value);
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private void removeExpired() {
        Instant now = clock.instant();
        contexts.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        plans.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }
}
