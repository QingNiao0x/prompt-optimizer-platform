package com.promptoptimizer.context.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @DateTime: 2026-09-12
 * @Author: QingNiao
 * @ProjectName: prompt-optimizer-platform
 * @Description: 验证长内容在任务检索和无关键词命中时都能覆盖全文的代表位置。
 */
class ContentChunkSelectorTest {

    @Test
    void shouldUseDistributedFallbackWhenQueryDoesNotMatchContent() {
        StringBuilder content = new StringBuilder("a".repeat(62_000));
        content.replace(18_000, 18_018, "QUARTER-MARKER-26");
        content.replace(45_000, 45_023, "THREE-QUARTER-MARKER");

        ContentChunkSelector.Selection selection = new ContentChunkSelector().select(
                content.toString(),
                "请根据全文生成一份演讲稿",
                30_000,
                5
        );

        assertThat(selection.content())
                .contains("QUARTER-MARKER-26")
                .contains("THREE-QUARTER-MARKER");
        assertThat(selection.totalChunks()).isGreaterThan(5);
        assertThat(selection.contextLimited()).isTrue();
    }
}
