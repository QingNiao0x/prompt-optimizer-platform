package com.promptoptimizer.textpolish.service;

import com.promptoptimizer.common.logging.ModelCallAuditContext;
import com.promptoptimizer.common.logging.ModelCallObservation;
import com.promptoptimizer.provider.service.PlatformModelCatalog.ModelEntry;
import com.promptoptimizer.textpolish.domain.TextPolishModels.Input;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 演示 Provider 同样生成匿名对账，不能以零 Token 伪装真实用量或把正文送入审计。
 * @author QingNiao
 * @since 0.1.0
 */
class MockTextPolishProviderTest {
    @Test
    void mockCallsHaveIndependentIdsAndUnknownTokenUsageWithoutContent() {
        var calls = new ArrayList<ModelCallObservation>();
        UUID tenant = UUID.randomUUID();
        var model = new ModelEntry(UUID.randomUUID(), "fixture", "mock", "fixture", "fixture", true, true, 0);
        var input = new Input("private synthetic article", null, null, "", "", List.of(), "fixture");
        try (var scope = ModelCallAuditContext.bind(new ModelCallAuditContext.Snapshot(tenant, (id, call) -> {
            assertThat(id).isEqualTo(tenant); calls.add(call);
        }))) {
            var provider = new MockTextPolishProvider();
            assertThat(provider.polish(input, model)).isEqualTo(input.sourceText());
            provider.polish(input, model);
        }
        assertThat(calls).hasSize(2).extracting(ModelCallObservation::eventId).doesNotHaveDuplicates();
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.operation()).isEqualTo("text.polish");
            assertThat(call.mock()).isTrue(); assertThat(call.successful()).isTrue();
            assertThat(call.transportState()).isEqualTo("MOCK");
            assertThat(call.usage()).isNull();
            assertThat(call.toString()).doesNotContain(input.sourceText());
        });
        assertThat(ModelCallAuditContext.capture()).isNull();
    }
}
