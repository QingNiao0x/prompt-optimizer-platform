package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.PlanningContextReference;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 保存短期计划上下文和计划问题的最小持久化接口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface PlanningSessionStore {

    /** 保存带所有者与过期时间的短期上下文，不持久化为用户历史。 */
    void saveContext(ContextSession context);

    /** 根据上下文 ID 查找尚可用的会话；所有权由调用方校验。 */
    Optional<ContextSession> findContext(String contextId);

    /** 保存待用户确认的问题及其所属用户。 */
    void savePlan(PlanSession plan);

    /** 根据计划 ID 查找尚可用的计划；所有权由调用方校验。 */
    Optional<PlanSession> findPlan(String planId);

    record ContextSession(
            PlanningContextReference reference,
            UUID ownerUserId,
            String requestFingerprint,
            PlanningContextDigest digest,
            ContextSnapshot snapshot,
            Instant expiresAt
    ) {

        public ContextSession {
            Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        }
    }

    record PlanSession(
            String planId,
            UUID ownerUserId,
            String requestFingerprint,
            PlanningContextReference planningContext,
            List<PlanQuestion> questions,
            String modelId,
            Instant expiresAt
    ) {

        public PlanSession {
            Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
            questions = List.copyOf(questions);
        }

        /** 兼容不携带模型选择的现有测试与旧短期计划。 */
        public PlanSession(String planId, UUID ownerUserId, String requestFingerprint,
                           PlanningContextReference planningContext, List<PlanQuestion> questions,
                           Instant expiresAt) {
            this(planId, ownerUserId, requestFingerprint, planningContext, questions, null, expiresAt);
        }
    }
}
