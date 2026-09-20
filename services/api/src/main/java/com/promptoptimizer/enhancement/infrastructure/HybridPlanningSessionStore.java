package com.promptoptimizer.enhancement.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.enhancement.application.InMemoryPlanningSessionStore;
import com.promptoptimizer.enhancement.application.PlanningSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 优先使用 Redis 保存跨请求计划会话，并保留进程内短期副本作为本地联调降级。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class HybridPlanningSessionStore implements PlanningSessionStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(HybridPlanningSessionStore.class);
    // 不读取上线认证前没有所有者的缓存；旧记录由原 TTL 自然清理。
    private static final String CONTEXT_KEY_PREFIX = "prompt-optimizer:planning-context:v2:";
    private static final String PLAN_KEY_PREFIX = "prompt-optimizer:plan:v2:";

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final InMemoryPlanningSessionStore fallback = new InMemoryPlanningSessionStore();

    public HybridPlanningSessionStore(
            ObjectMapper objectMapper,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider
    ) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
    }

    @Override
    public void saveContext(ContextSession context) {
        fallback.saveContext(context);
        write(CONTEXT_KEY_PREFIX + context.reference().contextId(), context, context.expiresAt());
    }

    @Override
    public Optional<ContextSession> findContext(String contextId) {
        Optional<ContextSession> local = fallback.findContext(contextId);
        return local.isPresent()
                ? local
                : read(CONTEXT_KEY_PREFIX + contextId, ContextSession.class)
                        .flatMap(value -> {
                            fallback.saveContext(value);
                            return fallback.findContext(contextId);
                        });
    }

    @Override
    public void savePlan(PlanSession plan) {
        fallback.savePlan(plan);
        write(PLAN_KEY_PREFIX + plan.planId(), plan, plan.expiresAt());
    }

    @Override
    public Optional<PlanSession> findPlan(String planId) {
        Optional<PlanSession> local = fallback.findPlan(planId);
        return local.isPresent()
                ? local
                : read(PLAN_KEY_PREFIX + planId, PlanSession.class)
                        .flatMap(value -> {
                            fallback.savePlan(value);
                            return fallback.findPlan(planId);
                        });
    }

    private void write(String key, Object value, Instant expiresAt) {
        if (redisTemplate == null) {
            return;
        }
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (RuntimeException | JsonProcessingException exception) {
            LOGGER.warn(
                    "Redis 计划会话写入失败，当前请求继续使用进程内短期存储；原因类型：{}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        if (redisTemplate == null) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, type));
        } catch (RuntimeException | JsonProcessingException exception) {
            LOGGER.warn(
                    "Redis 计划会话读取失败，当前请求继续使用进程内短期存储；原因类型：{}",
                    exception.getClass().getSimpleName()
            );
            return Optional.empty();
        }
    }
}
