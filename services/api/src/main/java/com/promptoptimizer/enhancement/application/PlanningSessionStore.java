package com.promptoptimizer.enhancement.application;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.enhancement.api.PlanningContextReference;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 保存短期计划上下文和计划问题的最小持久化接口。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface PlanningSessionStore {

    void saveContext(ContextSession context);

    Optional<ContextSession> findContext(String contextId);

    void savePlan(PlanSession plan);

    Optional<PlanSession> findPlan(String planId);

    record ContextSession(
            PlanningContextReference reference,
            String requestFingerprint,
            PlanningContextDigest digest,
            ContextSnapshot snapshot,
            Instant expiresAt
    ) {
    }

    record PlanSession(
            String planId,
            String requestFingerprint,
            PlanningContextReference planningContext,
            List<PlanQuestion> questions,
            Instant expiresAt
    ) {

        public PlanSession {
            questions = List.copyOf(questions);
        }
    }
}
