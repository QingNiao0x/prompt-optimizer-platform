package com.promptoptimizer.common.logging;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

/** 错误响应中仅提取整数用量，缺失与越界不伪造为零，不传播解析异常消息。 */
class ModelTokenUsageReaderTest {
    @Test
    void readsOnlyReportedIntegerUsageAndIgnoresFreeText() {
        var usage = read("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15,\"prompt_cache_hit_tokens\":4,\"prompt_cache_miss_tokens\":6},\"message\":\"private response\"}");
        assertThat(usage.totalTokens()).isEqualTo(15);
        assertThat(usage.cacheState()).isEqualTo("MIXED");
        assertThat(usage.toString()).doesNotContain("private response");
        assertThat(read("{\"usage\":{\"prompt_tokens\":\"10\",\"completion_tokens\":1.5,\"total_tokens\":999999999999999999999}}"))
                .isEqualTo(new ModelCallLogger.TokenUsage(-1L, -1L, -1L));
    }

    @Test
    void emptyInvalidAndOversizedResponsesRemainUnknown() {
        assertThat(read("not json")).isNull();
        assertThat(read("null")).isNull();
        assertThat(read("{}")).isNull();
        assertThat(ModelTokenUsageReader.readErrorUsage(null)).isNull();
        assertThat(ModelTokenUsageReader.readErrorUsage(new byte[65537])).isNull();
    }

    private ModelCallLogger.TokenUsage read(String json) {
        return ModelTokenUsageReader.readErrorUsage(json.getBytes(StandardCharsets.UTF_8));
    }
}
