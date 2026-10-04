package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验有效值保护的否定范围，避免“保留且不覆盖”被当成反向覆盖，并保持真实反转拦截。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class FidelityScopeRegressionTest {
    @Test
    void shouldRetainTheNoGuessingBoundaryWithoutRepeatingADetachedPendingStatus() {
        var rules = new RequirementFidelityGuard().explicitRules(
                "## 尚待明确\n候选地区缺失时如何处理？当前尚未决定，不得默认补全。\n取消时必须保持原值。", List.of());
        assertThat(rules).contains("对于仍未明确的条件，不得默认补全。", "取消时必须保持原值。")
                .noneMatch(rule -> rule.contains("当前尚未决定"));
    }
    @Test
    void shouldDistinguishProhibitedCancellationEffectsFromActualChanges() {
        var guard = new RequirementFidelityGuard();
        var rules = List.of("用户点击取消必须保持全部字段原值不变。");
        for (String allowed : List.of("用户取消时保持原值，禁止清空或覆盖原值。",
                "取消或关闭时保留原值，不能清空字段或覆盖原值。",
                "取消时保留全部字段原值，避免清空或覆盖原值。")) {
            assertThatCode(() -> guard.validate(allowed, rules, "sections.TASK"))
                    .as(allowed).doesNotThrowAnyException();
        }
        for (String rejected : List.of("用户取消时清空全部字段原值。",
                "取消时不清空字段，但覆盖原值。", "删除原有保留原值的保护逻辑。")) {
            assertThatThrownBy(() -> guard.validate(rejected, rules, "sections.TASK"))
                    .as(rejected).isInstanceOf(ProviderResponseValidationException.class);
        }
    }

    @Test
    void shouldAcceptPreservingValidValuesWhileRejectingTheirReplacement() {
        var guard = new RequirementFidelityGuard();
        var rules = List.of("0、false 和非空字符串都是有效原值，必须保留。");
        assertThatCode(() -> guard.validate("保留 0、false，不覆盖有效原值。", rules, "sections.TASK"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.validate("将 0、false 视为空值并覆盖。", rules, "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatThrownBy(() -> guard.validate("不能泄露身份信息，但将 0、false 视为空值。", rules, "sections.TASK"))
                .isInstanceOf(ProviderResponseValidationException.class);
        assertThatCode(() -> guard.validate("不得将 0、false 视为空值。", rules, "sections.TASK"))
                .doesNotThrowAnyException();
    }
}
