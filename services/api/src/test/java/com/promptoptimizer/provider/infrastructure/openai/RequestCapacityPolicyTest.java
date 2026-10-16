package com.promptoptimizer.provider.infrastructure.openai;

import com.promptoptimizer.provider.domain.ProviderCapacityException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RequestCapacityPolicyTest {
    private final RequestCapacityPolicy policy = new RequestCapacityPolicy();

    @Test
    void fullMessageBoundaryIncludesRepairAndDoesNotTruncateInput() {
        var messages = List.of("system", "中文与用户确认；不得删除例外");
        long input = policy.estimateInput(messages);
        var limits = Map.of("route:model", new RequestCapacityPolicy.Limit(input + 110, 100, 10, "test-fixture", "2026-10-16"));
        assertThat(policy.outputLimit("route:model", messages, 100, limits)).isEqualTo(100);
        assertThatThrownBy(() -> policy.outputLimit("route:model",
                List.of(messages.get(0), messages.get(1) + "a"), 100, limits)).isInstanceOf(ProviderCapacityException.class);
        assertThatThrownBy(() -> policy.outputLimit("route:model",
                List.of(messages.get(0), messages.get(1), "repair"), 100, limits)).isInstanceOf(ProviderCapacityException.class);
        assertThat(messages.get(1)).endsWith("不得删除例外");
    }

    @Test
    void capsOutputPerRouteAndDoesNotBorrowAnotherRouteCapacity() {
        var limits = Map.of("route:model", new RequestCapacityPolicy.Limit(1000, 100, 10, "test-fixture", "2026-10-16"));
        assertThat(policy.outputLimit("route:model", List.of("x"), 200, limits)).isEqualTo(100);
        assertThat(policy.outputLimit("other:model", List.of("x"), 200, limits)).isEqualTo(200);
        assertThatThrownBy(() -> policy.outputLimit("unknown", List.of("a".repeat(1_048_577)), 100, limits))
                .isInstanceOf(ProviderCapacityException.class);
        assertThatThrownBy(() -> new RequestCapacityPolicy.Limit(0, 100, 10, "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
