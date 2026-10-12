package com.promptoptimizer.provider.domain;

import java.util.List;

/**
 * 同次增强调用的实质缺口判断，用于介入幅度和展示，不授予新事实或权限。
 * 旧适配器缺省时保守兼容，模型建议仍须通过保真、确认范围及资料校验。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record TaskEnhancementAssessment(Sufficiency sufficiency, List<Gap> gaps, Presentation presentation) {
    /** 充分需求不能同时声明必须补全的缺口；字段有界，避免分析元数据无界增长。 */
    public TaskEnhancementAssessment {
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        if (sufficiency == null || presentation == null || gaps.size() > 6
                || sufficiency == Sufficiency.SUFFICIENT && !gaps.isEmpty()
                || sufficiency == Sufficiency.NEEDS_COMPLETION && gaps.isEmpty()) throw invalid();
    }
    /** 判断依据是本次执行条件，不是输入语言、字数或限制词计数。 */
    public boolean preserveWording() { return sufficiency == Sufficiency.SUFFICIENT; }

    /**
     * 模型声明的专业补全不能同时成为必须由用户决定的阻塞项。
     * 矛盾响应交给现有有限修复，不静默删问题；原文、资料及有效 Plan 的未决事项仍由组装器独立保留。
     */
    public void validateModelDecisions(List<String> findings) {
        if (sufficiency == Sufficiency.NEEDS_COMPLETION && findings != null && !findings.isEmpty()
                && gaps.stream().noneMatch(gap -> gap.kind() == Kind.USER_DECISION)) {
            throw new ProviderResponseValidationException(
                    ProviderResponseValidationException.Reason.ASSESSMENT_DECISION_CONFLICT, "assessment.gaps");
        }
    }

    /** 已知目标、材料和有效确认是否足以执行。 */
    public enum Sufficiency { SUFFICIENT, NEEDS_COMPLETION }
    /** 提示词呈现形式，不改变下游作品的字数、格式和交付范围。 */
    public enum Presentation { COMPACT, STRUCTURED }
    /** 专业候选与用户决定独立，建议不等于已选事实。 */
    public enum Kind { EXECUTION_STEP, INPUT_OUTPUT, EDGE_CASE, QUALITY_CHECK, PROFESSIONAL_OPTION, USER_DECISION }
    /** 影响执行的缺口与补全动作；basis是一句可核对的依据，不是思维链。 */
    public record Gap(Kind kind, String missing, String completion, String basis) {
        public Gap {
            if (kind == null || !valid(missing) || !valid(completion) || !valid(basis)) throw invalid();
        }
        private static boolean valid(String value) { return value != null && !value.isBlank() && value.length() <= 500; }
    }
    private static ProviderResponseValidationException invalid() {
        return new ProviderResponseValidationException(ProviderResponseValidationException.Reason.ASSESSMENT_INVALID, "assessment");
    }
}
