package com.promptoptimizer.enhancement.service.impl;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 大量独立来源规则的共享链路回归；耗时仅记录用于同机前后比较，不设脆弱的 CI 时间断言。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class RequirementFidelityWorkloadTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequirementFidelityWorkloadTest.class);

    @Test
    void shouldValidateAllRulesAndStillRejectTheLastReversedRule() {
        var guard = new RequirementFidelityGuard();
        List<String> rules = IntStream.range(0, 90).mapToObj(index -> "模块" + index + "必须保留已有业务。")
                .toList();
        String draft = String.join("\n", rules);
        long started = System.nanoTime();
        assertThat(guard.compatibleSourceRules(rules, List.of())).containsExactlyElementsOf(rules);
        var validation = guard.prepare(rules);
        for (int section = 0; section < 4; section++) validation.validate(draft, "sections.TASK");
        LOGGER.info("event=fidelity.synthetic_workload ruleCount={} sectionCount=4 durationMs={}",
                rules.size(), (System.nanoTime() - started) / 1_000_000);
        assertThatThrownBy(() -> guard.validate("模块89无需保留已有业务。", rules, "sections.TASK"))
                .isInstanceOf(com.promptoptimizer.provider.domain.ProviderResponseValidationException.class);
    }
}
