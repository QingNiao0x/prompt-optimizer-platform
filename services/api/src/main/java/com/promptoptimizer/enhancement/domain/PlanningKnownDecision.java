package com.promptoptimizer.enhancement.domain;

import java.util.List;

/**
 * 本次 Plan 输入中有明确证据的决定，以及可交给执行 Agent 的工程核查边界。
 * 仅作内部模型输入，不是用户确认答案，不代表未知实现已经核实，也不能覆盖平台约束。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PlanningKnownDecision(Kind kind, String subject, String evidence, List<String> sources) {
    public PlanningKnownDecision {
        sources = List.copyOf(sources);
    }

    /** 已定字段、条件和效果与工程核查分开；LOOKUP 只说明核查职责，不能补造实现事实。 */
    public enum Kind {
        FIELD_SCOPE,
        DECLARED_MODULE_SCOPE,
        MATCH_TRIGGER,
        EXISTING_UI_TRIGGER_LOOKUP,
        REGION_MATCH_SCOPE,
        FILL_WRITE_SCOPE,
        DETAIL_BEFORE_FILL,
        CANCEL_EFFECT,
        RECORD_ORDER,
        MATCH_KEYS,
        NO_MATCH_UI,
        ERROR_UI_LOOKUP,
        RESEARCH_STATUS,
        PLAN_INTERACTION_DEFINITION,
        WRITING_SECTION_SCOPE,
        REVIEWER_BLINDING,
        METHOD_SELECTION,
        INFORMATION_PARITY,
        EXISTING_REGION_LOOKUP,
        TEST_LAYER_LOOKUP,
        TEST_TARGET_LOOKUP
    }
}
