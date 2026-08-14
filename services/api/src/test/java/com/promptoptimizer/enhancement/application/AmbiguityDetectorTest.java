package com.promptoptimizer.enhancement.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AmbiguityDetectorTest {

    private final AmbiguityDetector detector = new AmbiguityDetector();

    @Test
    void shouldReportMissingDetailsWhenPromptIsVague() {
        assertThat(detector.detect("弄个排序"))
                .anyMatch(item -> item.contains("模糊动作"))
                .anyMatch(item -> item.contains("输入来源"))
                .anyMatch(item -> item.contains("输出内容"))
                .anyMatch(item -> item.contains("验收标准"));
    }

    @Test
    void shouldAvoidInputAndOutputWarningsWhenPromptIsSpecific() {
        assertThat(detector.detect("接收整数列表作为输入，返回升序列表，并用测试验证重复元素和空列表"))
                .noneMatch(item -> item.contains("输入来源"))
                .noneMatch(item -> item.contains("输出内容"))
                .noneMatch(item -> item.contains("验收标准"));
    }
}
