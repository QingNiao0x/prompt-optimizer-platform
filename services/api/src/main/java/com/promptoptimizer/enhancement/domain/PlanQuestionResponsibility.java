package com.promptoptimizer.enhancement.domain;

import java.util.List;

/**
 * 兼容已采集模型响应的内部建议；当前责任策略不以模型标签决定删题。
 * 不构成用户选择或权限，不向客户端公开；新响应无需再生成此字段。
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
