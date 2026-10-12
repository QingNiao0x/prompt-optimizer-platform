package com.promptoptimizer.enhancement.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** 覆盖真实研究案例中的“个汉字”，计量词不能使用户篇幅规划静默失效。 */
class TaskBodyLengthContractTest {
    @ParameterizedTest
    @ValueSource(strings={"正文800至1200个汉字，附表另计。", "正文800至1200汉字，附表另计。", "正文800—1200字，附表另计。"})
    void recognizesTheExplicitBodyRangeWithOrWithoutAMeasureWord(String requirement) {
        assertThat(TaskBodyLengthContract.guidance("撰写研究方法部分。"+requirement))
                .contains("800–1200", "1000", "原定标题、附表");
    }
    @Test void appendixAndQuotedTranslationDoNotBecomeBodyBudgets() {
        assertThat(TaskBodyLengthContract.guidance("撰写研究方法部分。附表800至1200个汉字。")).isEmpty();
        assertThat(TaskBodyLengthContract.guidance("翻译为英文：正文800至1200个汉字。")).isEmpty();
    }
}
