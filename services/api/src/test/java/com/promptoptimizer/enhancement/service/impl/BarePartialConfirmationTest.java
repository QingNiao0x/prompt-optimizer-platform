package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.dto.PlanAnswer;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回放真实部分回答的“仅确认”前缀，不要求用户重复“本次”，也不借此确认其他参数。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class BarePartialConfirmationTest {
    private static final String RAW = "完整性指标分母尚未确定。跨字段一致性指标分母尚未确定。";

    @Test
    void acceptsTheExactConfirmedPartInBothNarrativeAndTable() {
        for (String prefix : List.of("仅确认", "只确认", "本次仅确认")) {
            var contract = contract(prefix + "完整性指标分母包含所有有效出院记录。"
                    + "跨字段一致性指标分母仍未确定，不能沿用完整性分母。");
            assertThatCode(() -> contract.validate("用户已确认完整性指标分母。\n|指标|分母|状态|\n|---|---|---|\n"
                    + "|完整性指标|所有有效出院记录|已确认|\n|跨字段一致性指标|待确认|未决|", "sections.OUTPUT"))
                    .as(prefix).doesNotThrowAnyException();
        }
    }

    @Test
    void theOtherMetricRemainsUnknownEvenInsideTheSameAnswer() {
        var contract = contract("仅确认完整性指标分母包含所有有效出院记录。跨字段一致性指标分母仍未确定。");
        assertThatThrownBy(() -> contract.validate("用户已确认跨字段一致性指标分母。", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatThrownBy(() -> contract.validate("|指标|分母|\n|---|---|\n|跨字段一致性指标|所有有效出院记录|", "sections.OUTPUT"))
                .isInstanceOf(ProviderResponseValidationException.class);
    }

    @Test
    void futureProposalAndAnotherObjectCannotProvideCurrentConfirmation() {
        for (String answer : List.of("如果以后仅确认完整性指标分母包含所有有效出院记录，再修订方案。",
                "建议仅确认完整性指标分母包含所有有效出院记录，目前仍未决定。",
                "仅确认新生儿完整性指标分母包含新生儿记录，原完整性指标分母仍未确定。")) {
            assertThatThrownBy(() -> contract(answer).validate("用户已确认完整性指标分母。", "sections.OUTPUT"))
                    .as(answer).isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    private static UnresolvedDecisionContract contract(String answer) {
        return UnresolvedDecisionContract.from(RAW, ConfirmedDecisionSet.from(List.of(
                new PlanAnswer("denominator", "各指标分母如何确定？", answer))));
    }
}
