package com.promptoptimizer.provider.domain;

import com.promptoptimizer.common.security.SensitiveValueScanner;
import java.util.List;

/**
 * 67在既有准备调用中分开登记原有要求与补全提案，不增加来源、用户确认或模型阶段。
 * 提案只辅助正文写作；最终正文仍独立建立来源及覆盖记录，不能继承准备阶段的通过结论。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public record NaturalPromptCompletionPreparation(List<NaturalTaskScope.Action> actions, NaturalTaskScope.Response response,
        List<NaturalPromptPhasePayloads.Requirement> requirements, List<NaturalTaskUnderstanding.Addition> additions) {

    /** 先沿用原要求的严格来源与题答核验，再检查提案容量；不将提案合并进原有要求。 */
    public Prepared resolve(NaturalPromptInput input) {
        var registry = new NaturalPromptRequirementRegistry.Preparation(actions, response, requirements).resolve(input);
        var proposals = NaturalPromptRequirementRegistry.bounded(additions, 6);
        for (var proposal : proposals) {
            String gap = proposal.gap();
            if (gap == null || gap.isBlank() || gap.length() > 20_000 || new SensitiveValueScanner().containsCredential(gap)) {
                throw NaturalPromptRequirementRegistry.invalid("补全提案缺少有效执行缺口");
            }
        }
        var suggestions = new NaturalTaskUnderstanding(registry.scope().actions().getFirst().text(), registry.scope().deliverable(),
                registry.requirements().stream().map(NaturalPromptRequirementRegistry.Entry::requirement).toList(), proposals,
                List.of(), new NaturalTaskReview(NaturalTaskReview.InputReadiness.NOT_ASSESSED, List.of(), List.of(), List.of()))
                .resolve(input);
        return new Prepared(registry, suggestions);
    }

    /** 同请求辅助结果；零提案不表示充分，提案也不表示正文已经采用或正确。 */
    public record Prepared(NaturalPromptRequirementRegistry.Prepared registry, NaturalTaskUnderstanding suggestions) { }
}
