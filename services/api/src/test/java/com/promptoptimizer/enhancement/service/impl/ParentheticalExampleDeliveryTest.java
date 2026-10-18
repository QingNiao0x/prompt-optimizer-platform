package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.PromptSection;
import com.promptoptimizer.enhancement.domain.PromptSectionType;
import com.promptoptimizer.provider.domain.ExampleContract;
import com.promptoptimizer.provider.domain.ProviderResponseValidationException;
import java.util.EnumMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 414原件的括号/列表示例义务不能绕过OFF；否定父句、必要测试及引文不变成示例要求。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class ParentheticalExampleDeliveryTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "交付以下内容：\n1. 完整可运行的 Python 代码（含请求、解析、异常处理与入口调用示例）。",
            "完整源码（含调用示例）。",
            "完整源码(包括输入输出示例)。",
            "交付源码，含输入输出示例。",
            "完整源码（包含调用示例）。",
            "不得不交付完整源码（含调用示例）。",
            "不交付方案，但交付源码（含调用示例）。"
    })
    void positiveParentheticalAndListDeliveriesAreRejectedWhenExamplesAreOff(String output) {
        assertThatThrownBy(() -> ExampleResultIntegrator.integrate(sections(output), ExampleContract.from("实现标题抓取功能。", false)))
                .isInstanceOfSatisfying(ProviderResponseValidationException.class, error -> {
                    org.assertj.core.api.Assertions.assertThat(error.getReason()).isEqualTo(ProviderResponseValidationException.Reason.RULE_CONFLICT);
                    org.assertj.core.api.Assertions.assertThat(error.getField()).isEqualTo("sections.OUTPUT");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "不交付完整代码（含调用示例），只提供设计说明。",
            "禁止输出完整代码（包括调用示例），保留原始资料。",
            "不要提供完整代码（包含调用示例），只说明设计。",
            "交付源码，不含示例。",
            "完整代码（不含调用示例）。",
            "完整代码（含必要测试示例），不要求额外示例。",
            "未来确认：\n完整代码（含调用示例）。",
            "引用：\n完整代码（含调用示例）。",
            "> 完整代码（含调用示例）。",
            "```text\n完整代码（含调用示例）。\n```",
            "资料说明：\"包含有示例的段落\"；只提取资料标题。"
    })
    void negativeLiteralDeferredAndNecessaryTestScopesRemainValid(String output) {
        assertThatCode(() -> ExampleResultIntegrator.integrate(sections(output), ExampleContract.from("实现标题抓取功能。", false)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = { "完整源码（含调用示例）。", "完整源码(包括输入输出示例)。" })
    void explicitUserExamplesStillOverrideTheOffSwitch(String output) {
        assertThatCode(() -> ExampleResultIntegrator.integrate(sections(output), ExampleContract.from("实现标题抓取功能，提供输入与预期输出示例。", false)))
                .doesNotThrowAnyException();
    }

    private EnumMap<PromptSectionType, PromptSection> sections(String output) {
        var sections = new EnumMap<PromptSectionType, PromptSection>(PromptSectionType.class);
        sections.put(PromptSectionType.OUTPUT, new PromptSection(PromptSectionType.OUTPUT, "输出", output));
        return sections;
    }
}
