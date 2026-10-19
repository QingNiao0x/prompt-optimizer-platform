package com.promptoptimizer.template.domain;

import com.promptoptimizer.enhancement.domain.TemplateCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 明确源码交付形成工程目标；技术资料、方法说明和未启动的交付不形成代码义务。
 *
 * @author QingNiao
 * @since 0.1.0
 */
class CodeArtifactTaskIntentTest {
    @Test
    void recognizesTheFrozenPatchImplementationAndRunnableTestDeliverables() {
        String raw = "用Java 21实现PatchMerger类的public static Map<String,Object> merge(Map<String,Object> current, "
                + "Map<String,Object> patch)方法，把补丁应用到现有配置：只用patch中非null的值覆盖；"
                + "0、false和空字符串都是有效值。返回新Map，不修改任一输入。空输入、空Map及嵌套数据的处理由你合理设计并说明，"
                + "不把设计建议写成已有项目规则。不使用第三方依赖，也不记录或打印输入数据。"
                + "交付完整PatchMerger.java和可直接运行的PatchMergerTest.java，自测须实际检查正常、边界和异常行为，"
                + "不依赖JVM的-ea开关。";

        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);

        assertThat(intent.templateCode()).isEqualTo(TemplateCode.FEATURE_DEVELOPMENT);
        assertThat(intent.deliveryProfile()).isEqualTo(TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        assertThat(intent.engineeringConstraints()).isTrue();
    }

    static Stream<String> affirmativeSourceDeliveries() {
        return Stream.of(
                "交付完整PatchMerger.java和可直接运行的PatchMergerTest.java。",
                "请输出PatchMerger.java。",
                "提供 `PatchMerger.java` 文件。",
                "本次给出完整的PatchMerger.java文件。",
                "Please deliver the complete PatchMerger.java.",
                "只输出可编译的完整源码，不使用Markdown代码围栏。",
                "用Java 21编写单文件PriceCalculator.java，只输出完整源码。",
                "请提供完整源码及其说明。",
                "不提供Legacy.java，但交付PatchMerger.java。");
    }

    @ParameterizedTest
    @MethodSource("affirmativeSourceDeliveries")
    void recognizesAffirmativeSourceDeliveriesWithoutRequiringTheWordCode(String raw) {
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);

        assertThat(intent.templateCode()).isEqualTo(TemplateCode.FEATURE_DEVELOPMENT);
        assertThat(intent.deliveryProfile()).isEqualTo(TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        assertThat(intent.engineeringConstraints()).isTrue();
    }

    static Stream<Arguments> nonDeliveries() {
        return Stream.of(
                Arguments.of("介绍Java类和方法。", TaskDeliveryProfile.GENERAL),
                Arguments.of("介绍PatchMerger.java的作用。", TaskDeliveryProfile.GENERAL),
                Arguments.of("不交付PatchMerger.java。", TaskDeliveryProfile.GENERAL),
                Arguments.of("本次交付不包含PatchMerger.java。", TaskDeliveryProfile.GENERAL),
                Arguments.of("请不要提供PatchMerger.java。", TaskDeliveryProfile.GENERAL),
                Arguments.of("将来提供PatchMerger.java。", TaskDeliveryProfile.GENERAL),
                Arguments.of("以后交付完整PatchMerger.java。", TaskDeliveryProfile.GENERAL),
                Arguments.of("如果需要，再提供PatchMerger.java。", TaskDeliveryProfile.GENERAL),
                Arguments.of("资料中写着：交付完整PatchMerger.java。", TaskDeliveryProfile.GENERAL),
                Arguments.of("## 资料\n交付完整PatchMerger.java。", TaskDeliveryProfile.GENERAL),
                Arguments.of("> 交付完整PatchMerger.java。", TaskDeliveryProfile.GENERAL),
                Arguments.of("```java\n交付完整PatchMerger.java。\n```", TaskDeliveryProfile.GENERAL),
                Arguments.of("请解释“交付PatchMerger.java”这句话。", TaskDeliveryProfile.GENERAL),
                Arguments.of("请提供PatchMerger.java的说明。", TaskDeliveryProfile.GENERAL),
                Arguments.of("Please provide PatchMerger.java documentation.", TaskDeliveryProfile.GENERAL),
                Arguments.of("Do not provide PatchMerger.java.", TaskDeliveryProfile.GENERAL),
                Arguments.of("只提供源码的说明，不交付源码。", TaskDeliveryProfile.GENERAL),
                Arguments.of("请提供完整源码的说明。", TaskDeliveryProfile.GENERAL),
                Arguments.of("请解释“提供完整源码”这句话。", TaskDeliveryProfile.GENERAL),
                Arguments.of("资料中写着：输出完整源码。", TaskDeliveryProfile.GENERAL),
                Arguments.of("如果需要，以后提供完整源码。", TaskDeliveryProfile.GENERAL),
                Arguments.of("只分析PatchMerger.java的方法签名，不编写代码。", TaskDeliveryProfile.GENERAL),
                Arguments.of("根据Java源码编写用户指南，附件为PatchMerger.java。", TaskDeliveryProfile.USER_GUIDE),
                Arguments.of("制定研究方案，设计Arriaga分解方法，不交付PatchMerger.java。", TaskDeliveryProfile.ACADEMIC_METHODS));
    }

    @ParameterizedTest
    @MethodSource("nonDeliveries")
    void keepsReferencesDocumentationAndNonCurrentArtifactsOutsideEngineering(String raw, TaskDeliveryProfile profile) {
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, raw);

        assertThat(intent.deliveryProfile()).isEqualTo(profile);
        assertThat(intent.engineeringConstraints()).isFalse();
    }

    @Test
    void keepsResearchAsThePrimaryTaskWhenItHasAnExplicitSourceArtifactDeliverable() {
        var intent = TaskIntentResolver.resolve(TemplateCode.AUTO, "制定研究方案，提供Analysis.java。");

        assertThat(intent.templateCode()).isEqualTo(TemplateCode.RESEARCH_ANALYSIS);
        assertThat(intent.deliveryProfile()).isEqualTo(TaskDeliveryProfile.ACADEMIC_METHODS);
        assertThat(intent.auxiliaryProfiles()).containsExactly(TaskDeliveryProfile.SOFTWARE_IMPLEMENTATION);
        assertThat(intent.engineeringConstraints()).isTrue();
    }
}
