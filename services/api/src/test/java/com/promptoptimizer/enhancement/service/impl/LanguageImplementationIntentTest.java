package com.promptoptimizer.enhancement.service.impl;

import com.promptoptimizer.enhancement.domain.TemplateCode;
import com.promptoptimizer.template.domain.TaskDeliveryProfile;
import com.promptoptimizer.template.domain.TaskIntentResolver;
import com.promptoptimizer.template.service.impl.PromptTemplateRegistryImpl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语言工具与肯定实现动作共同建立代码交付；解释、资料、条件和明确禁代码不能被语言名覆盖。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class LanguageImplementationIntentTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "用TypeScript实现文本去重。", "使用JavaScript实现文本清洗。", "请用 Rust 来实现文件压缩。",
            "用Go实现字符串倒序。", "使用C++实现整数排序。", "用C#实现日期格式化。",
            "任务：\n用TypeScript实现文本去重。",
            "未来任务：\n用Rust实现文件压缩。\n任务：\n用TypeScript实现文本去重。",
            "如果以后需要再做，本次用TypeScript实现文本去重。"
    })
    void affirmativeImplementationWithAProgrammingToolKeepsTheCodeDelivery(String raw) {
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
        assertThat(intent.deliveryProfile()).isEqualTo(TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        assertThat(intent.templateCode()).isEqualTo(TemplateCode.FEATURE_DEVELOPMENT);
        assertThat(intent.engineeringConstraints()).isTrue();
        assertThat(new PromptTemplateRegistryImpl().infer(raw)).isEqualTo(TemplateCode.FEATURE_DEVELOPMENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "解释如何用TypeScript实现文本去重。", "介绍TypeScript文本去重的原理。",
            "用TypeScript实现文本去重的思路。", "只解释，不交付代码。用TypeScript实现文本去重。",
            "用TypeScript实现文本去重。不输出代码，只解释步骤。",
            "用TypeScript实现文本去重。不要输出代码。", "用TypeScript实现文本去重。请勿交付代码。",
            "原文：用TypeScript实现文本去重。", "> 用TypeScript实现文本去重。",
            "```text\n用TypeScript实现文本去重。\n```", "参考资料：\n用TypeScript实现文本去重。",
            "未来任务：\n用TypeScript实现文本去重。", "如果以后确认需要，用TypeScript实现文本去重。",
            "背景：\n用TypeScript实现文本去重。", "使用TypeScript实现过的文本去重是既有能力。",
            "用Python完成数据分析。", "用R实现统计分析。", "用JavaScript实现研究方案。",
            "使用Excel实现文本去重。", "背景：\n用TypeScript实现文本去重。\n备注：\n待以后再做。",
            "用TypeScript还是Java实现文本去重？"
    })
    void explanationFactsDeferredActionsAndNonCodeArtifactsDoNotAcquireCodeObligations(String raw) {
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
        assertThat(intent.deliveryProfile()).isNotEqualTo(TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        assertThat(intent.engineeringConstraints()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "用TypeScript实现文本去重，不额外提供输入输出示例。",
            "用TypeScript实现文本去重，不附带示例代码。",
            "使用requests库爬取网页标题，不附带示例代码。"
    })
    void decliningExtraExamplesDoesNotCancelTheRequestedImplementation(String raw) {
        assertThat(TaskIntentResolver.resolve(TemplateCode.AUTO, raw).deliveryProfile())
                .isEqualTo(TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
    }

    @org.junit.jupiter.api.Test
    void sharedTaskViewAndSafetyRulesReceiveTheSameRepairedIdentity() {
        String raw = "用TypeScript实现文本去重。";
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);
        var task = EffectiveTaskViewFactory.create(raw, TemplateCode.AUTO, java.util.List.of(), java.util.List.of(), false);
        assertThat(task.intent()).isEqualTo(intent);
        var context = new com.promptoptimizer.context.domain.ContextSnapshot("", java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), "EMPTY");
        var constraints = new com.promptoptimizer.policy.service.impl.ConstraintCompleterImpl().completeForTask(context,
                com.promptoptimizer.enhancement.dto.PermissionPolicyInput.empty(), true, intent);
        assertThat(constraints.platformMandatory()).hasSize(5);
        assertThat(constraints.platformMandatory()).anyMatch(value -> value.contains("Token"));
    }
}
