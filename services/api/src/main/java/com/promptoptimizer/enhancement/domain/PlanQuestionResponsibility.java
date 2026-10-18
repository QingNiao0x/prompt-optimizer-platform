package com.promptoptimizer.enhancement.domain;

import java.util.List;

/**
 * 同次模型给出的可选执行责任建议；只用于服务端复核，不是用户选择或权限来源。
 * 原题、原目标及全部候选ID绑定不匹配时继续保留问题；旧响应不要求此字段。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record PlanQuestionResponsibility(String owner, String impact, String rawGoal,
        String question, List<String> optionIds) {
    public PlanQuestionResponsibility {
        optionIds = optionIds == null ? List.of() : List.copyOf(optionIds);
    }
}
