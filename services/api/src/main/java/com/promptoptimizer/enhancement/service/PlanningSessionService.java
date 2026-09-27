package com.promptoptimizer.enhancement.service;

import com.promptoptimizer.context.domain.ContextSnapshot;
import com.promptoptimizer.context.dto.ContextAnalysisRequest;
import com.promptoptimizer.context.dto.PlanningContextRequest;
import com.promptoptimizer.enhancement.domain.PlanQuestion;
import com.promptoptimizer.enhancement.domain.PlanningContextDigest;
import com.promptoptimizer.enhancement.domain.PlanningContextPreparation;
import com.promptoptimizer.enhancement.dto.ConversationMessage;
import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.enhancement.dto.PlanConfirmation;
import com.promptoptimizer.enhancement.dto.PlanningContextReference;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 计划上下文、确认问题和最终增强之间的短期会话边界。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public interface PlanningSessionService {

    /** 对初步相关文件执行安全过滤和分析，并保存可在后续请求中引用的短期快照。 */
    PlanningContextPreparation prepareContext(PlanningContextRequest request);

    /** 解析计划请求中的上下文引用，并确保它由同一份需求创建且尚未过期。 */
    ResolvedPlanningContext resolveForPlan(
            PlanningContextReference reference,
            String rawPrompt,
            String contextDescription
    );

    /** 保存服务端实际展示的问题，使最终回答能够绑定同一次需求和上下文。 */
    PlanRegistration registerPlan(
            String rawPrompt,
            String contextDescription,
            List<ConversationMessage> conversationHistory,
            ResolvedPlanningContext planningContext,
            List<PlanQuestion> questions,
            String modelId
    );

    /** 兼容未显式选择模型的既有调用方。 */
    PlanRegistration registerPlan(
            String rawPrompt,
            String contextDescription,
            List<ConversationMessage> conversationHistory,
            ResolvedPlanningContext planningContext,
            List<PlanQuestion> questions
    );

    /** 校验最终回答与服务端计划一致，并使用服务端问题文案替换客户端回传文案。 */
    ConfirmedPlan confirm(
            String rawPrompt,
            String contextDescription,
            List<ConversationMessage> conversationHistory,
            PlanConfirmation confirmation
    );

    /** 最终文件集合与计划前分析完全一致时复用快照，避免重复完整分析。 */
    Optional<ContextSnapshot> reusableContext(
            ConfirmedPlan confirmedPlan,
            ContextAnalysisRequest filteredContext,
            String analysisQuery
    );

    /** 验证计划存在、尚未过期且属于当前登录用户。 */
    void assertAccessible(String planId);

    /**
     * 已通过所有权和有效期校验的上下文引用及安全摘要。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    record ResolvedPlanningContext(
            PlanningContextReference reference,
            PlanningContextDigest digest
    ) {
    }

    /**
     * 新计划标识、关联上下文和过期时间。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    record PlanRegistration(
            String planId,
            PlanningContextReference planningContext,
            Instant expiresAt
    ) {
    }

    /**
     * 用户确认后供最终增强流程消费的计划数据。
     *
     * @author QingNiao
     * @since 0.1.0
     */
    record ConfirmedPlan(
            List<PlanAnswer> answers,
            PlanningContextReference planningContext,
            boolean bound,
            PlanningContextDigest planningContextDigest,
            String modelId
    ) {

        public ConfirmedPlan {
            answers = List.copyOf(answers);
        }

        /** 兼容未绑定事实摘要的现有调用方。 */
        public ConfirmedPlan(List<PlanAnswer> answers, PlanningContextReference planningContext, boolean bound) {
            this(answers, planningContext, bound, null, null);
        }

        /** 兼容调用方在新增模型绑定之前构造的确认对象。 */
        public ConfirmedPlan(List<PlanAnswer> answers, PlanningContextReference planningContext,
                boolean bound, PlanningContextDigest planningContextDigest) {
            this(answers, planningContext, bound, planningContextDigest, null);
        }
    }
}
